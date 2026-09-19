package com.keepasskey.sync.s3

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
 * ISSUE-P2-192 余量第 1 项：`S3SyncProvider` 的**设备侧**真实请求-响应用例
 * （与 [WebDavTransferDeviceTest] 对称，覆盖 S3 一侧的传输面）。
 *
 * ## 覆盖与不覆盖（如实声明）
 *
 * - **覆盖**：设备回环真实 socket 上「HEAD 预检 → 条件 PUT」首传链路
 *   （含 `If-None-Match: *` 原子创建头与 SigV4 `Authorization` 头的真实出栈）、
 *   GET 下载往返、HEAD 预检发现 ETag 漂移 → ConflictError。
 * - **不覆盖**：SigV4 签名值正确性（宿主 `S3SyncProviderTest` 已有固定时间点已知答案向量，
 *   设备侧不重做签名算术）；时钟偏斜自愈重试（依赖服务端 `Date` 头伪造，宿主场景套件覆盖）。
 */
class S3TransferDeviceTest {

    private lateinit var server: MockWebServer

    // Wave 12 TLS-only 工厂默认拒绝明文：MockWebServer 为 HTTP 回环，测试显式注入默认规格客户端
    // （设备上 OkHttpClient() 默认走 Conscrypt 平台 socket 栈——本用例要证明的面）
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

    private fun newProvider(): S3SyncProvider = S3SyncProvider(
        endpoint = "http://127.0.0.1:${server.port}",
        bucketName = "device-vault",
        region = "us-east-1",
        accessKeyId = "DEVICEACCESSKEY".toCharArray(),
        secretAccessKey = "devicesecretkey".toCharArray(),
        // path 风格寻址：回环端点上无需 virtual-host DNS 改写（宿主场景套件同款选择）
        usePathStyle = true,
        client = plainLoopbackClient
    )

    @Test
    fun `设备回环上首传链路 HEAD 预检加条件 PUT`() = runBlocking {
        val payload = ByteArray(2048) { (it % 253).toByte() }
        // 1) HEAD 预检 → 404（确认首传）；2) 条件 PUT → 200 + 新 ETag
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"device-s3-etag\""))

        val etag = newProvider().upload("vault.kdbx", payload, expectedEtag = null)

        assertTrue("S3 首传应在设备上成功：${etag.exceptionOrNull()}", etag.isSuccess)
        assertEquals("device-s3-etag", etag.getOrThrow())

        val head = server.takeRequest()
        assertEquals("HEAD 预检必须真实发出", "HEAD", head.method)
        assertEquals(
            "HEAD 路径必须是 path 风格的 /bucket/key",
            "/device-vault/vault.kdbx",
            head.path
        )
        assertTrue(
            "SigV4 Authorization 头必须随真实报文发出",
            head.getHeader("Authorization")?.startsWith("AWS4-HMAC-SHA256 ") == true
        )
        assertTrue(
            "x-amz-content-sha256 头必须随真实报文发出",
            head.getHeader("x-amz-content-sha256")?.contains('a') == true
        )

        val put = server.takeRequest()
        assertEquals("PUT 必须真实发出", "PUT", put.method)
        assertEquals(
            "首传必须携带 If-None-Match: * 原子创建头",
            "*",
            put.getHeader("If-None-Match")
        )
        assertEquals(
            "上传体必须经设备 socket 完整到达服务端",
            payload.toList(),
            put.body.readByteArray().toList()
        )
    }

    @Test
    fun `设备回环上 GET 下载往返字节一致`() = runBlocking {
        val payload = ByteArray(6144) { (it * 13 % 256).toByte() }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Length", payload.size.toString())
                .setBody(okio.Buffer().write(payload))
        )

        // ISSUE-P3-206 流式契约：内容边读边写进内存 sink 后断言（下载期不整份物化）
        val sink = ByteArrayOutputStream()
        val downloaded = newProvider().download("vault.kdbx", sink)

        assertTrue("S3 下载应在设备上成功：${downloaded.exceptionOrNull()}", downloaded.isSuccess)
        assertEquals("下载体必须与 mock 远端逐字节一致", payload.toList(), sink.toByteArray().toList())

        val sent = server.takeRequest()
        assertEquals("GET 请求方法必须真实发出", "GET", sent.method)
        assertTrue(
            "SigV4 Authorization 头必须随真实报文发出",
            sent.getHeader("Authorization")?.startsWith("AWS4-HMAC-SHA256 ") == true
        )
    }

    @Test
    fun `设备回环上 HEAD 预检发现 ETag 漂移即 ConflictError`() = runBlocking {
        // 覆盖更新路径：expectedEtag 非空 → HEAD 预检返回的远端 ETag 与期望不符 → 预检即冲突
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"remote-newer-etag\"")
                .setHeader("Content-Length", "1")
        )

        val result = newProvider().upload("vault.kdbx", ByteArray(16), expectedEtag = "stale-etag")

        assertTrue("ETag 漂移应为失败结果", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "预检 ETag 漂移必须映射为 ConflictError，实际 ${error?.javaClass?.simpleName}",
            error is SyncException.ConflictError
        )
        assertEquals("remote-newer-etag", (error as SyncException.ConflictError).remoteEtag)
        assertEquals("预检即冲突时不得再发 PUT", "HEAD", server.takeRequest().method)
    }
}
