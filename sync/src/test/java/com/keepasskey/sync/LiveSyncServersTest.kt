package com.keepasskey.sync

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.s3.S3SyncProvider
import com.keepasskey.sync.webdav.WebDavSyncProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit

/**
 * 针对本地真实 HTTPS 服务（WebDAV / MinIO S3）的端到端联调测试。
 *
 * 该测试默认跳过：需先启动 [tools/local-sync] 下的本地 HTTPS 服务，并设置环境变量
 * `LIVE_SYNC_TEST=1`（或 JVM 属性 `liveSyncTest`）才会执行。它验证的是真实协议栈：
 * - Provider 通过注入「信任本地自签名证书的 OkHttpClient」建立真实 TLS 连接；
 * - WebDAV：PROPFIND 连接测试 / PUT / GET 字节精确 / 元数据 / uploadAtomic 真实 MOVE / DELETE；
 * - S3：SigV4 连接测试 / 首传 If-None-Match / 覆盖 If-Match / 错误 ETag 触发 412 ConflictError；
 * - 下载→合并贯通：将三方库镜像经真实服务器上传/下载（字节精确），再用真实 KdbxMerger 三方合并。
 *
 * 注：生产路径强制 HTTPS + 系统 CA 链；此处注入自定义客户端仅为测试自签名证书，
 * 与既有的 MockWebServer 回环注入方式一致。
 */
class LiveSyncServersTest {

    private val webdavUrl = prop("liveWebdavUrl", "https://localhost:9443")
    private val webdavUser = prop("liveWebdavUser", "tester")
    private val webdavPass = prop("liveWebdavPass", "tester123")
    private val s3Endpoint = prop("liveS3Endpoint", "https://localhost:9000")
    private val s3Bucket = prop("liveS3Bucket", "keepasskey-test")
    private val s3Access = prop("liveS3Access", "tester")
    private val s3Secret = prop("liveS3Secret", "tester1234")

    private fun prop(key: String, default: String): String {
        return System.getProperty(key) ?: System.getenv(key) ?: default
    }

    private fun assumeLive() {
        Assume.assumeTrue(
            "跳过：未启用本地 HTTPS 服务联调（设置 LIVE_SYNC_TEST=1 并启动 tools/local-sync 服务后运行）",
            System.getenv("LIVE_SYNC_TEST") != null || System.getProperty("liveSyncTest") != null
        )
    }

    /** 构造信任本地自签名证书的 OkHttpClient（仅测试用途） */
    private fun createTrustingClient(): OkHttpClient {
        val certBytes = loadCertBytes()
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setCertificateEntry("local", cert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, tmf.trustManagers, SecureRandom())
        val x509 = tmf.trustManagers[0] as X509TrustManager
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, x509)
            .hostnameVerifier { hostname, _ -> hostname == "localhost" }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun loadCertBytes(): ByteArray {
        // 优先测试资源，其次 liveCertPath 指定路径
        val fromResource = javaClass.classLoader?.getResourceAsStream("live/cert.pem")?.readBytes()
        if (fromResource != null) return fromResource
        val path = System.getProperty("liveCertPath") ?: System.getenv("LIVE_CERT_PATH")
        if (path != null) return java.io.File(path).readBytes()
        throw IllegalStateException("无法定位本地测试证书 cert.pem（请设置 liveCertPath）")
    }

    @Test
    fun `LIVE WebDAV 真实 HTTPS 同步全链路`() = runTest {
        assumeLive()
        val client = createTrustingClient()
        val provider = WebDavSyncProvider(
            serverUrl = webdavUrl,
            username = webdavUser,
            passwordChars = webdavPass.toCharArray(),
            client = client
        )

        assertTrue("连接测试(PROPFIND)", provider.testConnection().isSuccess)

        val payload = "kdbx-payload-v1".toByteArray()
        val etag = provider.upload("vault.kdbx", payload).getOrThrow()
        assertNotNull("PUT 返回 ETag", etag)

        val downloaded = provider.download("vault.kdbx").getOrThrow()
        assertArrayEquals("GET 下载字节精确", payload, downloaded)

        val meta = provider.getMetadata("vault.kdbx").getOrThrow()
        assertEquals("元数据大小一致", payload.size.toLong(), meta.contentLength)

        // uploadAtomic：真实 PUT .kpktmp -> MOVE 覆盖
        val atomicEtag = provider.uploadAtomic("vault-atomic.kdbx", "atomic-bytes".toByteArray()).getOrThrow()
        assertNotNull("uploadAtomic 返回 ETag", atomicEtag)

        assertTrue("DELETE 清理", provider.delete("vault.kdbx").isSuccess)
        assertTrue("DELETE 清理(atomic)", provider.delete("vault-atomic.kdbx").isSuccess)
    }

    @Test
    fun `LIVE S3 真实 HTTPS 同步全链路与乐观锁`() = runTest {
        assumeLive()
        val client = createTrustingClient()
        val provider = S3SyncProvider(
            endpoint = s3Endpoint,
            bucketName = s3Bucket,
            region = "us-east-1",
            accessKeyId = s3Access,
            secretAccessKey = s3Secret,
            usePathStyle = true,
            client = client
        )

        assertTrue("连接测试(HEAD bucket)", provider.testConnection().isSuccess)

        val payload = "s3-kdbx-v1".toByteArray()
        val etag1 = provider.upload("vault.kdbx", payload).getOrThrow()
        assertNotNull("首传 PUT 返回 ETag", etag1)

        val downloaded = provider.download("vault.kdbx").getOrThrow()
        assertArrayEquals("GET 下载字节精确", payload, downloaded)

        // 覆盖上传（If-Match 乐观锁，服务端原子校验）
        val etag2 = provider.upload("vault.kdbx", "s3-kdbx-v2".toByteArray(), expectedEtag = etag1).getOrThrow()
        assertNotNull("覆盖 PUT 返回 ETag", etag2)

        // 错误期望 ETag -> 412 ConflictError（乐观并发保护）
        val conflict = provider.upload("vault.kdbx", "s3-kdbx-v3".toByteArray(), expectedEtag = "deadbeef")
        assertTrue("期望 412 冲突", conflict.isFailure)
        assertTrue(
            "异常类型应为 ConflictError",
            conflict.exceptionOrNull() is SyncException.ConflictError
        )

        assertTrue("DELETE 清理", provider.delete("vault.kdbx").isSuccess)
    }

    @Test
    fun `LIVE 真实下载与三方合并（WebDAV 下载经 KdbxMerger）`() = runTest {
        assumeLive()
        val client = createTrustingClient()
        val provider = WebDavSyncProvider(
            serverUrl = webdavUrl,
            username = webdavUser,
            passwordChars = webdavPass.toCharArray(),
            client = client
        )

        // 三方库镜像：base 仅 A；local 在 A 不变基础上新增 B；remote 在 A 不变基础上新增 C
        // （模拟两台设备各自新增不同条目的真实并发场景）
        val base = buildDb(mapOf("A" to ("Base Title" to "base.url")))
        val local = buildDb(mapOf("A" to ("Base Title" to "base.url"), "B" to ("Local B" to "b.url")))
        val remote = buildDb(mapOf("A" to ("Base Title" to "base.url"), "C" to ("Remote C" to "c.url")))

        provider.upload("merge_base.bin", dbToBytes(base)).getOrThrow()
        provider.upload("merge_local.bin", dbToBytes(local)).getOrThrow()
        provider.upload("merge_remote.bin", dbToBytes(remote)).getOrThrow()

        // 真实下载（字节精确）
        val dBase = provider.download("merge_base.bin").getOrThrow()
        val dLocal = provider.download("merge_local.bin").getOrThrow()
        val dRemote = provider.download("merge_remote.bin").getOrThrow()
        assertArrayEquals("base 下载字节精确", dbToBytes(base), dBase)

        // 重建模型并运行真实三方合并
        val merged = KdbxMerger.mergeDatabases(
            bytesToDb(dBase), bytesToDb(dLocal), bytesToDb(dRemote)
        )
        val mergedTitles = merged.mergedRoot.allEntries().map { it.title }.toSet()
        assertTrue("A 存活", mergedTitles.contains("Base Title"))
        assertTrue("B 存活（本地下载合并）", mergedTitles.contains("Local B"))
        assertTrue("C 存活（远地下载合并）", mergedTitles.contains("Remote C"))
        assertTrue("并发新增无冲突", merged.conflicts.isEmpty())
        assertEquals("合并后共 3 条目", 3, merged.mergedRoot.allEntries().size)

        provider.delete("merge_base.bin")
        provider.delete("merge_local.bin")
        provider.delete("merge_remote.bin")
    }

    @Test
    fun `LIVE 真实下载 + 冲突检测合并`() = runTest {
        assumeLive()
        val client = createTrustingClient()
        val provider = WebDavSyncProvider(
            serverUrl = webdavUrl,
            username = webdavUser,
            passwordChars = webdavPass.toCharArray(),
            client = client
        )

        val base = buildDb(mapOf("A" to ("Base" to "u")))
        val local = buildDb(mapOf("A" to ("LocalTitle" to "u")))
        val remote = buildDb(mapOf("A" to ("RemoteTitle" to "u")))

        provider.upload("conf_base.bin", dbToBytes(base)).getOrThrow()
        provider.upload("conf_local.bin", dbToBytes(local)).getOrThrow()
        provider.upload("conf_remote.bin", dbToBytes(remote)).getOrThrow()

        val merged = KdbxMerger.mergeDatabases(
            bytesToDb(provider.download("conf_base.bin").getOrThrow()),
            bytesToDb(provider.download("conf_local.bin").getOrThrow()),
            bytesToDb(provider.download("conf_remote.bin").getOrThrow())
        )
        assertTrue("应检测到冲突", merged.conflicts.isNotEmpty())
        assertTrue(
            "冲突涉及双方都修改的条目 A",
            merged.conflicts.any { it.localEntry.title == "LocalTitle" }
        )

        provider.delete("conf_base.bin")
        provider.delete("conf_local.bin")
        provider.delete("conf_remote.bin")
    }

    // ---- 三方库镜像 <-> 字节 的确定性序列化（仅测试用，模拟 kdbx 内容在服务器上的往返） ----

    /** 固定 key→ID 映射：同一逻辑条目在三方库中必须共享同一 UUID（否则合并会误判为冲突） */
    private val KEY_IDS = mapOf("A" to 1L, "B" to 2L, "C" to 3L, "D" to 4L, "E" to 5L)

    private fun buildDb(entries: Map<String, Pair<String, String>>): KdbxDatabaseLite {
        val kEntries = entries.map { (key, value) ->
            val (title, url) = value
            KdbxEntry(
                id = KdbxUuid.fromHexString(
                    String.format("%032X", KEY_IDS[key] ?: (key.hashCode().toLong() and 0xFFFFFFF))
                ),
                fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString(title, false),
                    KdbxConstants.Fields.URL to ProtectedString(url, false)
                )
            )
        }
        return KdbxDatabaseLite(rootGroup = KdbxGroup(name = "Root", entries = kEntries))
    }

    private fun dbToBytes(db: KdbxDatabaseLite): ByteArray {
        val sb = StringBuilder()
        for (e in db.rootGroup.allEntries()) {
            sb.append(e.id.toHexString()).append('|').append(e.title).append('|').append(e.url).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun bytesToDb(bytes: ByteArray): KdbxDatabaseLite {
        val text = bytes.toString(Charsets.UTF_8)
        val entries = text.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('|')
            KdbxEntry(
                id = KdbxUuid.fromHexString(parts[0]),
                fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString(parts[1], false),
                    KdbxConstants.Fields.URL to ProtectedString(parts.getOrNull(2) ?: "", false)
                )
            )
        }
        return KdbxDatabaseLite(rootGroup = KdbxGroup(name = "Root", entries = entries))
    }
}
