package com.keepasskey.sync

import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.s3.S3SyncProvider
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.Date

/**
 * S3SyncProvider 单元测试：
 * 验证 AWS SigV4 规范请求、Header 计算与鉴权串签名，
 * 以及首传 If-None-Match: * 原子创建与 URL 编码一致性。
 */
class S3SyncProviderTest {

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

    private fun createLoopbackClient(): OkHttpClient {
        val loopbackDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }
        return OkHttpClient.Builder().dns(loopbackDns).build()
    }

    @Test
    fun `测试 AWS SigV4 鉴权请求头格式`() {
        val provider = S3SyncProvider(
            endpoint = "https://s3.amazonaws.com",
            bucketName = "my-secure-vault",
            region = "us-east-1",
            accessKeyId = "AKIAEXAMPLEKEY",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )

        val fixedDate = Date(1788500000000L) // 固定测试时间点
        val headers = provider.signV4(
            method = "GET",
            url = "https://my-secure-vault.s3.amazonaws.com/vault.kdbx",
            payloadHash = S3SyncProvider.EMPTY_SHA256,
            dateTime = fixedDate
        )

        assertNotNull(headers["Authorization"])
        assertNotNull(headers["x-amz-date"])
        assertNotNull(headers["x-amz-content-sha256"])
        assertEquals(S3SyncProvider.EMPTY_SHA256, headers["x-amz-content-sha256"])

        val auth = headers["Authorization"].orEmpty()
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIAEXAMPLEKEY/"))
        assertTrue(auth.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
        assertTrue(auth.contains("Signature="))
    }

    @Test
    fun `测试首传带有 If-None-Match 星号原子创建成功`() = runTest {
        // 1. HEAD 探测返回 404 (文件不存在)
        server.enqueue(MockResponse().setResponseCode(404))
        // 2. PUT 上传返回 200 OK
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"s3-created-etag\"")
        )

        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY",
            secretAccessKey = "TESTSECRET",
            client = createLoopbackClient()
        )

        val result = provider.upload("vault.kdbx", "data".toByteArray(), expectedEtag = null)
        assertTrue(result.isSuccess)
        assertEquals("s3-created-etag", result.getOrThrow())

        val headReq = server.takeRequest()
        assertEquals("HEAD", headReq.method)

        val putReq = server.takeRequest()
        assertEquals("PUT", putReq.method)
        assertEquals("*", putReq.getHeader("If-None-Match"))
    }

    @Test
    fun `测试首传 If-None-Match 遭遇并发 412 抛出冲突异常`() = runTest {
        // 1. HEAD 返回 404
        server.enqueue(MockResponse().setResponseCode(404))
        // 2. PUT 返回 412 Precondition Failed (并发被抢先创建)
        server.enqueue(MockResponse().setResponseCode(412))
        // 3. 冲突后获取当前元数据
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"remote-concurrent-etag\"")
        )

        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY",
            secretAccessKey = "TESTSECRET",
            client = createLoopbackClient()
        )

        val result = provider.upload("vault.kdbx", "data".toByteArray(), expectedEtag = null)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is SyncException.ConflictError)
    }

    @Test
    fun `测试覆盖更新 PUT 附带 If-Match 条件头实现原子覆写`() = runTest {
        // 1. HEAD 预检返回现有 ETag
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"remote-current-etag\"")
                .setHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
        )
        // 2. PUT 覆写成功
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"new-etag\"")
        )

        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY",
            secretAccessKey = "TESTSECRET",
            client = createLoopbackClient()
        )

        val result = provider.upload("vault.kdbx", "data".toByteArray(), expectedEtag = "remote-current-etag")
        assertTrue(result.isSuccess)
        assertEquals("new-etag", result.getOrThrow())

        val headReq = server.takeRequest()
        assertEquals("HEAD", headReq.method)

        val putReq = server.takeRequest()
        assertEquals("PUT", putReq.method)
        // If-Match 必须携带引号包裹的期望 ETag，由服务端原子校验，消除 HEAD+PUT TOCTOU 窗口
        assertEquals("\"remote-current-etag\"", putReq.getHeader("If-Match"))
    }

    @Test
    fun `测试覆盖更新 If-Match 遭遇并发 412 抛出冲突异常`() = runTest {
        // 1. HEAD 预检返回现有 ETag
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"stale-etag\"")
                .setHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
        )
        // 2. PUT 被服务端条件校验拒绝（HEAD 后被并发修改）
        server.enqueue(MockResponse().setResponseCode(412))
        // 3. 冲突后获取当前远端元数据
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"concurrent-new-etag\"")
        )

        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY",
            secretAccessKey = "TESTSECRET",
            client = createLoopbackClient()
        )

        val result = provider.upload("vault.kdbx", "data".toByteArray(), expectedEtag = "stale-etag")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is SyncException.ConflictError)
    }

    @Test
    fun `测试覆盖更新 ETag 预检不匹配快速失败不发 PUT`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"someone-elses-etag\"")
                .setHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
        )

        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY",
            secretAccessKey = "TESTSECRET",
            client = createLoopbackClient()
        )

        val result = provider.upload("vault.kdbx", "data".toByteArray(), expectedEtag = "my-expected-etag")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncException.ConflictError)
        // 仅 HEAD 预检，无 PUT 发出
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `测试首传前置探测网络失败时快速失败不发无条件PUT`() = runTest {
        // F6 修复：expectedEtag 为空且 HEAD 探测遭遇非 404 失败（网络错误/5xx）时必须
        // 快速失败——若退化为无条件 PUT，远端存在他人更新时将被静默覆盖
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY",
            secretAccessKey = "TESTSECRET",
            client = createLoopbackClient()
        )

        val result = provider.upload("vault.kdbx", "data".toByteArray(), expectedEtag = null)
        assertTrue("探测失败必须 fail-fast: ${result.exceptionOrNull()}", result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncException.ProtocolError)
        // 仅 HEAD 探测，绝无 PUT 发出
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `测试编码对象键与 SigV4 规范 URI 一致性`() {
        val provider = S3SyncProvider(
            endpoint = "https://s3.amazonaws.com",
            bucketName = "my-vault",
            region = "us-east-1",
            accessKeyId = "TESTKEY",
            secretAccessKey = "TESTSECRET"
        )

        val fixedDate = Date(1788500000000L)
        // 含中文与空格路径
        val encodedUrl = "https://my-vault.s3.amazonaws.com/%E4%B8%AD%E6%96%87%20%E7%9B%AE%E5%BD%95/test%20db.kdbx"
        val headers = provider.signV4(
            method = "PUT",
            url = encodedUrl,
            payloadHash = S3SyncProvider.EMPTY_SHA256,
            dateTime = fixedDate
        )

        val auth = headers["Authorization"].orEmpty()
        assertTrue(auth.contains("Signature="))
        // 确保签名计算成功且格式合法
        assertEquals("my-vault.s3.amazonaws.com", headers["Host"])
    }
}
