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
 * XML v2.0 向量为测试专用的合成密钥（P0-2 凭据泄露整改：不使用任何真实密钥文件内容；
 * Hash 属性 = 合成密钥 SHA-256 前 4 字节，按官方同一公式计算）。
 */
class KdbxKeyFileTest {

    companion object {
        /** 测试专用合成 v2.0 XML 密钥文件（32 字节测试密钥 = 0x01..0x20 顺序十六进制串） */
        private const val TEST_V2_KEYFILE_XML = """<?xml version="1.0" encoding="UTF-8"?>
<KeyFile>
    <Meta>
        <Version>2.0</Version>
    </Meta>
    <Key>
        <Data Hash="AE216C2E">
            01020304 05060708 090A0B0C 0D0E0F10
            11121314 15161718 191A1B1C 1D1E1F20
        </Data>
    </Key>
</KeyFile>
"""

        private const val TEST_V2_KEY_HEX = "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20"
    }

    @Test
    fun `v2_0 xml keyfile with synthetic vector extracts hex decoded key`() {
        val key = KdbxKeyFile.extractKey(TEST_V2_KEYFILE_XML.toByteArray(Charsets.UTF_8))
        assertEquals(32, key.size)
        assertEquals(TEST_V2_KEY_HEX, key.joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `v2_0 xml keyfile with tampered hash attribute is rejected`() {
        val tampered = TEST_V2_KEYFILE_XML.replace("AE216C2E", "AE216C2F")
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
    <Key><Data>ZZ020304 05060708 090A0B0C 0D0E0F10 11121314 15161718 191A1B1C 1D1E1F20</Data></Key>
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
        val text = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10\n" +
                "11 12 13 14 15 16 17 18 19 1A 1B 1C 1D 1E 1F 20\n"
        val key = KdbxKeyFile.extractKey(text.toByteArray(Charsets.UTF_8))
        assertEquals(TEST_V2_KEY_HEX, key.joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `uppercase hex text file is decoded case insensitively`() {
        val text = TEST_V2_KEY_HEX.lowercase()
        val key = KdbxKeyFile.extractKey(text.toByteArray(Charsets.UTF_8))
        assertEquals(TEST_V2_KEY_HEX, key.joinToString("") { "%02X".format(it) })
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
        val xml = TEST_V2_KEYFILE_XML.toByteArray(Charsets.UTF_8)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xml
        val key = KdbxKeyFile.extractKey(withBom)
        assertEquals(TEST_V2_KEY_HEX, key.joinToString("") { "%02X".format(it) })
    }
}
