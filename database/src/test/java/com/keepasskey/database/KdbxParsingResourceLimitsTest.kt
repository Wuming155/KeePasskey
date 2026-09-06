package com.keepasskey.database

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.file.InnerHeader
import com.keepasskey.database.file.SizeBoundedInputStream
import com.keepasskey.database.io.LittleEndianUtil
import com.keepasskey.database.xml.KdbxXmlParser
import com.keepasskey.database.xml.TextNode
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * KDBX 解析资源防线单元测试（Wave 12 解析炸弹防护）：
 * 覆盖 XML 文本长度上限、嵌套深度上限、二进制池条目/总量封顶与解压输出护栏。
 */
class KdbxParsingResourceLimitsTest {

    @Test
    fun `XML 文本节点超出字符上限即拒绝`() {
        val node = TextNode(maxChars = 8, onText = {})

        node.text("12345".toCharArray(), 0, 5)
        assertThrows(KdbxCorruptFileException::class.java) {
            node.text("67890".toCharArray(), 0, 5)
        }
    }

    @Test
    fun `XML 嵌套深度超出上限即拒绝`() {
        val depth = KdbxXmlParser.MAX_XML_DEPTH + 6
        val xml = buildString {
            append("<KeePassFile><Root>")
            repeat(depth) { append("<Group>") }
            repeat(depth) { append("</Group>") }
            append("</Root></KeePassFile>")
        }

        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxXmlParser(null).parse(xml.byteInputStream())
        }
    }

    @Test
    fun `合法深度范围内的空分组 XML 可正常解析或按语义拒绝`() {
        // 合法浅层文档：深度防线不得误伤（解析结果仅要求不因深度被拒）
        val xml = "<KeePassFile><Root><Group></Group></Root></KeePassFile>"
        // 空 Root Group 允许解析为默认库结构（不抛深度异常即视为通过防线）
        try {
            KdbxXmlParser(null).parse(xml.byteInputStream())
        } catch (e: KdbxCorruptFileException) {
            // 语义层拒绝（如缺少 UUID）不属于资源防线范畴，此处仅确保消息与深度无关
            assert(!e.message!!.contains("嵌套深度"))
        }
    }

    @Test
    fun `二进制池条目数超出上限即拒绝`() {
        val bos = ByteArrayOutputStream()
        repeat(InnerHeader.MAX_BINARY_POOL_ENTRIES + 1) {
            bos.write(KdbxConstants.InnerHeaderFieldId.BINARY.toInt())
            LittleEndianUtil.writeInt(bos, 5)
            bos.write(1) // flags
            bos.write(byteArrayOf(1, 2, 3, 4))
        }

        assertThrows(KdbxCorruptFileException::class.java) {
            InnerHeader.deserialize(ByteArrayInputStream(bos.toByteArray()))
        }
    }

    @Test
    fun `解压输出护栏_超出字节上限即拒绝`() {
        val data = ByteArray(64) { it.toByte() }
        val bounded = SizeBoundedInputStream(ByteArrayInputStream(data), maxBytes = 8)

        assertThrows(KdbxCorruptFileException::class.java) {
            bounded.readAllBytes()
        }
    }

    @Test
    fun `解压输出护栏_限额内读取正常`() {
        val data = ByteArray(8) { it.toByte() }
        val bounded = SizeBoundedInputStream(ByteArrayInputStream(data), maxBytes = 8)

        val read = bounded.readAllBytes()
        assert(read.size == 8)
    }
}
