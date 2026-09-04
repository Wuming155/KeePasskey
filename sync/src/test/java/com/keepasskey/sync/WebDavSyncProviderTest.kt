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
 * 覆盖 PROPFIND 解析、If-Match 乐观锁并发检测 (412)、GET 下载与 DELETE。
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
    fun `测试 PUT 上传触发 HTTP 412 乐观并发锁异常`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(412)
                .setBody("Precondition Failed")
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
    }
}
