package com.keepasskey.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * [readBoundedBytes] 单元测试（`ISSUE-P3-617`）。
 *
 * 以内存流覆盖累计封顶语义：恰为上限可通过、越过 1 字节即抛超限异常且不返回部分缓冲、
 * 空流返回空数组、多块累积（跨 64 KiB 块边界与扩容路径）、上限非法参数拒绝。
 * 调用方（漂移合并源文件、附件 / 图标 SAF 流）只传各自通道上限调用本函数，
 * 其上限取值与接线由各通道的源码守卫锁定（见 `EntryEditIconUploadGateTest` 与漂移守卫）。
 */
class BoundedStreamReadsTest {

    @Test
    fun `空流返回空数组`() {
        assertEquals(0, ByteArrayInputStream(ByteArray(0)).readBoundedBytes(1024).size)
    }

    @Test
    fun `恰为上限可通过`() {
        val payload = ByteArray(1024) { it.toByte() }

        assertArrayEquals(payload, ByteArrayInputStream(payload).readBoundedBytes(1024L))
    }

    @Test
    fun `越过上限1字节即抛超限异常`() {
        val payload = ByteArray(1025) { it.toByte() }

        try {
            ByteArrayInputStream(payload).readBoundedBytes(1024L)
            fail("越过上限必须抛 StreamLimitExceededException")
        } catch (large: StreamLimitExceededException) {
            assertTrue(large.message!!.contains("1024"))
        }
    }

    @Test
    fun `上限为0时非空流即超限`() {
        try {
            ByteArrayInputStream(byteArrayOf(1)).readBoundedBytes(0L)
            fail("上限为 0 时任何字节都超限")
        } catch (large: StreamLimitExceededException) {
            // 期望路径
        }
        assertEquals(0, ByteArrayInputStream(ByteArray(0)).readBoundedBytes(0L).size)
    }

    @Test
    fun `多块累积跨块边界仍精确封顶`() {
        // 200 KiB 跨 3 个 64 KiB 块 + 扩容路径；上限恰为载荷时通过，加 1 字节即超限
        val payload = ByteArray(200 * 1024) { (it % 251).toByte() }

        assertArrayEquals(payload, ByteArrayInputStream(payload).readBoundedBytes(payload.size.toLong()))
        try {
            ByteArrayInputStream(payload).readBoundedBytes(payload.size.toLong() - 1)
            fail("累计越过上限必须抛超限异常")
        } catch (large: StreamLimitExceededException) {
            // 期望路径
        }
    }

    @Test
    fun `上限恰为块边界倍数时行为一致`() {
        val payload = ByteArray(128 * 1024) { it.toByte() }

        assertArrayEquals(payload, ByteArrayInputStream(payload).readBoundedBytes(payload.size.toLong()))
        try {
            ByteArrayInputStream(payload).readBoundedBytes(payload.size.toLong() - 1)
            fail("块边界处越过上限必须抛超限异常")
        } catch (large: StreamLimitExceededException) {
            // 期望路径
        }
    }

    @Test
    fun `单字节慢流同样累计封顶`() {
        val payload = ByteArray(100) { it.toByte() }
        val drip = object : InputStream() {
            private val delegate = ByteArrayInputStream(payload)
            override fun read(): Int = delegate.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len <= 0) return 0
                val single = delegate.read()
                if (single < 0) return -1
                b[off] = single.toByte()
                return 1
            }
        }

        assertArrayEquals(payload, drip.readBoundedBytes(payload.size.toLong()))
        try {
            ByteArrayInputStream(payload).readBoundedBytes(50L)
            fail("慢流累计越过上限必须抛超限异常")
        } catch (large: StreamLimitExceededException) {
            // 期望路径
        }
    }

    @Test
    fun `超限异常是IO异常便于调用方按失败处理`() {
        try {
            ByteArrayInputStream(ByteArray(10)).readBoundedBytes(5L)
            fail("越过上限必须抛超限异常")
        } catch (large: StreamLimitExceededException) {
            assertTrue(large is java.io.IOException)
        }
    }

    @Test
    fun `非法上限被拒绝`() {
        try {
            ByteArrayInputStream(ByteArray(1)).readBoundedBytes(-1L)
            fail("负上限必须被拒绝")
        } catch (illegal: IllegalArgumentException) {
            // 期望路径
        }
    }
}
