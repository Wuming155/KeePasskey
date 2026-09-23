package com.keepasskey.database.xml

import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.file.InnerHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * 附件池引用的**物化预算**回归：ISSUE-P2-48（审计 F-10）立规、ISSUE-P1-276 重定口径。
 *
 * 逐引用 `item.load().copyOf()` 是 ISSUE-P3-07 的别名隔离契约防线，不得取消；
 * 但同一池条目被引用 N 次即 N 份副本，元素数与整包上限都只间接约束该乘积，故须按引用记账。
 *
 * ## 本类锁定的三面判据（封闭公式见 [AttachmentBudget] 类 KDoc）
 *
 * | 判据 | 公式 | 拦截对象 | 本类用例 |
 * |---|---|---|---|
 * | ① 去重计费 | `Σ_{i ∈ 去重引用集} sᵢ ≤ 2·Σⱼ sⱼ + 1 MiB` | 记账不变量（恒真，不拦截） | 全部用例隐式覆盖 |
 * | ② 单条目引用次数 | `nᵢ ≤ 1024` | 放大**倍数** | `单池条目引用次数的边界` |
 * | ③ 单条目物化字节 | `nᵢ·sᵢ ≤ 64 MiB` | 单条目**内存** | `单池条目加海量引用被物化预算拒绝而非 OOM` |
 *
 * ## ISSUE-P1-276：旧口径的受害区间与放行用例
 *
 * 旧判据（`Σᵢ nᵢ·sᵢ > 2·Σⱼ sⱼ + 1 MiB`，按引用计费、无去重）在单条目时化简为
 * `s·(N − 2) > 1 MiB`，而 `> 1 MiB` 的附件走落盘不计费 ⇒ 受害区间为 `(512 KiB, 1 MiB]`：
 * 「中等尺寸附件 + 若干历史快照」的自产库会被判为引用放大攻击、整库打不开。
 * 故本类新增三例**必须放行**的用例，逐点钉住该窗口的三处（下界内侧 / 上界端点 / 多条目不组合成安全）。
 */
class AttachmentReferenceBudgetTest {

    private val uuidB64: String = Base64.getEncoder().encodeToString(ByteArray(16))

    private fun xmlWithBinaries(binariesXml: String): String = """
        <KeePassFile>
            <Root>
                <Group>
                    <UUID>$uuidB64</UUID>
                    <Name>G</Name>
                    <Entry>
                        <UUID>$uuidB64</UUID>
                        $binariesXml
                    </Entry>
                </Group>
            </Root>
        </KeePassFile>
    """.trimIndent()

    private fun parse(xml: String, pool: List<InnerHeader.BinaryItem>) =
        KdbxXmlParser(null).parse(ByteArrayInputStream(xml.toByteArray()), pool)

    /** N 个同名 `<Binary>` 全部引用池索引 [refIndex]（历史快照对同一附件的真实形态）。 */
    private fun refsToSameItem(refIndex: Int, count: Int, name: String = "photo.jpg"): String =
        buildString {
            repeat(count) { append("<Binary><Key>$name</Key><Value Ref=\"$refIndex\"/></Binary>") }
        }

    @Test
    fun `单池条目加海量引用被物化预算拒绝而非 OOM`() {
        // 64 KiB 单池条目被引用 5000 次：旧口径按 Σ nᵢ·sᵢ 累计 ≈ 320 MiB ⇒ 越界；
        // 现口径由「③ 单条目物化字节（64 MiB）」在**第 1025 次**引用处 fail-closed
        // （1024 × 64 KiB = 恰好 64 MiB 不越界，第 1025 份才越过），消息仍以
        // 「累计物化字节超出预算」点明越界量（作用域由「整库累计」收窄为「单条目累计」）。
        val pool = listOf(InnerHeader.BinaryItem(0, ByteArray(64 * 1024) { 7 }))
        val binariesXml = buildString {
            repeat(5000) { append("<Binary><Key>a$it</Key><Value Ref=\"0\"/></Binary>") }
        }

        val ex = assertThrows(KdbxCorruptFileException::class.java) {
            parse(xmlWithBinaries(binariesXml), pool)
        }
        assertTrue(
            "异常消息应点明累计预算越界，实际: ${ex.message}",
            ex.message.orEmpty().contains("累计物化字节超出预算")
        )
    }

    @Test
    fun `共享池条目的少量引用不被误拒`() {
        // 池总字节 5，累计 2×5=10 ≤ 2×5 + 1 MiB 余量 → 必须放行（对齐 KdbxAttachmentAliasIsolationTest 形态）
        val pool = listOf(InnerHeader.BinaryItem(0, byteArrayOf(9, 8, 7, 6, 5)))
        val binariesXml =
            "<Binary><Key>x.bin</Key><Value Ref=\"0\"/></Binary>" +
                "<Binary><Key>y.bin</Key><Value Ref=\"0\"/></Binary>"

        val entry = parse(xmlWithBinaries(binariesXml), pool).rootGroup.entries.single()

        assertEquals(2, entry.attachments.size)
        assertEquals(0, entry.attachments[0].refIndex)
        assertEquals(0, entry.attachments[1].refIndex)
    }

    /**
     * ISSUE-P1-276 AC③：**受害区间上界端点**（`s` 恰为内联落盘阈值 1 MiB，仍内联计量）
     * 挂 3 条历史引用（`N = 4`）必须放行。
     *
     * 旧口径读数：`4 MiB > 2×1 MiB + 1 MiB = 3 MiB` ⇒ 拒绝（整库打不开）。整改前此例必红。
     */
    @Test
    fun `一兆内联附件带三条历史引用必须放行`() {
        val pool = listOf(InnerHeader.BinaryItem(0, ByteArray(1 * 1024 * 1024) { 3 }))

        val entry = parse(xmlWithBinaries(refsToSameItem(0, count = 4)), pool)
            .rootGroup.entries.single()

        assertEquals(4, entry.attachments.size)
        entry.attachments.forEach { att ->
            assertEquals(0, att.refIndex)
            assertEquals(1 * 1024 * 1024, att.data.size)
        }
    }

    /**
     * ISSUE-P1-276 AC③：受害区间**下界内侧**（`512 KiB + 1 B`，旧口径的最小触发尺寸）必须放行。
     *
     * 旧口径读数：`4 × (512 KiB + 1) = 2 MiB + 4 > 2 × (512 KiB + 1) + 1 MiB = 2 MiB + 2` ⇒ 拒绝。
     */
    @Test
    fun `五百一十二K加一字节的内联附件带三条历史引用必须放行`() {
        val size = 512 * 1024 + 1
        val pool = listOf(InnerHeader.BinaryItem(0, ByteArray(size)))

        val entry = parse(xmlWithBinaries(refsToSameItem(0, count = 4)), pool)
            .rootGroup.entries.single()

        assertEquals(4, entry.attachments.size)
        assertEquals(size, entry.attachments.last().data.size)
    }

    /**
     * ISSUE-P1-276 AC③：**多条目不组合成安全**——两个 1 MiB 内联附件各挂 2 条历史
     * （各 `N = 3`，总计 6 次引用）必须放行。
     *
     * 旧口径读数：`6 MiB > 2 × 2 MiB + 1 MiB = 5 MiB` ⇒ 拒绝。
     */
    @Test
    fun `两条目各挂两条历史引用的一兆附件必须放行`() {
        val pool = listOf(
            InnerHeader.BinaryItem(0, ByteArray(1 * 1024 * 1024) { 1 }),
            InnerHeader.BinaryItem(0, ByteArray(1 * 1024 * 1024) { 2 })
        )
        val binariesXml =
            refsToSameItem(0, count = 3, name = "a.jpg") +
                refsToSameItem(1, count = 3, name = "b.jpg")

        val entry = parse(xmlWithBinaries(binariesXml), pool).rootGroup.entries.single()

        assertEquals(6, entry.attachments.size)
        assertEquals(listOf(0, 0, 0, 1, 1, 1), entry.attachments.map { it.refIndex })
    }

    /**
     * ISSUE-P1-276 判据② 的**边界**：同一池条目被引 1024 次放行、1025 次拒绝。
     *
     * 与判据③ 的分工也用本例钉住：条目仅 1 KiB，物化总量（1024 KiB）远低于 64 MiB，
     * 故拦截只能来自「引用次数」这一维度。
     */
    @Test
    fun `单池条目引用次数的边界`() {
        val pool = listOf(InnerHeader.BinaryItem(0, ByteArray(1024) { 5 }))

        val allowed = parse(
            xmlWithBinaries(refsToSameItem(0, count = AttachmentBudget.MAX_REFERENCES_PER_POOL_ITEM)),
            pool
        ).rootGroup.entries.single()
        assertEquals(AttachmentBudget.MAX_REFERENCES_PER_POOL_ITEM, allowed.attachments.size)

        val ex = assertThrows(KdbxCorruptFileException::class.java) {
            parse(
                xmlWithBinaries(
                    refsToSameItem(0, count = AttachmentBudget.MAX_REFERENCES_PER_POOL_ITEM + 1)
                ),
                pool
            )
        }
        assertTrue(
            "异常消息应点明引用次数越界，实际: ${ex.message}",
            ex.message.orEmpty().contains("被引用次数超出上限")
        )
    }
}
