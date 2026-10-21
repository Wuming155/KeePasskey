package com.keepasskey.sync.engine

import com.keepasskey.sync.s3.S3SyncProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `ISSUE-P2-636`：**真实 S3 服务**上的 ETag 往返与崩溃窗口后的一致性。
 *
 * ## 为什么需要真实服务端
 *
 * 设备侧两阶段用例（`SyncCacheCrashRecoveryDeviceTest`）证明的是「崩溃后本地判据仍成立」，
 * 其中 ETag 用的是合成值——它只验证标记**传递**，不验证标记**来源**。
 * 这里把真实 MinIO 返回的 ETag 写进缓存基线，再走一遍崩溃窗口，回答的是：
 * 崩溃后下一轮同步读到的 ETag 是否仍与远端一致、过期基线是否会被如实拒绝。
 *
 * ## 覆盖
 *
 * 1. 上传后从**服务端**取到的 ETag 写入基线，`getState` 读回逐字一致；
 * 2. 崩溃窗口（新字节 + 未刷新 `.version`）后，基线 ETag **不得**被改写；
 * 3. 用崩溃前的 ETag 做条件上传：远端未变时成功、远端被他人改动后必须报冲突（不静默覆盖）；
 * 4. 崩溃后重新同步（以远端 ETag 前移基线）后判据回到「无本地修改」。
 *
 * 由 `tools/local-sync/run_s3_matrix.py` 启动隔离服务并注入参数；未启用联调时整体跳过。
 */
class LiveS3EtagCrashWindowTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

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

    private fun newProvider(): S3SyncProvider = S3SyncProvider(
        endpoint = endpoint,
        bucketName = bucket,
        region = REGION,
        accessKeyId = access.toCharArray(),
        secretAccessKey = secret.toCharArray(),
        usePathStyle = true,
        client = trustingClient()
    )

    @Test
    fun `真实服务端ETag经崩溃窗口后仍一致且过期基线被拒`() = runBlocking {
        assumeLive()
        val provider = newProvider()
        val cache = SyncCache(tmpFolder.newFolder("cache"))
        val remotePath = "p2-636/etag-${UUID.randomUUID().toString().take(8)}.kdbx"
        val original = "p2-636-original".toByteArray()
        val crashed = ByteArray(4096) { (it * 3 % 251).toByte() }

        assertTrue("连接测试: ${provider.testConnection()}", provider.testConnection().isSuccess)
        // 1. 首传，并从**服务端**取回真实 ETag
        provider.upload(remotePath, original, expectedEtag = null).getOrThrow()
        val remoteEtag = provider.getMetadata(remotePath).getOrThrow().etag
        assertTrue("服务端必须返回非空 ETag（否则条件写无从构造）", remoteEtag.isNotBlank())

        // 2. 建立一致基线：本地缓存与基线摘要对齐，ETag 取服务端真值
        cache.writeCache(remotePath, original)
        cache.updateBase(remotePath, SyncCache.sha256Hex(original), remoteEtag)
        assertEquals("基线 ETag 必须逐字等于服务端值", remoteEtag, cache.getState(remotePath)?.etag)
        assertFalse("建立基线后不得判为有本地修改", cache.hasLocalChanges(remotePath))

        // 3. 崩溃窗口：新字节落盘但不刷新 .version（真实崩溃落在 rename 与写 version 之间）
        cache.writeCache(remotePath, crashed, updateVersion = false)
        assertTrue("崩溃窗口后必须判为有本地修改", cache.hasLocalChanges(remotePath))
        assertEquals(
            "崩溃不得改写基线 ETag——远端仍是旧版本，本地修改尚未上传",
            remoteEtag,
            cache.getState(remotePath)?.etag
        )

        // 4. 用崩溃前的 ETag 做条件上传：远端未变 ⇒ 必须成功
        provider.upload(remotePath, crashed, expectedEtag = remoteEtag).getOrThrow()
        val etagAfterUpload = provider.getMetadata(remotePath).getOrThrow().etag
        assertNotEquals("上传后服务端 ETag 必须变化（内容已不同）", remoteEtag, etagAfterUpload)

        // 5. 过期基线必须被拒：再用旧 ETag 条件写，服务端已变 ⇒ 冲突，不得静默覆盖
        val stale = provider.upload(remotePath, original, expectedEtag = remoteEtag)
        assertTrue("过期 ETag 条件写必须失败（否则会静默覆盖他人更新）", stale.isFailure)

        // 6. 崩溃后重新同步：以远端当前 ETag 前移基线，判据回到「无本地修改」
        cache.updateBase(remotePath, SyncCache.sha256Hex(crashed), etagAfterUpload)
        assertFalse("重新同步后不得再判为有本地修改", cache.hasLocalChanges(remotePath))
        assertEquals(
            "基线前移必须同步新 ETag",
            etagAfterUpload,
            cache.getState(remotePath)?.etag
        )
        val sink = java.io.ByteArrayOutputStream()
        provider.download(remotePath, sink).getOrThrow()
        val downloaded = sink.toByteArray()
        assertTrue("远端内容必须与崩溃窗口里写入的字节一致", downloaded.contentEquals(crashed))
    }

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
            .hostnameVerifier { hostname, _ -> hostname == "localhost" || hostname == "127.0.0.1" }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun loadCertBytes(): ByteArray {
        javaClass.classLoader?.getResourceAsStream("live/cert.pem")?.let { return it.readBytes() }
        val path = System.getProperty("liveCertPath") ?: System.getenv("LIVE_CERT_PATH")
            ?: throw IllegalStateException("无法定位本地测试证书 cert.pem（请设置 liveCertPath）")
        return File(path).readBytes()
    }

    private companion object {
        const val REGION = "us-east-1"
    }
}
