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
