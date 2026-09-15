package com.keepasskey.core.model

import com.keepasskey.core.security.BinarySource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * [KdbxAttachment] 交付字节的**所有权口径**行为锁（ISSUE-P3-104）。
 *
 * 缺陷背景：类 KDoc 曾宣称「无论哪种来源，[KdbxAttachment.data] / `openStream` 交付的字节
 * 都不与内层二进制池、也不与其它附件共享可变引用」，但内存附件的实现是
 * `get() = source?.load() ?: inlineData`——**直接返回本实例持有的数组本身**。
 * 该「文档过度宣称」正是历史真实缺陷的土壤：写侧误以为恒为副本而在 `finally` 中 `fill(0)`，
 * 使「写出」顺带销毁了内存附件字节（同一实例再次保存即全零）。
 *
 * 本用例把两侧语义**分别锁死**（口径以类 KDoc 更正后的表述为准）：
 * - 内存附件：**借用视图**（零拷贝，同一实例；调用方不得修改 / 清零）；
 * - 落盘附件：**每次按需读回独立副本**（所有权随返回移交调用方）。
 *
 * 两侧任一被反向改写（内存改成返回副本 → 静默丢零拷贝；落盘改成返回共享数组 → 别名泄漏）
 * 都会使本用例失败。
 */
class KdbxAttachmentOwnershipTest {

    /** 记录型落盘来源：每次 `load()` 返回新的独立副本，并留存供断言比对实例身份 */
    private class RecordingBinarySource(private val payload: ByteArray) : BinarySource {

        val loadedCopies = mutableListOf<ByteArray>()

        override val size: Long get() = payload.size.toLong()

        override fun load(): ByteArray {
            val copy = payload.copyOf()
            loadedCopies += copy
            return copy
        }

        override fun openStream(): InputStream = ByteArrayInputStream(payload)

        override fun contentHash(): Int = payload.contentHashCode()
    }

    @Test
    fun `内存附件的 data 是借用视图而非副本`() {
        val payload = byteArrayOf(11, 22, 33, 44)
        val attachment = KdbxAttachment(name = "mem.bin", refIndex = 0, data = payload)

        assertSame(
            "内存附件 data 必须返回本实例持有的数组本身（零拷贝借用视图，见类 KDoc）",
            payload,
            attachment.data
        )
        assertSame("连续读取必须仍是同一实例", attachment.data, attachment.data)
        assertSame("inlineBytes() 与 data 必须同源（同为零拷贝通道）", payload, attachment.inlineBytes())
        assertSame(
            "resolveData 在内存来源下同样交付借用视图",
            payload,
            attachment.resolveData(listOf(byteArrayOf(7, 7, 7)))
        )

        // 借用视图契约的另一面：清零即内容消失——调用方不得据此修改 / 清零
        attachment.clear()
        assertArrayEquals(ByteArray(payload.size), attachment.data)
    }

    @Test
    fun `落盘附件的 data 与 resolveData 每次交付独立副本`() {
        val payload = byteArrayOf(9, 9, 9, 9, 9)
        val source = RecordingBinarySource(payload)
        val attachment = KdbxAttachment(name = "big.bin", refIndex = 0, source = source)

        assertTrue("落盘附件的内存副本必须为空数组", attachment.inlineBytes().isEmpty())

        val first = attachment.data
        val second = attachment.data
        assertNotSame("落盘附件两次读取必须各自独立（不得交出共享数组）", first, second)
        assertFalse("落盘附件不得直接交出 store 内部数组", first === payload)
        assertArrayEquals(payload, first)
        assertArrayEquals("source 须各交付一份新副本", source.loadedCopies[0], first)
        assertArrayEquals(source.loadedCopies[1], second)

        // 清零交付副本不得影响来源自身（后续读取仍字节精确）
        first.fill(0)
        assertArrayEquals("清零副本后来源必须完好", payload, attachment.data)

        assertArrayEquals(
            "resolveData 在落盘来源下与 data 同口径（独立副本）",
            payload,
            attachment.resolveData(emptyList())
        )
    }

    @Test
    fun `落盘附件的 clear 不动作且内容可重复读取`() {
        val payload = byteArrayOf(1, 2, 3)
        val source = RecordingBinarySource(payload)
        val attachment = KdbxAttachment(name = "big.bin", refIndex = 0, source = source)

        attachment.clear()

        assertArrayEquals(
            "落盘附件字节由 store 中多个引用者共享，clear() 不得触碰其来源",
            payload,
            attachment.data
        )
    }
}
