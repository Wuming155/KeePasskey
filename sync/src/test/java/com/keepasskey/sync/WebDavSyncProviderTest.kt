package com.keepasskey.sync

import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.webdav.WebDavSyncProvider
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * WebDavSyncProvider 单元测试：
 * 覆盖 PROPFIND 解析、If-Match 乐观锁并发检测 (412)、GET 下载与 DELETE、
 * 命名空间兼容性、uploadAtomic 事务写与回滚、路径 URL 编码。
 */
class WebDavSyncProviderTest {

    private lateinit var server: MockWebServer

    // Wave 12 TLS-only 工厂默认拒绝明文：MockWebServer 为 HTTP 回环，测试显式注入默认规格客户端
    private val plainLoopbackClient = OkHttpClient()

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `取消 WebDAV 请求会中止生产 Provider 的活动 HTTP Call`() = runTest {
        server.enqueue(MockResponse().setHeadersDelay(10, TimeUnit.SECONDS))
        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "test-user",
            passwordChars = "test-password".toCharArray(),
            client = plainLoopbackClient
        )

        val requestJob = launch { provider.testConnection() }
        runCurrent()
        assertNotNull("PROPFIND 应已到达服务端", server.takeRequest(2, TimeUnit.SECONDS))
        requestJob.cancelAndJoin()

        assertTrue("Provider 调用必须以协程取消结束", requestJob.isCancelled)
    }

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
        // ISSUE-P1-275 AC②：弱标记保留（旧断言预期剥掉 W/，与读写一致化口径相悖）
        assertEquals("W/weak-etag-999", meta.etag)
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

        // 验证请求顺序与头部（临时文件名含随机 UUID 成分，仅断言唯一名模式）；
        // ISSUE-P2-382：末段扩展名必须保留 .kdbx（后缀 .kpktmp 插在原扩展名之前）
        val req1 = server.takeRequest()
        assertEquals("PUT", req1.method)
        assertTrue(req1.path?.startsWith("/vault.") == true)
        assertTrue(req1.path?.endsWith("${WebDavSyncProvider.ATOMIC_TMP_SUFFIX}.kdbx") == true)
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
        server.takeRequest()            // MOVE 2
        val req5 = server.takeRequest() // DELETE
        assertEquals("DELETE", req5.method)
        assertTrue(req1.path?.startsWith("/vault.") == true)
        assertTrue(req1.path?.endsWith("${WebDavSyncProvider.ATOMIC_TMP_SUFFIX}.kdbx") == true)
        assertEquals(req1.path, req3.path)
        assertEquals(req1.path, req5.path)
    }

    @Test
    fun `测试事务上传临时名保留原扩展名(ISSUE-P2-382防回潮)`() {
        // AC② 命名形态锁定：固定后缀 .kpktmp 必须插在**原扩展名之前**，
        // 末段扩展名与目标一致——按扩展名限制上传的服务器会拒收末段被替换成 .kpktmp 的旧形态
        // （keepass2android b1ae0482 同型修复）。防回潮：任何改回「$remotePath.$uuid.kpktmp」
        // 尾部追加形态的实现都会在此变红。
        val suffix = WebDavSyncProvider.ATOMIC_TMP_SUFFIX
        assertEquals("vault.token-1$suffix.kdbx", WebDavSyncProvider.atomicTmpPath("vault.kdbx", "token-1"))
        assertEquals(
            "多重点号文件名只取末段为扩展名",
            "my.vault.token-1$suffix.kdbx",
            WebDavSyncProvider.atomicTmpPath("my.vault.kdbx", "token-1")
        )
        assertEquals(
            "深层路径段保持不变形",
            "a/b/c/d/vault.token-1$suffix.kdbx",
            WebDavSyncProvider.atomicTmpPath("a/b/c/d/vault.kdbx", "token-1")
        )
        // 无扩展名路径：保持尾部追加旧行为
        assertEquals("vault.token-1$suffix", WebDavSyncProvider.atomicTmpPath("vault", "token-1"))
        // 末段以「.」开头且无主干（点文件名）：按无扩展名处理，主干不得被切空
        assertEquals("a/b/.vault.token-1$suffix", WebDavSyncProvider.atomicTmpPath("a/b/.vault", "token-1"))
        // 目录段含「.」不影响末段扩展名判定
        assertEquals(
            "dir.v2/vault.token-1$suffix.kdbx",
            WebDavSyncProvider.atomicTmpPath("dir.v2/vault.kdbx", "token-1")
        )
        // 随机 token 成分保证临时名唯一（并发事务互不覆盖）
        val a = WebDavSyncProvider.atomicTmpPath("vault.kdbx", "token-A")
        val b = WebDavSyncProvider.atomicTmpPath("vault.kdbx", "token-B")
        assertTrue("不同 token 必须产生不同临时名", a != b)
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

        val downloaded = ByteArrayOutputStream()
        val downloadResult = provider.download("我的 密码库/工作 vault.kdbx", downloaded)
        assertTrue(downloadResult.isSuccess)
        assertEquals("file content", downloaded.toString(Charsets.UTF_8.name()))

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

    // ===== ISSUE-P1-05（ZT-05）/ ISSUE-P2-425：SSRF 端点防线（构造期放宽后口径） =====

    @Test
    fun `生产路径云元数据端点在构造期仍被拒（红线）`() {
        listOf(
            "https://169.254.169.254/remote.php/webdav",
            "https://[::ffff:169.254.169.254]/"
        ).forEach { url ->
            val ex = runCatching {
                WebDavSyncProvider(
                    serverUrl = url,
                    username = "admin",
                    passwordChars = "pass123".toCharArray()
                )
            }.exceptionOrNull()
            assertTrue("云元数据端点 \"$url\" 必须被拒（AC④ 红线）", ex is SyncException.InvalidEndpointError)
        }
    }

    @Test
    fun `生产路径自建与内网 https 端点构造通过（ISSUE-P2-425 放宽）`() {
        listOf(
            "https://192.168.1.10/dav",
            "https://127.0.0.1/dav",
            "https://localhost/dav",
            "https://nas.local/dav"
        ).forEach { url ->
            val ex = runCatching {
                WebDavSyncProvider(
                    serverUrl = url,
                    username = "admin",
                    passwordChars = "pass123".toCharArray()
                )
            }.exceptionOrNull()
            assertTrue("自建/内网 https 端点 \"$url\" 构造不应被拒: $ex", ex == null)
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

    // ===== ISSUE-P3-298 ③：请求级瞬时错误重试 =====
    // 重试参数经 SyncNetworkOptions 注入极小退避（withContext(Dispatchers.IO) 是真实时间，
    // 虚拟时钟管不到），避免用例真实等待。

    /** 瞬时重试用极小退避配置（3 次尝试、1ms 基准） */
    private val retryFastOptions = SyncNetworkOptions(
        transientRetryAttempts = 3,
        transientRetryBaseDelayMs = 1L
    )

    @Test
    fun `读请求遇瞬时 5xx 自动重试至成功`() = runTest {
        val propfindXml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop><d:getetag>"etag-ok"</d:getetag></d:prop></d:propstat></d:response></d:multistatus>"""
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(207).setBody(propfindXml))

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray(),
            networkOptions = retryFastOptions,
            client = plainLoopbackClient
        )

        val meta = provider.getMetadata("test.kdbx")
        assertTrue(meta.isSuccess)
        assertEquals("etag-ok", meta.getOrThrow().etag)
        assertEquals("瞬时 5xx 后应共发出 3 次请求", 3, server.requestCount)
    }

    @Test
    fun `无条件 PUT（无期望 ETag）遇 5xx 不重试`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray(),
            networkOptions = retryFastOptions,
            client = plainLoopbackClient
        )

        val result = provider.upload("test.kdbx", "data".toByteArray(), expectedEtag = null)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncException.ProtocolError)
        assertEquals("无条件 PUT 禁止重试（AC③），应只有 1 次请求", 1, server.requestCount)
    }

    @Test
    fun `带 If-Match 预条件的 PUT 遇 5xx 自动重试且重试仍携带预条件`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"new-etag\"")
        )

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray(),
            networkOptions = retryFastOptions,
            client = plainLoopbackClient
        )

        val result = provider.upload("test.kdbx", "data".toByteArray(), expectedEtag = "etag-1")
        assertTrue(result.isSuccess)
        assertEquals("new-etag", result.getOrThrow())
        assertEquals("条件 PUT 应重试，共 2 次请求", 2, server.requestCount)
        // 两次尝试都必须携带同一 If-Match 预条件：重试基线由服务端逐次重验（AC③）
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("\"etag-1\"", first.getHeader("If-Match"))
        assertEquals("\"etag-1\"", second.getHeader("If-Match"))
    }

    @Test
    fun `412 冲突属确定性结论不重试`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(412)
                .setBody("Precondition Failed")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("ETag", "\"remote-latest\"")
                .setBody(
                    """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop><d:getetag>"remote-latest"</d:getetag></d:prop></d:propstat></d:response></d:multistatus>"""
                )
        )

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray(),
            networkOptions = retryFastOptions,
            client = plainLoopbackClient
        )

        val result = provider.upload("test.kdbx", "data".toByteArray(), expectedEtag = "stale")
        assertTrue(result.exceptionOrNull() is SyncException.ConflictError)
        assertEquals("412 不在瞬时重试面，PUT 仅 1 次（随后 1 次 PROPFIND 取最新元数据）", 2, server.requestCount)
    }

    // ===== ENG-20261028-32：PROPFIND 解析失败不得伪装成成功元数据 =====

    /** 复用的最小 provider（回环明文客户端；仅本段用例使用）。 */
    private fun parseProbeProvider(): WebDavSyncProvider = WebDavSyncProvider(
        serverUrl = server.url("/").toString(),
        username = "admin",
        passwordChars = "pass123".toCharArray(),
        client = plainLoopbackClient
    )

    @Test
    fun `PROPFIND 报文不可解析时元数据读取如实失败而非折成空元数据`() = runTest {
        // 即便响应带可信 HTTP ETag 头，被截断的报文也不得折成「成功 + 空元数据」——
        // 折空会让上层把「远端元数据不可知」当成无 ETag 服务器，剥掉覆盖上传的乐观锁
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("ETag", "\"header-etag\"")
                .setBody("""<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"><D:response>""")
        )
        // 被网关替换成 HTML 错误页（根元素不是 multistatus）同样必须失败
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""<html><body><h1>502 Bad Gateway</h1></body></html>""")
        )

        val provider = parseProbeProvider()

        val truncated = provider.getMetadata("test.kdbx")
        assertTrue("截断报文必须如实失败: ${truncated.exceptionOrNull()}", truncated.isFailure)
        assertTrue("必须是可处理的协议错误: ${truncated.exceptionOrNull()}", truncated.exceptionOrNull() is SyncException.ProtocolError)

        val errorPage = provider.getMetadata("test.kdbx")
        assertTrue("错误页必须如实失败: ${errorPage.exceptionOrNull()}", errorPage.isFailure)
        assertTrue("必须是可处理的协议错误: ${errorPage.exceptionOrNull()}", errorPage.exceptionOrNull() is SyncException.ProtocolError)
    }

    @Test
    fun `PROPFIND 报文合法但缺 getetag 时才回退可信 HTTP 头`() = runTest {
        // 「属性缺失」与「解析失败」是两条通道：合法 multistatus 缺 getetag 才允许回退响应头 ETag
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("ETag", "\"header-etag\"")
                .setBody(
                    """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop><D:getcontentlength>7</D:getcontentlength></D:prop></D:propstat></D:response></D:multistatus>"""
                )
        )
        // 无 ETag 服务器的口径保留：合法报文 + 无 ETag 属性 + 无响应头 ⇒ 成功且 etag 为空串
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:"/>""")
        )

        val provider = parseProbeProvider()

        val withHeader = provider.getMetadata("test.kdbx")
        assertTrue("缺属性但头可信时必须成功: ${withHeader.exceptionOrNull()}", withHeader.isSuccess)
        assertEquals("header-etag", withHeader.getOrThrow().etag)
        assertEquals("getetag 缺失时其余属性仍如实采信", 7L, withHeader.getOrThrow().contentLength)

        val noHeader = provider.getMetadata("test.kdbx")
        assertTrue("无 ETag 服务器必须仍成功: ${noHeader.exceptionOrNull()}", noHeader.isSuccess)
        assertEquals("无 ETag 服务器须返回空 etag", "", noHeader.getOrThrow().etag)
    }

    @Test
    fun `存在性探测报文不可解析时不得静默转为无条件覆盖`() = runTest {
        // 无预期 ETag 时先探目标存在性；探测报文不可解析 ⇒ 按「目标存在」的保守对侧处理为
        // `Overwrite: F`（原子创建保护）。目标真实存在时 MOVE 412，412 后的重探若仍不可解析
        // ⇒ fail-closed 上抛探测失败，绝不发 `Overwrite: T` 的无条件覆盖
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"tmp-1\"")) // PUT 临时文件
        server.enqueue(MockResponse().setResponseCode(207).setBody("<not xml"))            // 存在性探测：不可解析
        server.enqueue(MockResponse().setResponseCode(412))                                 // MOVE：目标存在 ⇒ 412
        server.enqueue(MockResponse().setResponseCode(207).setBody("<not xml"))            // 412 后重探：仍不可解析
        server.enqueue(MockResponse().setResponseCode(204))                                 // DELETE 临时文件

        val provider = parseProbeProvider()
        val result = provider.uploadAtomic("vault.kdbx", "local-bytes".toByteArray())

        assertTrue("不可解析的探测必须 fail-closed: ${result.exceptionOrNull()}", result.isFailure)
        val err = result.exceptionOrNull()
        assertFalse("探测失败不得折成 ConflictError: $err", err is SyncException.ConflictError)
        assertTrue("必须如实上抛探测自身失败: $err", err is SyncException.ProtocolError)

        val recorded = mutableListOf<okhttp3.mockwebserver.RecordedRequest>()
        repeat(server.requestCount) { recorded += server.takeRequest() }
        assertEquals(
            "请求序应为 PUT → PROPFIND(探测) → MOVE(412) → PROPFIND(重探) → DELETE(临时文件)",
            listOf("PUT", "PROPFIND", "MOVE", "PROPFIND", "DELETE"),
            recorded.map { it.method }
        )
        val move = recorded.first { it.method == "MOVE" }
        assertEquals("探测失败时必须以原子创建保护覆盖（不得无条件覆盖）", "F", move.getHeader("Overwrite"))
        assertNull("无预期 ETag 时不得发送 If 预条件", move.getHeader("If"))
        val delete = recorded.first { it.method == "DELETE" }
        assertTrue("只清理本次临时文件", delete.path.orEmpty().contains(".kpktmp"))
    }

    // ---------------------------------------------------------------- ENG-20261028-42 凭据生命周期

    @Test
    fun `认证头按请求现算且失效后拒绝继续发请求`() = runTest {
        // 每个请求都必须重新构造认证头（构造期缓存已被移除）：两次请求各自成功，
        // 且都携带完整凭据——证明口令副本仍在、未被「用过一次就擦」。
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>"))
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>"))

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "fake-user",
            passwordChars = "fake-pass-密".toCharArray(),
            client = plainLoopbackClient
        )

        assertTrue(provider.testConnection().isSuccess)
        assertTrue(provider.testConnection().isSuccess)

        repeat(2) { index ->
            val request = server.takeRequest()
            assertEquals(
                "第 ${index + 1} 次请求的认证头必须由现算产生",
                "fake-user:fake-pass-密",
                String(
                    java.util.Base64.getDecoder().decode(request.getHeader("Authorization")!!.removePrefix("Basic ")),
                    Charsets.UTF_8
                )
            )
        }

        provider.clearCredentials()

        // 失效后所有请求面都以鉴权失败结束，且不发出任何网络请求（fail-closed，不以空口令降级）
        val before = server.requestCount
        val meta = provider.getMetadata("vault.kdbx")
        assertTrue("失效后元数据读取必须失败: $meta", meta.isFailure)
        assertTrue(
            "必须以 AuthenticationError 失败: ${meta.exceptionOrNull()}",
            meta.exceptionOrNull() is SyncException.AuthenticationError
        )
        val upload = provider.uploadAtomic("vault.kdbx", "bytes".toByteArray())
        assertTrue("失效后原子上传必须返回 Result.failure 而非抛异常: $upload", upload.isFailure)
        assertTrue(
            "原子上传同样以 AuthenticationError 失败: ${upload.exceptionOrNull()}",
            upload.exceptionOrNull() is SyncException.AuthenticationError
        )
        val list = provider.listRemoteDirectory("", cursor = null, pageSize = 10)
        assertTrue("失效后目录列举必须返回 Result.failure: $list", list.isFailure)
        assertTrue(
            "目录列举同样以 AuthenticationError 失败: ${list.exceptionOrNull()}",
            list.exceptionOrNull() is SyncException.AuthenticationError
        )
        assertEquals("失效后不得再发出任何请求", before, server.requestCount)

        // 幂等：重复失效不放大失败面
        provider.clearCredentials()
        assertTrue(provider.testConnection().isFailure)
    }

    @Test
    fun `构造期擦除借用口令数组后仍能认证`() = runTest {
        // 既有所有权契约（CloudVaultImporter / RemoteBrowseController 依赖）：入参数组在构造返回后
        // 立即被调用方清零，Provider 必须凭自己的独占副本继续认证。
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>"))

        val borrowed = "fake-pass-借用".toCharArray()
        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "fake-user",
            passwordChars = borrowed,
            client = plainLoopbackClient
        )
        assertTrue("Provider 构造后必须立即擦除借用数组", borrowed.all { it == '0' })

        assertTrue(provider.testConnection().isSuccess)
        val request = server.takeRequest()
        assertEquals(
            "fake-user:fake-pass-借用",
            String(
                java.util.Base64.getDecoder().decode(request.getHeader("Authorization")!!.removePrefix("Basic ")),
                Charsets.UTF_8
            )
        )
    }
}
