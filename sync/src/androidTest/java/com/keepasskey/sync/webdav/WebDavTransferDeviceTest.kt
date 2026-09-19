package com.keepasskey.sync.webdav

import com.keepasskey.sync.model.SyncException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P2-192 余量第 1 项：`WebDavSyncProvider` 的**设备侧**真实请求-响应用例。
 *
 * ## 为什么宿主 MockWebServer 套件不算数
 *
 * 宿主 JVM 的 OkHttp 走纯 Java socket 实现；真机 / 模拟器上 OkHttp 经
 * **平台 HTTP 栈（Conscrypt TLS Provider + 真实内核 socket）** 收发。PROPFIND / PUT / GET
 * 的报文构造、头解析与状态码分派逻辑相同，但「请求真的从设备 socket 发出、响应真的
 * 被设备端 Conscrypt 栈读回」这一层只在设备侧可证（AGENTS.md §5：平台 API 不可只靠宿主单测）。
 *
 * ## 覆盖与不覆盖（如实声明）
 *
 * - **覆盖**：设备回环上 PROPFIND 207 元数据链路、PUT + If-Match 乐观锁、
 *   GET 下载往返、412 → ConflictError、401 → AuthenticationError 的完整请求-响应链路；
 *   并用 `takeRequest()` 断言真实报文（方法 / 头）确从设备发出。
 * - **不覆盖**：真实 TLS 服务器（自签证书信任链行为由 app 层网络策略用例证明，
 *   见 `CleartextPolicyDeviceTest`）；`uploadAtomic` 的 MOVE 事务与回滚
 *   （宿主 `WebDavSyncScenarioTest` 已有全链路覆盖，设备侧重点在传输面）。
 */
class WebDavTransferDeviceTest {

    private lateinit var server: MockWebServer

    // Wave 12 TLS-only 工厂默认拒绝明文：MockWebServer 为 HTTP 回环，测试显式注入默认规格客户端
    // （OkHttpClient() 默认构造在设备上走 Conscrypt 平台 socket 栈——这正是本用例要证明的面）
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

    private fun newProvider(): WebDavSyncProvider = WebDavSyncProvider(
        serverUrl = server.url("/").toString(),
        username = "device-admin",
        passwordChars = "device-pass".toCharArray(),
        client = plainLoopbackClient
    )

    @Test
    fun `设备回环上 PROPFIND 元数据请求-响应链路`() = runBlocking {
        val propfindXml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/remote.php/webdav/vault.kdbx</d:href>
    <d:propstat>
      <d:prop>
        <d:getetag>"device-etag-1"</d:getetag>
        <d:getcontentlength>20480</d:getcontentlength>
        <d:getlastmodified>Sat, 19 Sep 2026 08:00:00 GMT</d:getlastmodified>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""
        server.enqueue(MockResponse().setResponseCode(207).setBody(propfindXml))

        val meta = newProvider().getMetadata("vault.kdbx")

        assertTrue("PROPFIND 链路应在设备上成功：${meta.exceptionOrNull()}", meta.isSuccess)
        assertEquals("device-etag-1", meta.getOrThrow().etag)
        assertEquals(20480L, meta.getOrThrow().contentLength)

        val sent = server.takeRequest()
        assertEquals("PROPFIND 请求方法必须真实发出", "PROPFIND", sent.method)
        assertEquals("Depth 头必须随真实报文发出", "0", sent.getHeader("Depth"))
        assertTrue(
            "Basic 认证头必须随真实报文发出",
            sent.getHeader("Authorization")?.startsWith("Basic ") == true
        )
    }

    @Test
    fun `设备回环上 PUT 上传带 If-Match 乐观锁并回读 ETag`() = runBlocking {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        server.enqueue(
            MockResponse().setResponseCode(204).setHeader("ETag", "\"device-etag-2\"")
        )

        val etag = newProvider().upload("vault.kdbx", payload, expectedEtag = "device-etag-1")

        assertTrue("PUT 上传应在设备上成功：${etag.exceptionOrNull()}", etag.isSuccess)
        assertEquals("device-etag-2", etag.getOrThrow())

        val sent = server.takeRequest()
        assertEquals("PUT 请求方法必须真实发出", "PUT", sent.method)
        assertEquals(
            "If-Match 乐观锁头必须随真实报文发出",
            "\"device-etag-1\"",
            sent.getHeader("If-Match")
        )
        assertEquals(
            "上传体必须经设备 socket 完整到达服务端",
            payload.toList(),
            sent.body.readByteArray().toList()
        )
    }

    @Test
    fun `设备回环上 GET 下载往返字节一致`() = runBlocking {
        val payload = ByteArray(8192) { (it * 7 % 256).toByte() }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Length", payload.size.toString())
                .setBody(okio.Buffer().write(payload))
        )

        // ISSUE-P3-206 流式契约：内容边读边写进内存 sink 后断言（下载期不整份物化）
        val sink = ByteArrayOutputStream()
        val downloaded = newProvider().download("vault.kdbx", sink)

        assertTrue("GET 下载应在设备上成功：${downloaded.exceptionOrNull()}", downloaded.isSuccess)
        assertEquals("下载体必须与 mock 远端逐字节一致", payload.toList(), sink.toByteArray().toList())

        val sent = server.takeRequest()
        assertEquals("GET 请求方法必须真实发出", "GET", sent.method)
    }

    @Test
    fun `设备回环上 412 冲突映射为 ConflictError`() = runBlocking {
        // PUT 回 412 后，实现会立即 PROPFIND 探测当前远端 ETag 用于冲突报告
        server.enqueue(MockResponse().setResponseCode(412))
        val propfindXml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/vault.kdbx</d:href>
    <d:propstat>
      <d:prop><d:getetag>"remote-newer-etag"</d:getetag></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""
        server.enqueue(MockResponse().setResponseCode(207).setBody(propfindXml))

        val result = newProvider().upload("vault.kdbx", ByteArray(16), expectedEtag = "stale-etag")

        assertTrue("412 应为失败结果", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "412 必须映射为 ConflictError，实际 ${error?.javaClass?.simpleName}",
            error is SyncException.ConflictError
        )
        assertEquals("remote-newer-etag", (error as SyncException.ConflictError).remoteEtag)
    }

    @Test
    fun `设备回环上 401 映射为 AuthenticationError`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = newProvider().testConnection()

        assertTrue("401 应为失败结果", result.isFailure)
        assertTrue(
            "401 必须映射为 AuthenticationError，实际 ${result.exceptionOrNull()?.javaClass?.simpleName}",
            result.exceptionOrNull() is SyncException.AuthenticationError
        )
        assertEquals("PROPFIND", server.takeRequest().method)
    }
}
