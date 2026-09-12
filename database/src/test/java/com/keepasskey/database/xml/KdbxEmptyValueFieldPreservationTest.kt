package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.file.InnerHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * 空 `<Value/>` 元素语义回归测试（缺陷 D3 / P2）。
 *
 * 旧缺陷：`KdbxXmlStringNode.kt` 的 `rawValue ?: return` 使空元素整条 `<String>` 被丢弃——
 * SAX 对 `<Value/>` 不触发 `characters()`。官方真实夹具（`/DataExchange/Sample.kdbx`）
 * 的 Notes / URL / UserName 正是 `<Value/>`，官方保留空 `ProtectedString`。
 *
 * 官方对照：`KdbxFile.Read.Streamed.cs:776` `xr.ReadElementString()` 对空元素返回空串。
 */
class KdbxEmptyValueFieldPreservationTest {

    private val uuidB64: String = Base64.getEncoder().encodeToString(ByteArray(16))

    private fun newCipher(): InnerRandomStreamCipher =
        InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.CHACHA20, ByteArray(64))

    private fun entryXml(body: String): String = """
        <KeePassFile>
            <Root>
                <Group>
                    <UUID>$uuidB64</UUID>
                    <Name>G</Name>
                    <Entry>
                        <UUID>$uuidB64</UUID>
                        $body
                    </Entry>
                </Group>
            </Root>
        </KeePassFile>
    """.trimIndent()

    private fun parse(xml: String, cipher: InnerRandomStreamCipher? = null) =
        KdbxXmlParser(cipher).parse(ByteArrayInputStream(xml.toByteArray())).rootGroup.entries.single()

    @Test
    fun `自闭合空 Value 不得丢字段且值为空`() {
        val entry = parse(entryXml("<String><Key>Notes</Key><Value/></String>"))

        val notes = entry.fields[KdbxConstants.Fields.NOTES]
        assertNotNull("空 <Value/> 的字段不得整条消失（官方保留空 ProtectedString）", notes)
        assertEquals("空值字段长度应为 0", 0, notes!!.length)
        assertEquals("空值字段读回应为空串", "", notes.readString())
    }

    @Test
    fun `带闭合标签的空 Value 同样不得丢字段`() {
        val entry = parse(entryXml("<String><Key>URL</Key><Value></Value></String>"))

        val url = entry.fields[KdbxConstants.Fields.URL]
        assertNotNull("<Value></Value> 与 <Value/> 语义应一致", url)
        assertEquals("", url!!.readString())
    }

    @Test
    fun `官方夹具形态的空字段集合全部保留`() {
        // 官方 Sample.kdbx 中 Notes / URL / UserName 即写作 <Value/>
        val entry = parse(
            entryXml(
                """
                <String><Key>Title</Key><Value>T</Value></String>
                <String><Key>UserName</Key><Value/></String>
                <String><Key>Password</Key><Value>p</Value></String>
                <String><Key>URL</Key><Value/></String>
                <String><Key>Notes</Key><Value/></String>
                """.trimIndent()
            )
        )

        assertEquals("五个标准字段必须全部在场", 5, entry.fields.size)
        assertEquals("", entry.fields[KdbxConstants.Fields.USER_NAME]!!.readString())
        assertEquals("", entry.fields[KdbxConstants.Fields.URL]!!.readString())
        assertEquals("", entry.fields[KdbxConstants.Fields.NOTES]!!.readString())
        assertEquals("T", entry.fields[KdbxConstants.Fields.TITLE]!!.readString())
    }

    @Test
    fun `空的自定义字段同样保留且不得误判为受保护`() {
        val entry = parse(entryXml("<String><Key>EmptyCustom</Key><Value/></String>"))

        assertEquals("自定义空字段应进入 customFields", 1, entry.customFields.size)
        val custom = entry.customFields.single()
        assertEquals("EmptyCustom", custom.key)
        assertFalse("空值不得被当作受保护值", custom.value.isProtected)
        assertEquals("", custom.value.readString())
        assertTrue("标准字段集合不应包含自定义字段", entry.fields.isEmpty())
    }

    @Test
    fun `空的受保护 Value 不消耗密钥流且不抛异常`() {
        // 空受保护值：官方写侧仅在载荷长度 > 0 时才写 Base64（KdbxFile.Write.cs:860-862），
        // 空载荷不得推进 inner stream，否则后续受保护字段会整体错位。
        val entry = parse(
            entryXml(
                """
                <String><Key>Password</Key><Value Protected="True"/></String>
                <String><Key>Second</Key><Value Protected="True">${Base64.getEncoder().encodeToString(newCipher().processBytes("AB".toByteArray()))}</Value></String>
                """.trimIndent()
            ),
            newCipher()
        )

        val password = entry.fields[KdbxConstants.Fields.PASSWORD]
        assertNotNull("空的受保护字段不得消失", password)
        assertEquals(0, password!!.length)
        assertTrue("受保护标志应保留（空载荷不消耗密钥流）", password.isProtected)

        // 第二个受保护值必须仍从密钥流位 0 解出（若空值推进了流，此处会解成乱码——长度即不同）
        assertEquals("AB", entry.customFields.single().value.readString())
    }

    @Test
    fun `空元素语义不得误伤非空文本`() {
        val entry = parse(entryXml("<String><Key>Notes</Key><Value>hello</Value></String>"))
        assertEquals("hello", entry.fields[KdbxConstants.Fields.NOTES]!!.readString())
    }
}
