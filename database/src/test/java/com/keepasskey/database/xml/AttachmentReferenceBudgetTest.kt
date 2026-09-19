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
 * ISSUE-P2-48（审计 F-10）回归：附件池引用的**累计物化字节**预算。
 *
 * 逐引用 `item.load().copyOf()` 是 ISSUE-P3-07 的别名隔离契约防线，不得取消；
 * 但同一池条目被引用 N 次即 N 份副本，元素数与整包上限都只间接约束该乘积，
 * 故须以 [AttachmentBudget] 按累计字节 fail-closed。
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

    @Test
    fun `单池条目加海量引用被累计预算拒绝而非 OOM`() {
        // 64 KiB 单池条目被引用 5000 次 → 累计 ≈ 320 MiB，远超 2×64 KiB + 1 MiB 预算
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
}
