package com.keepasskey.app.data.breach

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HIBP 范围查询客户端回归测试（TASK-47）。
 *
 * 覆盖：k-匿名上送内容（请求路径只含 5 位前缀，完整哈希与明文绝不出端）、
 * 响应解析（大小写归一 / 非法行跳过）、失败如实抛出（非 2xx 与非法前缀均 fail-closed）。
 */
class HibpRangeClientTest {

    /** 公开常量：SHA-1("password") 的前 5 位 */
    private companion object {
        const val PREFIX = "5BAA6"
        const val SUFFIX = "1E4C9B93F3F0682250B6CF8331B7EE68FD8"
        const val FULL_HASH = "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8"
    }

    private lateinit var server: MockWebServer
    private lateinit var client: HibpRangeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HibpRangeClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `范围查询只上送 5 位前缀，完整哈希与密码明文不出端`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("$SUFFIX:10434004\r\n0000000000000000000000000000000000A:3\r\n")
        )

        val suffixes = client.queryRange(PREFIX)

        assertTrue(suffixes.contains(SUFFIX))
        assertTrue(suffixes.contains("0000000000000000000000000000000000A"))

        val request = server.takeRequest()
        assertEquals("/range/$PREFIX", request.path)
        // k-匿名核心不变量：完整哈希与密码明文均不得出现在请求中
        assertEquals(false, request.path?.contains(FULL_HASH, ignoreCase = true))
        assertEquals(false, request.path?.contains("password", ignoreCase = true))
        assertEquals("", request.body.readUtf8())
    }

    @Test
    fun `小写前缀归一为大写并命中同一路径`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        client.queryRange("5baa6")

        assertEquals("/range/$PREFIX", server.takeRequest().path)
    }

    @Test
    fun `服务端返回小写后缀时归一为大写`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("${SUFFIX.lowercase()}:10434004\r\n")
        )

        assertTrue(client.queryRange(PREFIX).contains(SUFFIX))
    }

    @Test
    fun `非法行静默跳过，不污染结果集`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "这不是哈希:1\r\n$SUFFIX:7\r\n短后缀:2\r\n\r\n"
            )
        )

        val suffixes = client.queryRange(PREFIX)
        assertEquals(1, suffixes.size)
        assertTrue(suffixes.contains(SUFFIX))
    }

    @Test
    fun `非 2xx 如实抛出而非回落空集合`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        val error = assertThrows(BreachCheckException::class.java) {
            runBlocking { client.queryRange(PREFIX) }
        }
        assertTrue(error.message!!.contains("503"))
    }

    @Test
    fun `非法前缀本地 fail-closed 且不发起任何请求`() {
        assertThrows(BreachCheckException::class.java) {
            runBlocking { client.queryRange("ZZZZZ") }
        }
        assertEquals(0, server.requestCount)
    }
}
