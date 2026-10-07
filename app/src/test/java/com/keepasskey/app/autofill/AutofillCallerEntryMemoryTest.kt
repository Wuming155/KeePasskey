package com.keepasskey.app.autofill

import com.keepasskey.app.security.CallerCertDigests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AutofillCallerEntryMemory] 单元测试（ISSUE-P3-528）。
 *
 * 以 `context = null` 的内存语义覆盖关联记忆的准入口径：
 * 同包名同签名召回；**同包名换签名不召回**（重打包 / 侧载冒名不得继承记忆）；
 * 包名非法 fail-closed；摘要不可读退回「仅包名」降级键且摘要可读时不回退；
 * 多摘要任一命中即召回（签名轮换）；容量上限按记录时刻淘汰最旧。
 */
class AutofillCallerEntryMemoryTest {

    private val memory = AutofillCallerEntryMemory(context = null)

    private fun digests(vararg values: String) = CallerCertDigests.of(values.toList())

    @Test
    fun `无记录时召回为空`() {
        assertNull(memory.recall("com.example.app", digests("ABCD")))
    }

    @Test
    fun `同包名同签名记录后可召回`() {
        memory.remember("com.example.app", "ABCD", "entry-1")

        assertEquals("entry-1", memory.recall("com.example.app", digests("ABCD")))
    }

    @Test
    fun `同包名换签名不得召回`() {
        memory.remember("com.example.app", "ABCD", "entry-1")

        // 重打包 / 换签名是对抗模型的现实路径：摘要参与记忆键，记忆不是包名维度的旁路
        assertNull(memory.recall("com.example.app", digests("FEDC")))
    }

    @Test
    fun `包名非法既不记录也不召回`() {
        memory.remember("app", "ABCD", "entry-1")
        memory.remember("com.1bad.app", "ABCD", "entry-2")

        assertNull(memory.recall("app", digests("ABCD")))
        assertNull(memory.recall("com.1bad.app", digests("ABCD")))
    }

    @Test
    fun `空条目标识被忽略`() {
        memory.remember("com.example.app", "ABCD", "   ")

        assertNull(memory.recall("com.example.app", digests("ABCD")))
    }

    @Test
    fun `摘要不可读退回仅包名降级键且摘要可读时不回退`() {
        memory.remember("com.example.app", null, "entry-1")

        assertEquals("entry-1", memory.recall("com.example.app", CallerCertDigests.EMPTY))
        // 摘要后续可读（如包可见性恢复）时按「包名 + 摘要」键判定，视为未命中
        assertNull(memory.recall("com.example.app", digests("ABCD")))
    }

    @Test
    fun `签名轮换期任一摘要命中即召回`() {
        // 记录落在当时的主摘要上
        memory.remember("com.example.app", "OLD", "entry-1")

        // 轮换后系统同时返回当前与历史签名者：任一命中即召回（ISSUE-P3-93 同口径）
        assertEquals("entry-1", memory.recall("com.example.app", digests("NEW", "OLD")))
    }

    @Test
    fun `非主摘要命中时同样召回`() {
        memory.remember("com.example.app", "SECOND", "entry-1")

        assertEquals("entry-1", memory.recall("com.example.app", digests("FIRST", "SECOND")))
    }

    @Test
    fun `超出容量上限时淘汰最旧记录`() {
        // 容量上限＝16（AutofillCallerEntryMemory.MAX_ENTRIES，与 AutofillLoginFieldMemory 同量级）
        repeat(16) { index ->
            memory.remember("com.example.app$index", null, "entry-$index")
        }
        memory.remember("com.example.app16", null, "entry-16")

        assertNull("最旧一条应被淘汰", memory.recall("com.example.app0", CallerCertDigests.EMPTY))
        assertEquals(
            "最新一条须保留",
            "entry-16",
            memory.recall("com.example.app16", CallerCertDigests.EMPTY)
        )
    }

    @Test
    fun `重复记录同一调用方时后写覆盖前写`() {
        memory.remember("com.example.app", "ABCD", "entry-1")
        memory.remember("com.example.app", "ABCD", "entry-2")

        assertEquals("entry-2", memory.recall("com.example.app", digests("ABCD")))
    }

    @Test
    fun `clear 后召回为空`() {
        memory.remember("com.example.app", "ABCD", "entry-1")
        memory.clear()

        assertNull(memory.recall("com.example.app", digests("ABCD")))
    }
}
