package com.keepasskey.app.ui.screens.unlock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * ISSUE-P3-04 密钥文件安全读取单测：整流读入语义 + 空文件语义。
 *
 * ISSUE-P3-435（用户 2026-10-02 裁决）：**不设文件大小上限**——密钥文件内容即密钥材料
 * 本身（KDBX4 为整文件 SHA-512），任何截断都产出错误密钥；KeePass 官方 / kp2a /
 * KeePassXC 均无上限，本仓对齐。原「上限闸门（不截断半截密钥）」两用例随被删对象
 * （`KEY_FILE_MAX_BYTES` / `TooLarge`）退役，由「大文件整读」回归承接。
 *
 * 这些用例不触碰 SAF / ContentResolver，可在 JVM 直接执行；SAF 选择器交互需真机验证。
 */
class KeyFileByteReaderTest {

    @Test
    fun `常规密钥文件按原样读出`() {
        val bytes = ByteArray(32) { (it * 3 + 1).toByte() }

        val read = readKeyFileBytes(ByteArrayInputStream(bytes))

        assertNotNull(read)
        assertArrayEquals(bytes, read)
    }

    @Test
    fun `空文件读出空数组由调用方判定为非法`() {
        val read = readKeyFileBytes(ByteArrayInputStream(ByteArray(0)))

        assertNotNull(read)
        assertEquals(0, read.size)
    }

    @Test
    fun `旧1MiB上限附近的合法大文件整流读出`() {
        // ISSUE-P3-435 回归：旧上限 1 MiB 曾把 2.28 MB 级合法密钥文件拒之门外
        // （用户装机走查实测）；去上限后该尺寸必须整文件读出、绝不截断
        val bytes = ByteArray((1 shl 20) + 1) { (it % 251).toByte() }

        val read = readKeyFileBytes(ByteArrayInputStream(bytes))

        assertNotNull(read)
        assertEquals(bytes.size, read.size)
        assertArrayEquals(bytes, read)
    }

    @Test
    fun `用户实测2_28MB级密钥文件整流读出`() {
        val bytes = ByteArray(2_280_000) { (it % 251).toByte() }

        val read = readKeyFileBytes(ByteArrayInputStream(bytes))

        assertEquals("用户实测被旧上限拒读的尺寸必须完整读出（P3-435）", bytes.size, read.size)
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
