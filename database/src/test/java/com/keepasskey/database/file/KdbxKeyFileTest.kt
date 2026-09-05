package com.keepasskey.database.file

import com.keepasskey.database.exception.KdbxCorruptFileException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

/**
 * KdbxKeyFile 单元测试：密钥文件官方解析梯子（XML v1.0 Base64 / v2.0 Hex+Hash 校验 /
 * 裸 32 字节 / 64 位 hex 文本 / 任意二进制整文件 SHA-256）。
 * XML v2.0 向量取自真实 KeePass 2.x 生成的 .keyx 密钥文件（111.keyx）。
 */
class KdbxKeyFileTest {

    companion object {
        /** 真实 KeePass 2.x 生成的 v2.0 XML 密钥文件（与测试库配套的原始文件内容） */
        private const val REAL_V2_KEYFILE_XML = """<?xml version="1.0" encoding="UTF-8"?>
<KeyFile>
    <Meta>
        <Version>2.0</Version>
    </Meta>
    <Key>
        <Data Hash="F6BC2010">
            7DDC70C9 FED76DEE 09DBFCE3 FA317E7F
            200C71F2 3651617F 5225F44C B212ABDE
        </Data>
    </Key>
</KeyFile>
"""

        private const val REAL_V2_KEY_HEX = "7DDC70C9FED76DEE09DBFCE3FA317E7F200C71F23651617F5225F44CB212ABDE"
    }

    @Test
    fun `v2_0 xml keyfile with real world vector extracts hex decoded key`() {
        val key = KdbxKeyFile.extractKey(REAL_V2_KEYFILE_XML.toByteArray(Charsets.UTF_8))
        assertEquals(32, key.size)
        assertEquals(REAL_V2_KEY_HEX, key.joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `v2_0 xml keyfile with tampered hash attribute is rejected`() {
        val tampered = REAL_V2_KEYFILE_XML.replace("F6BC2010", "F6BC2011")
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxKeyFile.extractKey(tampered.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `v1_0 xml keyfile with base64 data extracts decoded key`() {
        val keyBytes = ByteArray(32) { (it * 7 + 3).toByte() }
        val base64 = java.util.Base64.getEncoder().encodeToString(keyBytes)
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<KeyFile>
    <Meta><Version>1.0</Version></Meta>
    <Key><Data>$base64</Data></Key>
</KeyFile>
"""
        val key = KdbxKeyFile.extractKey(xml.toByteArray(Charsets.UTF_8))
        assertArrayEquals(keyBytes, key)
    }

    @Test
    fun `v2_0 xml keyfile with non hex data is rejected`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<KeyFile>
    <Meta><Version>2.0</Version></Meta>
    <Key><Data>ZZZZ70C9 FED76DEE 09DBFCE3 FA317E7F 200C71F2 3651617F 5225F44C B212ABDE</Data></Key>
</KeyFile>
"""
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxKeyFile.extractKey(xml.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `xml keyfile without data element is rejected`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<KeyFile>
    <Meta><Version>2.0</Version></Meta>
    <Key></Key>
</KeyFile>
"""
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxKeyFile.extractKey(xml.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `raw 32 byte binary file is used as key directly`() {
        val raw = ByteArray(32) { (it * 11 + 1).toByte() }
        val key = KdbxKeyFile.extractKey(raw)
        assertArrayEquals(raw, key)
    }

    @Test
    fun `64 char hex text file with whitespace is decoded`() {
        val text = "7D DC 70 C9 FE D7 6D EE 09 DB FC E3 FA 31 7E 7F\n" +
                "20 0C 71 F2 36 51 61 7F 52 25 F4 4C B2 12 AB DE\n"
        val key = KdbxKeyFile.extractKey(text.toByteArray(Charsets.UTF_8))
        assertEquals(REAL_V2_KEY_HEX, key.joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `uppercase hex text file is decoded case insensitively`() {
        val text = REAL_V2_KEY_HEX.lowercase()
        val key = KdbxKeyFile.extractKey(text.toByteArray(Charsets.UTF_8))
        assertEquals(REAL_V2_KEY_HEX, key.joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `arbitrary binary file falls back to whole file sha256`() {
        val raw = ByteArray(100) { (it * 13 + 5).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(raw)
        val key = KdbxKeyFile.extractKey(raw)
        assertArrayEquals(expected, key)
    }

    @Test
    fun `utf8 bom prefixed xml keyfile still parses`() {
        val xml = REAL_V2_KEYFILE_XML.toByteArray(Charsets.UTF_8)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xml
        val key = KdbxKeyFile.extractKey(withBom)
        assertEquals(REAL_V2_KEY_HEX, key.joinToString("") { "%02X".format(it) })
    }
}
