package com.keepasskey.sync.s3

import com.keepasskey.sync.model.SyncException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * ISSUE-P2-655：真实 S3 服务（隔离 MinIO）上的 SigV4 端到端验收。
 *
 * JVM 层既有用例只能证明「wire query 与 canonical query 是同一份输出」；本组把同一份输出去
 * 让**服务端**验签，覆盖三层能力：
 * 1. 含 `&` 的合法前缀必须被服务端接受（旧实现必然 403 SignatureDoesNotMatch）；
 * 2. 真实分页：服务端返回的续页 token 原样回传仍能通过验签并取到下一页；
 * 3. 负对照：错误 secret 必须被服务端以 403 拒绝，且 token 内 `&` 只作数据（服务端按
 *    「非法 token」而非「签名不匹配」拒绝，两者在 Provider 上映射为不同异常类型）。
 *
 * 由 `tools/local-sync/run_s3_matrix.py` 用独立临时数据目录与按次随机凭据启动服务并注入
 * 参数；未启用联调时整体跳过（`LIVE_SYNC_TEST=1` / `-DliveSyncTest`）。
 */
class LiveS3DirectoryListTest {

    private val endpoint = prop("liveS3Endpoint", "https://localhost:9000")
    private val bucket = prop("liveS3Bucket", "keepasskey-lab")
    private val access = credential("liveS3Access", "MINIO_ROOT_USER")
    private val secret = credential("liveS3Secret", "MINIO_ROOT_PASSWORD")

    private fun prop(key: String, default: String): String =
        System.getProperty(key) ?: System.getenv(key) ?: default

    private fun credential(propertyKey: String, envKey: String): String =
        System.getProperty(propertyKey)
            ?: System.getenv(envKey)
            ?: "kp" + UUID.randomUUID().toString().replace("-", "")

    private fun assumeLive() {
        Assume.assumeTrue(
            "跳过：未启用本地 S3 服务联调（设置 LIVE_SYNC_TEST=1 并启动 tools/local-sync 服务）",
            System.getenv("LIVE_SYNC_TEST") != null || System.getProperty("liveSyncTest") != null
        )
    }

    private fun newProvider(secretOverride: String = secret): S3SyncProvider = S3SyncProvider(
        endpoint = endpoint,
        bucketName = bucket,
        region = REGION,
        accessKeyId = access.toCharArray(),
        secretAccessKey = secretOverride.toCharArray(),
        usePathStyle = true,
        client = trustingClient()
    )

    private fun trustingClient(): OkHttpClient {
        val certBytes = loadCertBytes()
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setCertificateEntry("local", cert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, tmf.trustManagers, SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tmf.trustManagers[0] as X509TrustManager)
            // 仅测试：允许以 localhost 访问自签证书服务；生产仍走系统 CA 链与主机名校验
            .hostnameVerifier { hostname, _ -> hostname == "localhost" || hostname == "127.0.0.1" }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun loadCertBytes(): ByteArray {
        javaClass.classLoader?.getResourceAsStream("live/cert.pem")?.let { return it.readBytes() }
        val path = System.getProperty("liveCertPath") ?: System.getenv("LIVE_CERT_PATH")
            ?: throw IllegalStateException("无法定位本地测试证书 cert.pem（请设置 liveCertPath）")
        return java.io.File(path).readBytes()
    }

    @Test
    fun `含与号前缀的首分页与真实续页均被服务端接受`() = runBlocking {
        assumeLive()
        val provider = newProvider()
        val dir = "notes&archive-" + UUID.randomUUID().toString().take(8)
        val a = "$dir/a.kdbx"
        val b = "$dir/b.kdbx"
        try {
            assertTrue("连接测试(HEAD bucket): ${provider.testConnection()}", provider.testConnection().isSuccess)
            provider.upload(a, "fixture-a".toByteArray()).getOrThrow()
            provider.upload(b, "fixture-b".toByteArray()).getOrThrow()

            val first = provider.listRemoteDirectory(dir, cursor = null, pageSize = 1)
            assertTrue("含 & 的前缀必须通过服务端验签: ${first.exceptionOrNull()}", first.isSuccess)
            val firstPage = first.getOrThrow()
            assertEquals("首分页必须只返回按 key 排序的第一项", a, firstPage.entries.single().path)
            assertEquals("对象大小必须来自服务端响应", "fixture-a".toByteArray().size.toLong(),
                firstPage.entries.single().contentLength)
            assertTrue("pageSize=1 且有两项时必须给出续页", firstPage.truncated)
            val cursor = firstPage.nextCursor
            assertNotNull("截断页必须给出续页 token", cursor)
            val second = provider.listRemoteDirectory(dir, cursor = cursor!!, pageSize = 1)
            assertTrue("续页必须通过服务端验签: ${second.exceptionOrNull()}", second.isSuccess)
            val secondPage = second.getOrThrow()
            assertEquals("续页必须返回第二项", b, secondPage.entries.single().path)
            assertTrue("最后一页不得继续标为截断", !secondPage.truncated)
            assertNull("最后一页游标必须清空", secondPage.nextCursor)
        } finally {
            provider.delete(a)
            provider.delete(b)
            provider.clearCredentials()
        }
    }

    @Test
    fun `目录不存在时返回空页而非失败`() = runBlocking {
        assumeLive()
        val provider = newProvider()
        try {
            val empty = provider.listRemoteDirectory("no-such-dir-" + UUID.randomUUID(), pageSize = 10)
            assertTrue("空目录必须成功返回: ${empty.exceptionOrNull()}", empty.isSuccess)
            assertTrue("必须返回空页", empty.getOrThrow().entries.isEmpty())
            assertTrue("空页不得标为截断", !empty.getOrThrow().truncated)
        } finally {
            provider.clearCredentials()
        }
    }

    @Test
    fun `错误密钥被服务端以403拒绝`() = runBlocking {
        assumeLive()
        val wrong = newProvider(secretOverride = secret + "-wrong")
        try {
            val connection = wrong.testConnection()
            assertTrue("错误 secret 不得通过验签", connection.isFailure)
            assertTrue(
                "错误凭据必须映射为 AuthenticationError: ${connection.exceptionOrNull()}",
                connection.exceptionOrNull() is SyncException.AuthenticationError
            )
        } finally {
            wrong.clearCredentials()
        }
    }

    @Test
    fun `token内的与号只作数据而不产生额外参数`() = runBlocking {
        assumeLive()
        val provider = newProvider()
        try {
            // 该 token 不是服务端签发的合法游标：若客户端把值里的 `&` 当参数分隔符，
            // canonical query 会与服务端解析结果不一致 ⇒ 403 SignatureDoesNotMatch（AuthenticationError）。
            // 正确编码时服务端看到的是**一个** continuation-token 参数，只能就 token 本身报 400。
            val result = provider.listRemoteDirectory(
                remotePath = "probe&token",
                cursor = "a&b=c",
                pageSize = 1
            )
            assertTrue("非法规格请求必须失败而非伪装空目录", result.isFailure)
            assertTrue(
                "值内 & 必须只作数据：服务端应报 4xx 协议错误而不是签名不匹配（实际: ${result.exceptionOrNull()}）",
                result.exceptionOrNull() !is SyncException.AuthenticationError
            )
        } finally {
            provider.clearCredentials()
        }
    }

    private companion object {
        const val REGION = "us-east-1"
    }
}
