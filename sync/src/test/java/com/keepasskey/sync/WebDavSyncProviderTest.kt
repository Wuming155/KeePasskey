package com.keepasskey.sync

import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.webdav.WebDavSyncProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WebDavSyncProvider 单元测试：
 * 覆盖 PROPFIND 解析、If-Match 乐观锁并发检测 (412)、GET 下载与 DELETE、
 * 命名空间兼容性、uploadAtomic 事务写与回滚、路径 URL 编码。
 */
class WebDavSyncProviderTest {

    private lateinit var server: MockWebServer

    // Wave 12 TLS-only 工厂默认拒绝明文：MockWebServer 为 HTTP 回环，测试显式注入默认规格客户端
    private val plainLoopbackClient = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `测试 PROPFIND 解析远程元数据与 ETag`() = runTest {
        val propfindXml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/remote.php/webdav/test.kdbx</d:href>
    <d:propstat>
      <d:prop>
        <d:getetag>"etag_val_12345"</d:getetag>
        <d:getcontentlength>10240</d:getcontentlength>
        <d:getlastmodified>Wed, 04 Sep 2026 12:00:00 GMT</d:getlastmodified>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""

        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(propfindXml)
        )

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray(),
            client = plainLoopbackClient
        )

        val metaResult = provider.getMetadata("test.kdbx")
        assertTrue(metaResult.isSuccess)
        val meta = metaResult.getOrThrow()
        assertEquals("etag_val_12345", meta.etag)
        assertEquals(10240L, meta.contentLength)
    }

    @Test
    fun `测试多命名空间前缀与无前缀 PROPFIND 解析`() = runTest {
        // 大写 D 前缀与目录类型
        val propfindXmlD = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/files/vault_dir/</D:href>
    <D:propstat>
      <D:prop>
        <D:getetag>W/"weak-etag-999"</D:getetag>
        <D:getcontentlength>0</D:getcontentlength>
        <D:getlastmodified>Fri, 06 Sep 2026 10:00:00 GMT</D:getlastmodified>
        <D:resourcetype><D:collection/></D:resourcetype>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""

        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(propfindXmlD)
        )

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray(),
            client = plainLoopbackClient
        )

        val metaResult = provider.getMetadata("vault_dir")
        assertTrue(metaResult.isSuccess)
        val meta = metaResult.getOrThrow()
        assertEquals("weak-etag-999", meta.etag)
        assertTrue(meta.isDirectory)
    }

    @Test
    fun `测试中文密码 Basic 认证头按UTF8编码`() = runTest {
        // TASK-25 回归锁：非 ASCII（中文）密码此前按 ISO-8859-1 编码被错误转码，
        // 主流 WebDAV 服务端（按 UTF-8 解码凭据）必然 401
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>"))

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "用户",
            passwordChars = "密码123".toCharArray(),
            client = plainLoopbackClient
        )

        assertTrue(provider.testConnection().isSuccess)

        val request = server.takeRequest()
        val authHeader = request.getHeader("Authorization").orEmpty()
        assertTrue(authHeader.startsWith("Basic "))

        val decoded = String(
            java.util.Base64.getDecoder().decode(authHeader.removePrefix("Basic ")),
            Charsets.UTF_8
        )
        assertEquals("用户:密码123", decoded)
    }

    @Test
    fun `测试 PUT 上传触发 HTTP 412 乐观并发锁异常`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(412)
                .setBody("Precondition Failed")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("ETag", "\"remote-latest-etag\"")
                .setBody(
                    """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop><d:getetag>"remote-latest-etag"</d:getetag></d:prop></d:propstat></d:response></d:multistatus>"""
                )
        )

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray(),
            client = plainLoopbackClient
        )

        val uploadResult = provider.upload("test.kdbx", "test-bytes".toByteArray(), expectedEtag = "outdated_etag")
        assertTrue(uploadResult.isFailure)
        val exception = uploadResult.exceptionOrNull()
        assertTrue(exception is SyncException.ConflictError)
        val conflict = exception as SyncException.ConflictError
        assertEquals("remote-latest-etag", conflict.remoteEtag)
        assertEquals("outdated_etag", conflict.localExpectedEtag)
    }

    @Test
    fun `测试 uploadAtomic 事务写成功 (PUT tmp 到 MOVE 覆盖)`() = runTest {
        // 1. PUT .kpktmp
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"tmp-etag-1\"")
        )
        // 2. PROPFIND 目标存在性探测（F3 修复：expectedEtag 为空时区分真首传与无 ETag 覆盖）
        //    404 = 目标不存在 = 真首传语义
        server.enqueue(MockResponse().setResponseCode(404))
        // 3. MOVE
        server.enqueue(
            MockResponse()
                .setResponseCode(204)
                .setHeader("ETag", "\"final-etag-2\"")
        )

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray(),
            client = plainLoopbackClient
        )

        val result = provider.uploadAtomic("vault.kdbx", "binary-data".toByteArray())
        assertTrue(result.isSuccess)
        assertEquals("final-etag-2", result.getOrThrow())

        // 验证请求顺序与头部（临时文件名含随机 UUID 成分，仅断言唯一名模式）
        val req1 = server.takeRequest()
        assertEquals("PUT", req1.method)
        assertTrue(req1.path?.startsWith("/vault.kdbx.") == true)
        assertTrue(req1.path?.endsWith(".kpktmp") == true)
        // F3 修复：无 ETag 上传前先探测目标存在性
        val reqProbe = server.takeRequest()
        assertEquals("PROPFIND", reqProbe.method)
        // MOVE 源（请求 URL）必须是本次 PUT 的同一个唯一临时文件
        val req2 = server.takeRequest()
        assertEquals("MOVE", req2.method)
        // 目标不存在（404）：保持 P1-11 首传语义，Overwrite 必须为 F
        assertEquals("F", req2.getHeader("Overwrite"))
        assertEquals(req1.path, req2.path)
        assertTrue(req2.getHeader("Destination")?.endsWith("vault.kdbx") == true)
    }

    @Test
    fun `测试 uploadAtomic 无ETag且目标已存在时 Overwrite T 覆盖语义`() = runTest {
        // F3 修复：无 ETag 服务器的覆盖上传（本地赢/冲突解决路径，目标必然已存在）
        // 必须允许 Overwrite: T——恒用 F 会让 MOVE 对已存在目标恒定 412，上传路径死锁
        // 1. PUT .kpktmp
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"tmp-etag-1\""))
        // 2. PROPFIND 探测：目标存在（无 ETag 服务器返回 207 但不带 ETag 头）
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:"/>""")
        )
        // 3. MOVE
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"final-etag-2\""))

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray(),
            client = plainLoopbackClient
        )

        val result = provider.uploadAtomic("vault.kdbx", "binary-data".toByteArray())
        assertTrue("无 ETag 目标已存在时必须以 Overwrite T 完成覆盖: ${result.exceptionOrNull()}", result.isSuccess)

        server.takeRequest() // PUT
        val probeReq = server.takeRequest()
        assertEquals("PROPFIND", probeReq.method)
        val moveReq = server.takeRequest()
        assertEquals("MOVE", moveReq.method)
        assertEquals("T", moveReq.getHeader("Overwrite"))
        assertTrue(moveReq.getHeader("Destination")?.endsWith("vault.kdbx") == true)
    }

    @Test
    fun `测试 uploadAtomic 在 MOVE 失败重试后回滚清理临时文件`() = runTest {
        // 1. PUT .kpktmp 成功
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"tmp\""))
        // 2. PROPFIND 目标存在性探测（F3 修复引入，探测失败按首传 Overwrite: F 处理）
        server.enqueue(MockResponse().setResponseCode(404))
        // 3. MOVE 尝试 1 失败
        server.enqueue(MockResponse().setResponseCode(500).setBody("Server error"))
        // 4. MOVE 尝试 2 (重试) 失败
        server.enqueue(MockResponse().setResponseCode(500).setBody("Server error"))
        // 5. DELETE .kpktmp 回滚清理
        server.enqueue(MockResponse().setResponseCode(204))

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray(),
            client = plainLoopbackClient
        )

        val result = provider.uploadAtomic("vault.kdbx", "binary-data".toByteArray())
        assertTrue(result.isFailure)

        // 验证回滚调用了 DELETE，且清理的是本次 PUT 的同一个唯一临时文件
        val req1 = server.takeRequest() // PUT
        server.takeRequest()            // PROPFIND 探测
        val req3 = server.takeRequest() // MOVE 1
        val req4 = server.takeRequest() // MOVE 2
        val req5 = server.takeRequest() // DELETE
        assertEquals("DELETE", req5.method)
        assertTrue(req1.path?.startsWith("/vault.kdbx.") == true)
        assertTrue(req1.path?.endsWith(".kpktmp") == true)
        assertEquals(req1.path, req3.path)
        assertEquals(req1.path, req5.path)
    }

    @Test
    fun `测试路径 URL 编码（含中文与空格）`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("file content")
        )

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray(),
            client = plainLoopbackClient
        )

        val downloadResult = provider.download("我的 密码库/工作 vault.kdbx")
        assertTrue(downloadResult.isSuccess)

        val req = server.takeRequest()
        val path = req.path.orEmpty()
        // 路径应该包含编码后的 UTF-8 与 %20
        assertTrue(path.contains("%E6%88%91%E7%9A%84%20%E5%AF%86%E7%A0%81%E5%BA%93"))
        assertTrue(path.contains("%E5%B7%A5%E4%BD%9C%20vault.kdbx"))
    }

    @Test
    fun `生产路径显式 http 端点在构造期被拒绝`() {
        // Wave 14 全站强制 HTTPS：生产路径（未注入测试客户端）构造期即 fail-fast，
        // 抛类型化 InvalidEndpointError 并给出用户可理解提示
        val exception = runCatching {
            WebDavSyncProvider(
                serverUrl = "http://dav.example.com/remote.php/webdav",
                username = "admin",
                passwordChars = "pass123".toCharArray()
            )
        }.exceptionOrNull()

        assertTrue(exception is SyncException.InvalidEndpointError)
    }

    @Test
    fun `生产路径 https 与无 scheme 端点构造通过`() {
        // https 显式 scheme 直接通过构造（TLS-only 工厂客户端，不发起网络请求）
        val httpsProvider = WebDavSyncProvider(
            serverUrl = "https://dav.example.com/remote.php/webdav",
            username = "admin",
            passwordChars = "pass123".toCharArray()
        )
        assertNotNull(httpsProvider)

        // 无 scheme 输入按 https 语义处理（上层归一化补 https://），构造不拒绝
        val noSchemeProvider = WebDavSyncProvider(
            serverUrl = "dav.example.com/remote.php/webdav",
            username = "admin",
            passwordChars = "pass123".toCharArray()
        )
        assertNotNull(noSchemeProvider)
    }

    // ===== ISSUE-P1-05（ZT-05）：SSRF 端点内网/注入防线 =====

    @Test
    fun `生产路径内网与云元数据 IP 端点在构造期被拒`() {
        listOf(
            "https://169.254.169.254/remote.php/webdav",
            "https://192.168.1.10/dav",
            "https://127.0.0.1/dav",
            "https://localhost/dav"
        ).forEach { url ->
            val ex = runCatching {
                WebDavSyncProvider(
                    serverUrl = url,
                    username = "admin",
                    passwordChars = "pass123".toCharArray()
                )
            }.exceptionOrNull()
            assertTrue("内网/元数据端点 \"$url\" 必须被拒（SSRF）", ex is SyncException.InvalidEndpointError)
        }
    }

    @Test
    fun `生产路径 userinfo 注入端点在构造期被拒`() {
        val ex = runCatching {
            WebDavSyncProvider(
                serverUrl = "https://good.com@evil.com/dav",
                username = "admin",
                passwordChars = "pass123".toCharArray()
            )
        }.exceptionOrNull()
        assertTrue("userinfo（@）注入端点必须被拒", ex is SyncException.InvalidEndpointError)
    }
}
