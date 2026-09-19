package com.keepasskey.sync.network

import com.keepasskey.sync.model.SyncException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 下载体入口封顶单测（ISSUE-P0-09 / ISSUE-P2-75）。
 *
 * AC③ 负例：超大响应（声明超限 / 声明缺失 / 声明撒谎）均被拒绝且不物化；
 * AC④ 正例：正常尺寸响应完整读取不受影响。
 */
class SyncDownloadLimitsTest {

    @Test
    fun `声明尺寸超限时直接拒绝且不消费流`() {
        val input = ByteArrayInputStream(ByteArray(1024))
        val ex = assertThrows(SyncException.ProtocolError::class.java) {
            SyncDownloadLimits.readBounded(input, declaredLength = 1024L, maxBytes = 512L, label = "测试")
        }
        assertEquals(413, ex.statusCode)
        // 未建立任何缓冲、未读取任何字节
        assertEquals(1024, input.available())
    }

    @Test
    fun `声明缺失时流式累积封顶拒绝超限响应`() {
        val payload = ByteArray(1024) { it.toByte() }
        val input = ByteArrayInputStream(payload)
        val ex = assertThrows(SyncException.ProtocolError::class.java) {
            SyncDownloadLimits.readBounded(input, declaredLength = -1L, maxBytes = 512L, label = "测试")
        }
        assertEquals(413, ex.statusCode)
    }

    @Test
    fun `声明撒谎小于实际时仍在读取中途被拒不物化超限缓冲`() {
        val payload = ByteArray(1024) { (it % 127).toByte() }
        val input = ByteArrayInputStream(payload)
        val ex = assertThrows(SyncException.ProtocolError::class.java) {
            SyncDownloadLimits.readBounded(input, declaredLength = 100L, maxBytes = 512L, label = "测试")
        }
        assertEquals(413, ex.statusCode)
    }

    @Test
    fun `正常尺寸响应完整读取不受影响`() {
        val payload = "kdbx-file-bytes-for-sync".toByteArray(Charsets.UTF_8)
        val read = SyncDownloadLimits.readBounded(
            ByteArrayInputStream(payload),
            declaredLength = payload.size.toLong(),
            maxBytes = 1024L,
            label = "测试"
        )
        assertTrue("正常响应必须逐字节完整返回", payload.contentEquals(read))
    }

    @Test
    fun `生产上限常量符合设计量级`() {
        assertEquals(128L * 1024 * 1024, SyncDownloadLimits.MAX_DOWNLOAD_BYTES)
        assertEquals(16L * 1024 * 1024, SyncDownloadLimits.MAX_PROPFIND_BYTES)
    }

    // ---------------- ISSUE-P3-206：copyBounded 流式封顶判据 ----------------

    @Test
    fun `copyBounded 声明超限时未写 sink 任何字节即拒绝`() {
        val input = ByteArrayInputStream(ByteArray(1024))
        val sink = ByteArrayOutputStream()
        val ex = assertThrows(SyncException.ProtocolError::class.java) {
            SyncDownloadLimits.copyBounded(input, declaredLength = 1024L, maxBytes = 512L, label = "测试", sink = sink)
        }
        assertEquals(413, ex.statusCode)
        assertEquals(1024, input.available())
        assertEquals("声明预检拒绝时 sink 不得收到任何字节", 0, sink.size())
    }

    @Test
    fun `copyBounded 声明缺失时累计封顶中途拒绝`() {
        val payload = ByteArray(1024) { it.toByte() }
        val input = ByteArrayInputStream(payload)
        val sink = ByteArrayOutputStream()
        val ex = assertThrows(SyncException.ProtocolError::class.java) {
            SyncDownloadLimits.copyBounded(input, declaredLength = -1L, maxBytes = 512L, label = "测试", sink = sink)
        }
        assertEquals(413, ex.statusCode)
        assertTrue("中途拒绝时 sink 已写字节必须不超过上限", sink.size() <= 512L)
    }

    @Test
    fun `copyBounded 正常搬运逐字节一致且返回字节数`() {
        val payload = "streamed-kdbx-download-bytes".toByteArray(Charsets.UTF_8)
        val sink = ByteArrayOutputStream()
        val total = SyncDownloadLimits.copyBounded(
            ByteArrayInputStream(payload),
            declaredLength = payload.size.toLong(),
            maxBytes = 1024L,
            label = "测试",
            sink = sink
        )
        assertEquals(payload.size.toLong(), total)
        assertTrue("流式搬运必须逐字节一致", payload.contentEquals(sink.toByteArray()))
    }

    @Test
    fun `readBounded 经 copyBounded 委托后语义不变`() {
        val payload = ByteArray(700) { (it % 251).toByte() }
        val read = SyncDownloadLimits.readBounded(
            ByteArrayInputStream(payload),
            declaredLength = payload.size.toLong(),
            maxBytes = 1024L,
            label = "测试"
        )
        assertTrue(payload.contentEquals(read))
    }
}
