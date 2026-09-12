package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.InnerHeader
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * ISSUE-P3-07（P1-9 残余）附件读取侧别名共享消除回归测试。
 *
 * 旧缺陷：SAX 解析 `BinaryNode` 直接把内层 Header 二进制池中的 `ByteArray` 挂到
 * [KdbxAttachment.data] 上，于是
 * 1. 同一池条目的多个引用者（含历史条目）共享同一可变数组；
 * 2. 外部按 `Closeable` 契约调用 `attachment.clear()/close()` 会把**池内数据**一并清零，
 *    连带损坏其他引用者，并使后续保存去重时指纹取自被清零的数据。
 *
 * 修复后（读取侧出边界即防御性拷贝）：附件字节与本池条目、与其他附件均不共享可变引用，
 * 且保存侧的内容去重语义（按 flags + 字节内容指纹）保持不变。
 */
class KdbxAttachmentAliasIsolationTest {

    private val uuidB64: String = Base64.getEncoder().encodeToString(ByteArray(16))
    private val poolData = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    private fun entryXml(vararg binaryBlocks: String): String {
        val binaries = binaryBlocks.joinToString("\n")
        return """
            <KeePassFile>
                <Root>
                    <Group>
                        <UUID>$uuidB64</UUID>
                        <Name>G</Name>
                        <Entry>
                            <UUID>$uuidB64</UUID>
                            $binaries
                        </Entry>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()
    }

    private fun binaryBlock(name: String, refIndex: Int): String = """
        <Binary>
            <Key>$name</Key>
            <Value Ref="$refIndex"/>
        </Binary>
    """.trimIndent()

    private fun parseWithPool(xml: String, pool: List<InnerHeader.BinaryItem>): KdbxEntry =
        KdbxXmlParser(null).parse(ByteArrayInputStream(xml.toByteArray()), pool)
            .rootGroup.entries.single()

    @Test
    fun `读取侧两个附件互不共享可变引用且不别名池内数组`() {
        val pool = listOf(InnerHeader.BinaryItem(0, poolData))
        val entry = parseWithPool(
            entryXml(binaryBlock("a.bin", 0), binaryBlock("b.bin", 0)),
            pool
        )

        val attA = entry.attachments[0]
        val attB = entry.attachments[1]

        assertArrayEquals(poolData, attA.data)
        assertArrayEquals(poolData, attB.data)
        // 与池、与彼此均不得是同一数组实例
        assertFalse("附件 A 不得别名池内数组", attA.data === pool[0].data)
        assertFalse("附件 B 不得别名池内数组", attB.data === pool[0].data)
        assertFalse("两个附件不得共享同一数组", attA.data === attB.data)
    }

    @Test
    fun `修改返回副本不影响池中数据与其他附件`() {
        // 期望值、池内数组、附件副本必须是三个互不相同的数组实例：
        // 否则「清零附件后断言池完好」会因为断言两侧同为一物而变成恒真（甚至被清零反噬）
        val expected = poolData.copyOf()
        val pool = listOf(InnerHeader.BinaryItem(0, poolData.copyOf()))
        val entry = parseWithPool(
            entryXml(binaryBlock("a.bin", 0), binaryBlock("b.bin", 0)),
            pool
        )

        val attA = entry.attachments[0]
        val attB = entry.attachments[1]
        assertFalse("池内数组不得与期望值同实例", pool[0].data === expected)

        // Closeable 契约路径：清零附件 A（只应影响 A 自身副本）
        attA.clear()

        assertArrayEquals("池内数据必须完好", expected, pool[0].data)
        assertArrayEquals("附件 B 必须完好", expected, attB.data)
        assertArrayEquals("附件 A 自身已被清零", ByteArray(expected.size), attA.data)

        // 直接改写返回副本同样不得外溢到池与另一附件
        attB.data[0] = 99
        assertArrayEquals("池内数据不得被改写", expected, pool[0].data)
        assertEquals("清零后的附件 A 不得被 B 的改写复活", 0, attA.data[0].toInt())
    }

    @Test
    fun `引用索引越界时返回空数组且不抛异常`() {
        val pool = listOf(InnerHeader.BinaryItem(0, poolData))
        val entry = parseWithPool(entryXml(binaryBlock("ghost.bin", 7)), pool)

        // 缺陷 D9 对齐官方 `KdbxFile.Read.Streamed.cs:1004-1009`：池未命中即回退读内联正文
        // （此处正文为空）→ 交付空字节，且索引改为内联哨兵（原始越界值不再保留，
        // 否则写出侧无从区分「池内引用」与「内联正文」）。
        // 越界绝不静默折叠为池索引 0（旧缺陷：张冠李戴到无关附件）。
        assertEquals(BinaryNode.INLINE_REF_INDEX, entry.attachments.single().refIndex)
        assertTrue(entry.attachments.single().data.isEmpty())
    }

    /**
     * 端到端回归：保存（内容去重 → 池 1 条目、两条目引用同一索引）→ 读取后，
     * 附件副本与池、与彼此隔离。
     *
     * 关键判别点：调用方按 `Closeable` 契约清零**其中一个**附件后再保存/读取，
     * 只有该附件自身副本（及其条目）被清零——池数据与另一条目的附件必须字节精确。
     * 旧实现下两者共享池内同一数组，清零会把池与另一条目一并抹掉，
     * 再次保存时去重指纹取自已清零数据，附件内容被静默损坏。
     */
    @Test
    fun `往返后附件副本与池隔离且清零不污染池与另一条目`() {
        val shared = byteArrayOf(9, 8, 7, 6, 5)
        // 期望值单独持有一份实例：任何 clear() 都不可能作用到它，
        // 从而保证「池/另一条目未被清零」的断言不是恒真
        val expected = shared.copyOf()
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("E1", isProtected = false)),
                        attachments = listOf(KdbxAttachment("x.bin", data = shared))
                    ),
                    KdbxEntry(
                        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("E2", isProtected = false)),
                        attachments = listOf(KdbxAttachment("y.bin", data = shared.clone()))
                    )
                )
            )
        )
        val password = "AttachmentIsolation#2026".toCharArray()

        val firstSave = ByteArrayOutputStream().also { KdbxFile.save(it, db, password) }.toByteArray()
        val loaded = KdbxFile.load(ByteArrayInputStream(firstSave), password)

        // 内容去重语义保持：两条目引用同一池索引
        val att1 = loaded.rootGroup.entries[0].attachments.single()
        val att2 = loaded.rootGroup.entries[1].attachments.single()
        assertEquals(1, loaded.binaries.size)
        assertEquals(att1.refIndex, att2.refIndex)
        assertFalse("附件不得别名池内数组", att1.data === loaded.binaries[0].data)
        assertFalse("附属附件之间不得共享数组", att1.data === att2.data)
        assertFalse("期望值不得与池同实例", expected === loaded.binaries[0].data)
        assertArrayEquals(expected, att1.data)

        // 清零其中一份副本：池与另一份副本均完好
        att1.clear()
        assertArrayEquals("池内数据必须完好", expected, loaded.binaries[0].data)
        assertArrayEquals("另一条目的附件必须完好", expected, att2.data)

        // 再次保存/读取：条目 2 的附件内容必须仍字节精确（旧实现下会被清零污染）
        val secondSave = ByteArrayOutputStream().also { KdbxFile.save(it, loaded, password) }.toByteArray()
        val reloaded = KdbxFile.load(ByteArrayInputStream(secondSave), password)
        assertArrayEquals(
            "未清零条目的附件内容不得被连带损坏",
            expected,
            reloaded.rootGroup.entries[1].attachments.single().data
        )
    }
}
