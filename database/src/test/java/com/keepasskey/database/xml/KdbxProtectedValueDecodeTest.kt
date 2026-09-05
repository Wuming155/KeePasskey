package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * 受保护字段 Base64 解码安全回归测试（审计 P0-6）。
 *
 * 旧缺陷：受保护值 Base64 解码失败时 catch 后降级使用 value.toByteArray()——
 * 其字节数与 Base64 解码结果不一致，导致内层流密码 keystream 错位，
 * 后续所有受保护字段全部解密成乱码（数据级联损坏）。
 * 修复后：解码失败必须抛出 [KdbxCorruptFileException] 立即中断解析。
 */
class KdbxProtectedValueDecodeTest {

    private val uuidB64: String = Base64.getEncoder().encodeToString(ByteArray(16))

    private fun newCipher(): InnerRandomStreamCipher =
        InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.CHACHA20, ByteArray(64))

    private fun entryXml(vararg stringBlocks: String): String {
        val strings = stringBlocks.joinToString("\n")
        return """
            <KeePassFile>
                <Root>
                    <Group>
                        <UUID>$uuidB64</UUID>
                        <Name>G</Name>
                        <Entry>
                            <UUID>$uuidB64</UUID>
                            $strings
                        </Entry>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()
    }

    private fun protectedString(key: String, value: String): String =
        "<String><Key>$key</Key><Value Protected=\"True\">$value</Value></String>"

    @Test
    fun testInvalidBase64ProtectedValueThrowsCorruptFileException() {
        val xml = entryXml(protectedString("Password", "!!!NOT_VALID_BASE64@@@"))
        val parser = KdbxXmlParser(newCipher())

        val ex = assertThrows(KdbxCorruptFileException::class.java) {
            parser.parse(ByteArrayInputStream(xml.toByteArray()))
        }
        assertTrue("异常消息应指明受保护字段 key: ${ex.message}", ex.message!!.contains("无法解码受保护字段 Base64 数据"))
        assertTrue("异常消息应包含字段名: ${ex.message}", ex.message!!.contains("key=Password"))
    }

    @Test
    fun testSecondInvalidProtectedValueFailsFastWithItsKey() {
        val good = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val xml = entryXml(
            protectedString("Password", good),
            protectedString("RecoveryCode", "###CORRUPT###")
        )
        val parser = KdbxXmlParser(newCipher())

        val ex = assertThrows(KdbxCorruptFileException::class.java) {
            parser.parse(ByteArrayInputStream(xml.toByteArray()))
        }
        assertTrue(ex.message!!.contains("key=RecoveryCode"))
    }

    @Test
    fun testProtectedValueSurroundingWhitespaceIsTrimmedBeforeDecode() {
        // XML 缩进/换行带来的首尾空白必须先 trim() 再解码（Java 基本 Base64 解码器拒绝空白字符）
        val decoded = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val b64 = Base64.getEncoder().encodeToString(decoded)
        val xml = entryXml(protectedString("Password", "\n      $b64\n      "))

        val result = KdbxXmlParser(newCipher()).parse(ByteArrayInputStream(xml.toByteArray()))

        val protected = result.rootGroup.entries[0].fields[KdbxConstants.Fields.PASSWORD]
        assertTrue("受保护 Password 字段应存在", protected != null)
        assertEquals("解码后长度应与 Base64 载荷一致（keystream 未错位）", decoded.size, protected!!.length)
        // 与同参数新流密码实例从零位起解密的期望明文一致（该字段是文档首个受保护值）
        val expectedPlain = newCipher().processBytes(decoded)
        assertArrayEquals(expectedPlain, protected.readUtf8())
    }

    @Test
    fun testUnprotectedValueIsNotBase64Decoded() {
        // 非受保护值原样承载，即使内容不像合法 Base64 也不得触发解码异常
        val xml = entryXml("<String><Key>Notes</Key><Value>hello world!!! not base64</Value></String>")

        val result = KdbxXmlParser(newCipher()).parse(ByteArrayInputStream(xml.toByteArray()))

        val notes = result.rootGroup.entries[0].fields[KdbxConstants.Fields.NOTES]
        assertEquals("hello world!!! not base64", notes!!.readString())
    }
}
