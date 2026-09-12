package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * `Protected` 属性大小写敏感性回归测试（缺陷 D4 / P1→P2）。
 *
 * 官方精确比较：`KdbxFile.Read.Streamed.cs:1066-1068`
 * `if(xr.MoveToAttribute(AttrProtected)) if(xr.Value == ValTrue)`——`ValTrue = "True"`，
 * **大小写敏感**；官方写侧也只写 `"True"`（`KdbxFile.Write.cs:858,949`）。
 *
 * 旧缺陷：本仓用 `lowercase() == "true"`，于是非规范写入者的 `Protected="true"` 被当作密文
 * 并**推进了密钥流**，而其后的所有受保护值永久错位——保存一次即固化损坏。
 */
class KdbxProtectedAttributeCaseSensitivityTest {

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

    private fun parse(xml: String, cipher: InnerRandomStreamCipher) =
        KdbxXmlParser(cipher).parse(ByteArrayInputStream(xml.toByteArray())).rootGroup.entries.single()

    @Test
    fun `小写 protected 属性按明文承载且不推进密钥流`() {
        // 第一条字段：Protected="true"（非规范拼写）→ 官方当明文，不消耗密钥流。
        // 值刻意选成「合法 Base64 但不是想要的内容」，一旦被误当密文就会解出乱码。
        val lowerCaseValue = "aGVsbG8="
        // 第二条字段：规范 Protected="True"，密文由同参数、从零位起的新流密码产生
        val expectedPlain = "SECRET"
        val upperCipherText = Base64.getEncoder()
            .encodeToString(newCipher().processBytes(expectedPlain.toByteArray()))

        val entry = parse(
            entryXml(
                """
                <String><Key>Lower</Key><Value Protected="true">$lowerCaseValue</Value></String>
                <String><Key>Upper</Key><Value Protected="True">$upperCipherText</Value></String>
                """.trimIndent()
            ),
            newCipher()
        )

        val lower = entry.customFields.single { it.key == "Lower" }
        assertFalse("Protected=\"true\" 必须按明文承载（官方精确比较 \"True\"）", lower.value.isProtected)
        assertEquals(
            "小写拼写的值应原样承载为文本，绝不做 Base64 解码",
            lowerCaseValue,
            lower.value.readString()
        )

        // 流位置判据：若小写值误当密文，它已消耗 5 字节密钥流（"aGVsbG8=" 解码后 5 字节），
        // 第二条受保护值将从位 5 解出 → 解出的明文不再是 "SECRET"。
        val upper = entry.customFields.single { it.key == "Upper" }
        assertTrue("规范 Protected=\"True\" 仍应受保护", upper.value.isProtected)
        assertEquals(
            "小写拼写不得消耗密钥流，否则后续受保护值整体错位",
            expectedPlain,
            upper.value.readString()
        )
    }

    @Test
    fun `精确 True 是唯一受认可拼写`() {
        val cipher = newCipher()
        val cipherText = Base64.getEncoder().encodeToString(cipher.processBytes("x".toByteArray()))

        val entry = parse(
            entryXml(
                """
                <String><Key>Exact</Key><Value Protected="True">$cipherText</Value></String>
                <String><Key>Spaced</Key><Value Protected=" True ">plain</Value></String>
                """.trimIndent()
            ),
            newCipher()
        )

        assertTrue("Protected=\"True\" 受保护", entry.customFields.single { it.key == "Exact" }.value.isProtected)
        assertFalse(
            "带空白的 \" True \" 与官方精确比较不符，必须按明文",
            entry.customFields.single { it.key == "Spaced" }.value.isProtected
        )
    }

    @Test
    fun `大写 TRUE 与混合大小写一律按明文`() {
        val entry = parse(
            entryXml(
                """
                <String><Key>A</Key><Value Protected="TRUE">p1</Value></String>
                <String><Key>B</Key><Value Protected="tRuE">p2</Value></String>
                """.trimIndent()
            ),
            newCipher()
        )

        assertEquals("p1", entry.customFields.single { it.key == "A" }.value.readString())
        assertEquals("p2", entry.customFields.single { it.key == "B" }.value.readString())
        assertFalse(entry.customFields.single { it.key == "A" }.value.isProtected)
        assertFalse(entry.customFields.single { it.key == "B" }.value.isProtected)
    }

    @Test
    fun `规范 True 的受保护值仍正确解密`() {
        val expected = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val cipherText = Base64.getEncoder().encodeToString(newCipher().processBytes(expected))

        val entry = parse(
            entryXml("<String><Key>Password</Key><Value Protected=\"True\">$cipherText</Value></String>"),
            newCipher()
        )

        val password = entry.fields[KdbxConstants.Fields.PASSWORD]
        assertNotNull(password)
        assertTrue(password!!.isProtected)
        assertArrayEquals(expected, password.readUtf8())
    }
}
