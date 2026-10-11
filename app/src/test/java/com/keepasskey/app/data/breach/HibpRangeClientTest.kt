package com.keepasskey.app.data.breach

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * HIBP 范围查询客户端回归测试（TASK-47）。
 *
 * 覆盖：k-匿名上送内容（请求路径只含 5 位前缀，完整哈希与明文绝不出端）、
 * 响应解析（大小写归一 / 非法行跳过）、失败如实抛出（非 2xx 与非法前缀均 fail-closed）。
 * 及 `ISSUE-P3-578` 响应体尺寸双闸门（申报长度预检 + 读后兜底 / 判定纯函数 / 源码接线守卫）。
 */
class HibpRangeClientTest {

    /** 公开常量：SHA-1("password") 的前 5 位 */
    private companion object {
        const val PREFIX = "5BAA6"
        const val SUFFIX = "1E4C9B93F3F0682250B6CF8331B7EE68FD8"
        const val FULL_HASH = "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8"

        /** 自工作目录上溯定位仓库根的层数上限 */
        const val ROOT_SEARCH_DEPTH = 6
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

    @Test
    fun `恒发 Add-Padding 请求头（HIBP 官方隐私填充建议）`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        client.queryRange(PREFIX)

        val request = server.takeRequest()
        assertEquals("true", request.headers["Add-Padding"])
    }

    @Test
    fun `Add-Padding 填充行 count 为 0 时丢弃，不影响命中判定`() = runBlocking {
        // 官方语义：填充行 count 恒 0，可丢弃；真实泄露行 count ≥ 1
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "$SUFFIX:10434004\r\n" +
                    "00000000000000000000000000000000000:0\r\n" +
                    "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00:0\r\n" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA00:00\r\n" +
                    "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB01:3\r\n"
            )
        )

        val suffixes = client.queryRange(PREFIX)

        assertTrue(suffixes.contains(SUFFIX))
        assertTrue(suffixes.contains("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB01"))
        assertEquals(2, suffixes.size)
        assertEquals(false, suffixes.contains("00000000000000000000000000000000000"))
        assertEquals(false, suffixes.contains("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00"))
        assertEquals(false, suffixes.contains("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA00"))
    }

    // ── ISSUE-P3-578：响应体尺寸双闸门 ─────────────────────────────────────────

    @Test
    fun `申报长度超上限即判越界（不读体）`() {
        // 纯判定口径：11 > 10，闸门①在未读任何字节前即拦下
        assertTrue(HibpRangeClient.exceedsRangeBodyLimit(11L, maxBytes = 10L))
        // 生产上限量级：正常 Add-Padding 响应的量级（百 KiB 内）必须放行，1 MiB+ 必须拦下
        assertTrue(HibpRangeClient.exceedsRangeBodyLimit(4L * 1024 * 1024))
        assertFalse(HibpRangeClient.exceedsRangeBodyLimit(100L * 1024))
    }

    @Test
    fun `申报长度未超上限与恰好等于上限均放行`() {
        assertFalse(HibpRangeClient.exceedsRangeBodyLimit(9L, maxBytes = 10L))
        // 边界：恰等于上限不算越界（判据为严格大于）
        assertFalse(HibpRangeClient.exceedsRangeBodyLimit(10L, maxBytes = 10L))
        assertFalse(
            HibpRangeClient.exceedsRangeBodyLimit(
                HibpRangeClient.MAX_RESPONSE_BYTES,
                maxBytes = HibpRangeClient.MAX_RESPONSE_BYTES
            )
        )
    }

    @Test
    fun `长度未知（null 与 -1）不拦，交读后兜底`() {
        // chunked / Content-Length 缺失时 OkHttp 报 -1；两者都视为「未知」放行给闸门②
        assertFalse(HibpRangeClient.exceedsRangeBodyLimit(null, maxBytes = 10L))
        assertFalse(HibpRangeClient.exceedsRangeBodyLimit(-1L, maxBytes = 10L))
        assertEquals(-1L, HibpRangeClient.UNKNOWN_LENGTH)
    }

    @Test
    fun `读后累计长度超上限即判越界`() {
        // 闸门②同口径：已读累计（恒非负、非 null）
        assertTrue(HibpRangeClient.exceedsRangeBodyLimit(11L, maxBytes = 10L))
        assertFalse(HibpRangeClient.exceedsRangeBodyLimit(10L, maxBytes = 10L))
    }

    @Test
    fun `生产上限为 1 MiB，落于本出口合法响应量级的宽裕余量内`() {
        assertEquals(1L * 1024 * 1024, HibpRangeClient.MAX_RESPONSE_BYTES)
        // 合法响应（Add-Padding 对齐 800–1000 条 ≈ 40–50 KiB）须远小于上限
        assertTrue(HibpRangeClient.MAX_RESPONSE_BYTES > 10L * 100 * 1024)
    }

    @Test
    fun `声明 Content-Length 超上限时不读体直接拒绝`() {
        // MockWebServer 依实体大小自动写 Content-Length（≈1 MiB + 184 字节 > 1 MiB 上限）
        server.enqueue(MockResponse().setResponseCode(200).setBody(oversizedBody()))

        val error = assertThrows(BreachCheckException::class.java) {
            runBlocking { client.queryRange(PREFIX) }
        }
        // 「声明」字样证明拦在闸门①（申报预检），而非读后兜底
        assertTrue(error.message!!.contains("声明"))
        assertTrue(error.message!!.contains("已拒绝接收"))
    }

    @Test
    fun `长度未知（chunked）时由读后兜底拒绝`() {
        // setChunkedBody 不写 Content-Length ⇒ OkHttp contentLength() = -1 ⇒ 只余闸门②
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(oversizedBody(), 8192))

        val error = assertThrows(BreachCheckException::class.java) {
            runBlocking { client.queryRange(PREFIX) }
        }
        assertFalse(error.message!!.contains("声明"))
        assertTrue(error.message!!.contains("已拒绝接收"))
    }

    @Test
    fun `源码接线守卫：申报长度预检先于有界读取，且不得再整体物化响应体`() {
        val source = readRepoFile(
            "app/src/main/java/com/keepasskey/app/data/breach/HibpRangeClient.kt"
        )

        // ① 不得再有 `body.string()` 无界物化（ISSUE-P3-578 的原始缺陷形态）
        assertFalse("响应体不得再经 body.string() 整体物化", source.contains("body.string()"))
        // ② 有界读取须存在（流式 + 块缓冲）
        assertTrue(source.contains("byteStream()"))
        assertTrue(source.contains("readBounded(body)"))
        // ③ 申报长度预检必须先于有界读取（顺序反转＝先读爆内存再判超限）
        val declaredAt = source.indexOf("exceedsRangeBodyLimit(declaredLength)")
        val readAt = source.indexOf("readBounded(body)")
        assertTrue("申报长度预检缺位", declaredAt >= 0)
        assertTrue("有界读取缺位", readAt >= 0)
        assertTrue("申报长度预检必须先于有界读取", declaredAt < readAt)
        // ④ 读后兜底亦走同一判据
        assertTrue(source.contains("exceedsRangeBodyLimit(total)"))
    }

    /** 造一个明显超过 [HibpRangeClient.MAX_RESPONSE_BYTES] 的合法形态响应体（≈1 MiB + 数百字节）。 */
    private fun oversizedBody(): String {
        val line = "$SUFFIX:10434004\r\n"
        return line.repeat((HibpRangeClient.MAX_RESPONSE_BYTES / line.length).toInt() + 4)
    }

    /** 自工作目录逐层上溯定位仓库根下的文件并读全文（同仓既有测试范式）。 */
    private fun readRepoFile(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(ROOT_SEARCH_DEPTH) {
            val target = File(dir, relativePath)
            if (target.isFile) return target.readText()
            dir = dir?.parentFile
        }
        error("无法定位仓库文件：$relativePath（起始：${System.getProperty("user.dir")}）")
    }
}
