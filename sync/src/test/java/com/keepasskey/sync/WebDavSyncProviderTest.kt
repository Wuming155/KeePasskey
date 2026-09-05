package com.keepasskey.sync

import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.webdav.WebDavSyncProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
            passwordChars = "pass123".toCharArray()
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
            passwordChars = "pass123".toCharArray()
        )

        val metaResult = provider.getMetadata("vault_dir")
        assertTrue(metaResult.isSuccess)
        val meta = metaResult.getOrThrow()
        assertEquals("weak-etag-999", meta.etag)
        assertTrue(meta.isDirectory)
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
            passwordChars = "pass123".toCharArray()
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
        // 2. MOVE
        server.enqueue(
            MockResponse()
                .setResponseCode(204)
                .setHeader("ETag", "\"final-etag-2\"")
        )

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray()
        )

        val result = provider.uploadAtomic("vault.kdbx", "binary-data".toByteArray())
        assertTrue(result.isSuccess)
        assertEquals("final-etag-2", result.getOrThrow())

        // 验证请求顺序与头部（临时文件名含随机 UUID 成分，仅断言唯一名模式）
        val req1 = server.takeRequest()
        assertEquals("PUT", req1.method)
        assertTrue(req1.path?.startsWith("/vault.kdbx.") == true)
        assertTrue(req1.path?.endsWith(".kpktmp") == true)
        // MOVE 源（请求 URL）必须是本次 PUT 的同一个唯一临时文件
        val req2 = server.takeRequest()
        assertEquals("MOVE", req2.method)
        assertEquals("T", req2.getHeader("Overwrite"))
        assertEquals(req1.path, req2.path)
        assertTrue(req2.getHeader("Destination")?.endsWith("vault.kdbx") == true)
    }

    @Test
    fun `测试 uploadAtomic 在 MOVE 失败重试后回滚清理临时文件`() = runTest {
        // 1. PUT .kpktmp 成功
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"tmp\""))
        // 2. MOVE 尝试 1 失败
        server.enqueue(MockResponse().setResponseCode(500).setBody("Server error"))
        // 3. MOVE 尝试 2 (重试) 失败
        server.enqueue(MockResponse().setResponseCode(500).setBody("Server error"))
        // 4. DELETE .kpktmp 回滚清理
        server.enqueue(MockResponse().setResponseCode(204))

        val provider = WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "admin",
            passwordChars = "pass123".toCharArray()
        )

        val result = provider.uploadAtomic("vault.kdbx", "binary-data".toByteArray())
        assertTrue(result.isFailure)

        // 验证回滚调用了 DELETE，且清理的是本次 PUT 的同一个唯一临时文件
        val req1 = server.takeRequest() // PUT
        val req2 = server.takeRequest() // MOVE 1
        val req3 = server.takeRequest() // MOVE 2
        val req4 = server.takeRequest() // DELETE
        assertEquals("DELETE", req4.method)
        assertTrue(req1.path?.startsWith("/vault.kdbx.") == true)
        assertTrue(req1.path?.endsWith(".kpktmp") == true)
        assertEquals(req1.path, req4.path)
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
            passwordChars = "pass123".toCharArray()
        )

        val downloadResult = provider.download("我的 密码库/工作 vault.kdbx")
        assertTrue(downloadResult.isSuccess)

        val req = server.takeRequest()
        val path = req.path.orEmpty()
        // 路径应该包含编码后的 UTF-8 与 %20
        assertTrue(path.contains("%E6%88%91%E7%9A%84%20%E5%AF%86%E7%A0%81%E5%BA%93"))
        assertTrue(path.contains("%E5%B7%A5%E4%BD%9C%20vault.kdbx"))
    }
}
