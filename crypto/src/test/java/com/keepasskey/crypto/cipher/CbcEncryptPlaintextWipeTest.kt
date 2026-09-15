package com.keepasskey.crypto.cipher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * ISSUE-P3-96（审计 RUST-07）回归：**CBC 加密流的明文中转副本必须清零**。
 *
 * 缺陷形态：`CbcEncryptingOutputStream` 有两处明文副本
 * —— `emitAlignedBlocks()` 的 `buffer.copyOf(aligned)`（交给变换的 `chunk`）
 * 与 `close()` 的 `buffer.copyOf(filled)`（补齐前的残余明文）；
 * 二者此前只随局部变量出栈、静默留存至 GC，与类 KDoc「明文缓冲在 [close] 时显式归零」的宣称不符。
 *
 * 验证方式（**行为级**）：注入记录型变换，捕获它收到的**数组引用**与**当时的快照**——
 * ① 快照证明该副本原本是明文（保证用例非空跑）；② 引用在 `close()` 后必须逐字节为 0。
 */
class CbcEncryptPlaintextWipeTest {

    /** 变换收到的每个数组引用（用于断言 close 后已清零） */
    private val received = mutableListOf<ByteArray>()

    /** 变换收到时的内容快照（用于断言「原本确为明文」） */
    private val receivedSnapshot = mutableListOf<ByteArray>()

    private fun recordingTransform(calls: MutableList<Int> = mutableListOf()): CbcBlockTransform =
        { _: ByteArray, _: ByteArray, data: ByteArray ->
            calls += data.size
            received += data
            receivedSnapshot += data.copyOf()
            // 返回等长密文（内容无关紧要，只需证明写出链路未被清零动作破坏）
            ByteArray(data.size) { (data[it] + 1).toByte() }
        }

    private fun newStream(
        sink: ByteArrayOutputStream,
        calls: MutableList<Int> = mutableListOf()
    ) = CbcEncryptingOutputStream(
        sink = sink,
        key = ByteArray(16) { 1 },
        iv = ByteArray(16) { 2 },
        transform = recordingTransform(calls),
        bufferSize = 64
    )

    @Test
    fun `分组块与末尾补齐块的明文副本在关闭后均须清零`() {
        val sink = ByteArrayOutputStream()
        val calls = mutableListOf<Int>()
        val stream = newStream(sink, calls)

        // 40 字节：先走 emitAlignedBlocks（32 字节已对齐块，chunk 副本），
        // close 时再走残余补齐（8 字节残余 → plainSource 副本 + padded finalBlock）
        val plaintext = ByteArray(40) { (it + 7).toByte() }
        stream.write(plaintext)
        stream.close()

        assertEquals("应发生两次分组变换（对齐块 + 结尾补齐块）", 2, calls.size)
        assertEquals(32, calls[0])
        // 残余 8 字节 + PKCS#7 补 8 字节 = 一个整分组（16 字节）
        assertEquals(16, calls[1])

        // 用例非空跑：变换收到的内容原本确实是明文（非全零）
        receivedSnapshot.forEachIndexed { index, snapshot ->
            assertTrue(
                "第 ${index + 1} 次变换收到的副本应为明文（否则本用例无意义）",
                snapshot.any { it != 0.toByte() }
            )
        }

        // ISSUE-P3-96 的核心断言：关闭后所有交出的明文副本都必须已清零
        received.forEachIndexed { index, array ->
            assertTrue(
                "第 ${index + 1} 次变换收到的明文副本在 close 后必须逐字节清零（泄漏面）",
                array.all { it == 0.toByte() }
            )
        }

        assertTrue("密文应已写出（清零动作不得影响写出结果）", sink.size() == 48)
    }

    @Test
    fun `恰好整块对齐时仍须清零且写出填充块`() {
        val sink = ByteArrayOutputStream()
        val calls = mutableListOf<Int>()
        val stream = newStream(sink, calls)

        stream.write(ByteArray(64) { 3 }) // 恰好一个缓冲（64 = 4 × 16）
        stream.close()

        // 写入过程中缓冲写满即 emitAlignedBlocks（1 次 64 字节）；
        // close 时 filled==0 → 追加一整个填充块（1 次 16 字节）
        assertEquals(listOf(64, 16), calls)
        received.forEach { assertTrue("对齐路径的明文副本同样必须清零", it.all { b -> b == 0.toByte() }) }
    }
}
