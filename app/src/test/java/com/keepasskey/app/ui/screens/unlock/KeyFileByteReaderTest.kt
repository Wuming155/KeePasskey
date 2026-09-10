package com.keepasskey.app.ui.screens.unlock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * ISSUE-P3-04 密钥文件安全读取单测：上限闸门（不截断半截密钥）+ 空文件语义。
 *
 * 这些用例不触碰 SAF / ContentResolver，可在 JVM 直接执行；SAF 选择器交互需真机验证。
 */
class KeyFileByteReaderTest {

    @Test
    fun `常规密钥文件按原样读出`() {
        val bytes = ByteArray(32) { (it * 3 + 1).toByte() }

        val read = readKeyFileBytesCapped(ByteArrayInputStream(bytes))

        assertNotNull(read)
        assertArrayEquals(bytes, read)
    }

    @Test
    fun `空文件读出空数组由调用方判定为非法`() {
        val read = readKeyFileBytesCapped(ByteArrayInputStream(ByteArray(0)))

        assertNotNull(read)
        assertEquals(0, read!!.size)
    }

    @Test
    fun `恰好等于上限的文件可读`() {
        val bytes = ByteArray(KEY_FILE_MAX_BYTES) { (it % 251).toByte() }

        val read = readKeyFileBytesCapped(ByteArrayInputStream(bytes))

        assertNotNull(read)
        assertEquals(KEY_FILE_MAX_BYTES, read!!.size)
    }

    @Test
    fun `超出上限的文件返回null绝不截断`() {
        val bytes = ByteArray(KEY_FILE_MAX_BYTES + 1) { (it % 251).toByte() }

        val read = readKeyFileBytesCapped(ByteArrayInputStream(bytes))

        assertNull("超限必须整体拒绝，不得返回被截断的密钥材料", read)
    }

    @Test
    fun `可擦除字节流在清理后不残留已写内容`() {
        val sink = ProbeByteArrayOutputStream()
        sink.write(ByteArray(8) { 0x5A })

        assertTrue(sink.size() > 0)
        sink.wipe()

        assertEquals("擦除后计数必须复位", 0, sink.size())
        assertTrue(
            "擦除后内部缓冲必须全零，杜绝密钥材料在堆上残留",
            sink.fullBufferSnapshot().all { it == 0.toByte() }
        )
    }

    /**
     * 探针子类：在子类内直读 [java.io.ByteArrayOutputStream] 的内部缓冲。
     * 不用反射——JDK 17 下 `setAccessible` 对 java.base 非公开成员会被拒。
     */
    private class ProbeByteArrayOutputStream : WipeableByteArrayOutputStream() {
        fun fullBufferSnapshot(): ByteArray = buf.copyOf()
    }
}
