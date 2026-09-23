package com.keepasskey.database.file

import com.keepasskey.crypto.hash.HashUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * **外部管理器产出的**「中等尺寸附件 + 历史快照」库必须可打开（ISSUE-P1-276 AC⑥ / `AGENTS.md` 规则 8）。
 *
 * ## 为什么这一例不可由自产用例替代
 *
 * `AttachmentHistoryReferenceBudgetRoundtripTest` 证明的是「自家 writer → 自家 reader」，
 * 恒为绿、**证明不了**跨实现结论。本 fixture 的**落盘方是 KeePassXC 官方 CLI 2.7.12**
 * （基线语料为本仓 v4 探针产物，经官方 `edit` 两轮改写后由官方实现重写落盘——
 * 生成命令、逐项读数与 SHA-256 见同目录 `FIXTURE.md`），其形态恰为 ISSUE-P1-276 的受害形态：
 * 同一池条目被引用 **6 次**（`<Binary>` 6 个且全部 `<Value Ref="0"/>`），
 * 旧计费口径下 `6 × 1 MiB > 2 × 1 MiB + 1 MiB` ⇒ 官方产物被本仓解析器判为「引用放大攻击」、
 * **整库打不开**。故本用例是「同形态可打开性」的**官方侧产 → 本仓读**半边；对偶半边
 * （本仓产 → 官方读）见 `AttachmentHistoryReferenceBudgetRoundtripTest` 留下的探针产物。
 *
 * 断言口径：解锁成功 + 引用形态（池 1 条目 / 6 个引用者）+ 附件字节哈希逐字节一致——
 * 三者缺一即说明「能打开」只是解析器宽容而非真的读对了。
 */
class ThirdPartyAttachmentHistoryFixtureTest {

    private fun fixtureStream() = javaClass.getResourceAsStream(FIXTURE_PATH)
        ?: error("缺少 fixture：$FIXTURE_PATH（生成命令见同目录 FIXTURE.md）")

    @Test
    fun `官方CLI产出的中等附件加历史快照库可解锁且引用形态与字节一致`() {
        val loaded = KdbxFile.load(fixtureStream(), FIXTURE_PASSWORD.toCharArray())

        // 形态：官方 CLI 的 `export` 读数为 1 个池条目 + 6 个 `<Value Ref="0"/>`
        assertEquals("该库应只有 1 个池条目", 1, loaded.binaries.size)
        assertEquals(ATTACHMENT_BYTES.toLong(), loaded.binaries.single().size)

        val entry = loaded.rootGroup.entries.single()
        val holders = listOf(entry) + entry.history
        val referenced = holders.flatMap { it.attachments }.filter { it.refIndex == 0 }
        assertEquals("主条目 + 主条目 5 条历史快照的附件引用数应与 fixture 形态一致", 6, referenced.size)
        assertEquals("应读到 5 条历史快照", 5, entry.history.size)

        referenced.forEach { att ->
            assertEquals(ATTACHMENT_BYTES, att.data.size)
            assertEquals(
                "附件字节必须与 FIXTURE.md 记录的哈希逐字节一致",
                ATTACHMENT_SHA256,
                HashUtil.sha256(att.data).joinToString("") { "%02x".format(it) }
            )
        }
        assertNotNull("本用例必须真的读到附件，而非全部为空", referenced.firstOrNull())
        assertArrayEquals(
            "同名附件在各引用者处必须交付同一内容",
            referenced.first().data,
            referenced.last().data
        )
    }

    private companion object {
        const val FIXTURE_PATH = "/fixtures/attachment-history/keepassxc-medium-attachment-history.kdbx"
        const val FIXTURE_PASSWORD = "attachment-budget-probe-2026"
        const val ATTACHMENT_BYTES = 1_048_576
        const val ATTACHMENT_SHA256 =
            "631b84027d6b9e52b539c4e8373622d23032dfadc64d60af87339c9037e4f769"
    }
}
