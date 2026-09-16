package com.keepasskey.database.file

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream

/**
 * ISSUE-P3-140 回归：`HmacBlockOutputStream.flushBlock` 的**末尾残块明文副本**写出后必须清零。
 *
 * ## 缺陷背景
 *
 * `flushBlock` 对「写满的块」直接引用内部缓冲（其清零由 `close()` 的 `finally` 承担），
 * 对「末尾残块」则用 `buffer.copyOf(filled)` 拷一份切片。该切片是**明文的独立副本**：
 * `close()` 里的 `buffer.fill(0)` 碰不到它，故它会随局部变量出栈后静默留存至 GC ——
 * 正是本仓反复出现的「补一处、漏一处」同型缺口（对齐 `ISSUE-P3-96` 在 CBC 流上的处理口径）。
 *
 * ## 断言方式（可观测、非纸面）
 *
 * 以「记录型 sink」持有**写入时传出的数组引用本身**（不是拷贝），于是可以在 `close()` 之后
 * 直接检查那段内存是否已被清零。两条防空扫断言保证本用例真的观察到了明文切片：
 * ① 必须截获到该引用；② 截获瞬间它必须含非零字节。
 * 若把 `flushBlock` 的清零改回去，本用例必须变红。
 */
class HmacBlockOutputStreamTailWipeTest {

    @Test
    fun `末尾残块的明文切片写出后被清零`() {
        val sink = CapturingSink(expectedPayloadLength = TAIL_LENGTH)
        val stream = HmacBlockOutputStream(sink, hmacKey64(), BLOCK_SIZE)

        stream.write(ByteArray(TAIL_LENGTH) { (it + 1).toByte() })
        stream.close()

        val captured = sink.payloadRef
        assertNotNull("用例必须真的截获到末尾残块的明文切片（防空扫）", captured)
        assertTrue("截获时刻该切片必须含明文，否则本断言无意义", sink.payloadHadPlaintextWhenWritten)
        assertTrue(
            "末尾残块明文副本写出后必须被清零（ISSUE-P3-140）",
            captured!!.all { it == 0.toByte() }
        )
    }

    @Test
    fun `写满的块由 close 的缓冲清零覆盖`() {
        // 反向锁定：满块路径不得被「只清副本」的修复顺手改成清 buffer（那会在写盘过程中
        // 提前抹掉尚未交付的块）——此路径的清零责任人仍是 close() 的 finally。
        val sink = CapturingSink(expectedPayloadLength = BLOCK_SIZE)
        val stream = HmacBlockOutputStream(sink, hmacKey64(), BLOCK_SIZE)

        stream.write(ByteArray(BLOCK_SIZE) { (it + 1).toByte() })
        stream.close()

        val captured = sink.payloadRef
        assertNotNull("用例必须真的截获到满块引用（防空扫）", captured)
        assertTrue("截获时刻该块必须含明文", sink.payloadHadPlaintextWhenWritten)
        assertTrue("满块缓冲在 close() 后必须已清零", captured!!.all { it == 0.toByte() })
    }

    /**
     * 记录型 sink：只保留**写入时传出的数组引用**与其当时的内容特征。
     *
     * 通过数组长度区分三类写出：`hmacer.compute(...)` 的 32 字节摘要、`LE32` 的 4 字节长度、
     * 以及载荷切片（长度恰为 [expectedPayloadLength]）——后者正是被测对象。
     */
    private class CapturingSink(private val expectedPayloadLength: Int) : OutputStream() {

        var payloadRef: ByteArray? = null
        var payloadHadPlaintextWhenWritten: Boolean = false

        override fun write(b: Int) = Unit

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == expectedPayloadLength && b.size == expectedPayloadLength) {
                payloadRef = b
                payloadHadPlaintextWhenWritten = b.any { it != 0.toByte() }
            }
        }
    }

    private companion object {
        /** 远小于 `DEFAULT_BLOCK_SIZE`（1 MiB）的块尺寸：让「末尾残块」在一次写入内即成立 */
        const val BLOCK_SIZE = 64
        const val TAIL_LENGTH = 10

        /** KDBX 规范要求 HMAC 密钥恒为 64 字节 */
        fun hmacKey64(): ByteArray = ByteArray(64) { (it * 7 + 3).toByte() }
    }
}
