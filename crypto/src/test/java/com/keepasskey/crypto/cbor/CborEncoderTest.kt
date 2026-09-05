package com.keepasskey.crypto.cbor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.LinkedHashMap

/**
 * RFC 8949 / RFC 7049 标准测试向量与确定性 CBOR 编码器单元测试
 */
class CborEncoderTest {

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun fromHex(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = clean.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    @Test
    fun `测试无符号整数编码已知向量`() {
        // 0 -> 0x00
        assertEquals("00", CborEncoder.encode(0L).toHex())
        // 1 -> 0x01
        assertEquals("01", CborEncoder.encode(1L).toHex())
        // 10 -> 0x0A
        assertEquals("0A", CborEncoder.encode(10L).toHex())
        // 23 -> 0x17
        assertEquals("17", CborEncoder.encode(23L).toHex())
        // 24 -> 0x1818
        assertEquals("1818", CborEncoder.encode(24L).toHex())
        // 25 -> 0x1819
        assertEquals("1819", CborEncoder.encode(25L).toHex())
        // 100 -> 0x1864
        assertEquals("1864", CborEncoder.encode(100L).toHex())
        // 1000 -> 0x1903E8
        assertEquals("1903E8", CborEncoder.encode(1000L).toHex())
        // 1000000 -> 0x1A000F4240
        assertEquals("1A000F4240", CborEncoder.encode(1000000L).toHex())
        // 1000000000000 -> 0x1B000000E8D4A51000
        assertEquals("1B000000E8D4A51000", CborEncoder.encode(1000000000000L).toHex())
    }

    @Test
    fun `测试负整数编码已知向量`() {
        // -1 -> 0x20
        assertEquals("20", CborEncoder.encode(-1L).toHex())
        // -10 -> 0x29
        assertEquals("29", CborEncoder.encode(-10L).toHex())
        // -24 -> 0x37
        assertEquals("37", CborEncoder.encode(-24L).toHex())
        // -25 -> 0x3818
        assertEquals("3818", CborEncoder.encode(-25L).toHex())
        // -100 -> 0x3863
        assertEquals("3863", CborEncoder.encode(-100L).toHex())
        // -1000 -> 0x3903E7
        assertEquals("3903E7", CborEncoder.encode(-1000L).toHex())
        // -7 (COSE ES256) -> 0x26
        assertEquals("26", CborEncoder.encode(-7L).toHex())
        // -8 (COSE EdDSA) -> 0x27
        assertEquals("27", CborEncoder.encode(-8L).toHex())
        // -257 (COSE RS256) -> 0x390100 (-1 - 256)
        assertEquals("390100", CborEncoder.encode(-257L).toHex())
    }

    @Test
    fun `测试文本字符串编码已知向量`() {
        // "" -> 0x60
        assertEquals("60", CborEncoder.encode("").toHex())
        // "a" -> 0x6161
        assertEquals("6161", CborEncoder.encode("a").toHex())
        // "IETF" -> 0x6449455446
        assertEquals("6449455446", CborEncoder.encode("IETF").toHex())
        // "\"\\" -> 0x62225C
        assertEquals("62225C", CborEncoder.encode("\"\\").toHex())
    }

    @Test
    fun `测试字节串编码已知向量`() {
        // h'' -> 0x40
        assertEquals("40", CborEncoder.encode(ByteArray(0)).toHex())
        // 01020304 -> 0x4401020304
        val bytes4 = byteArrayOf(1, 2, 3, 4)
        assertEquals("4401020304", CborEncoder.encode(bytes4).toHex())
    }

    @Test
    fun `测试数组与嵌套结构编码已知向量`() {
        // [] -> 0x80
        assertEquals("80", CborEncoder.encode(emptyList<Any>()).toHex())
        // [1, 2, 3] -> 0x83010203
        assertEquals("83010203", CborEncoder.encode(listOf(1L, 2L, 3L)).toHex())
        // [1, [2, 3], [4, 5]] -> 0x8301820203820405
        val nestedList = listOf(1L, listOf(2L, 3L), listOf(4L, 5L))
        assertEquals("8301820203820405", CborEncoder.encode(nestedList).toHex())
    }

    @Test
    fun `测试映射表 Map 编码已知向量`() {
        // {} -> 0xA0
        assertEquals("A0", CborEncoder.encode(emptyMap<Any, Any>()).toHex())

        // {1: 2} -> 0xA10102
        val map1 = LinkedHashMap<Long, Long>().apply { put(1L, 2L) }
        assertEquals("A10102", CborEncoder.encode(map1).toHex())

        // {3: 2} -> 0xA10302
        val map3 = LinkedHashMap<Long, Long>().apply { put(3L, 2L) }
        assertEquals("A10302", CborEncoder.encode(map3).toHex())

        // {"a": [1, 2]} -> 0xA16161820102
        val nestedMap = LinkedHashMap<String, Any>().apply {
            put("a", listOf(1L, 2L))
        }
        assertEquals("A16161820102", CborEncoder.encode(nestedMap).toHex())
    }

    @Test
    fun `测试流式链式编码器 API`() {
        val bytes = CborEncoder.create()
            .writeMapHeader(2)
            .writeInt(1L)
            .writeTextString("passkey")
            .writeInt(2L)
            .writeByteString(byteArrayOf(0x0A, 0x0B))
            .toByteArray()

        // map 2 pairs: 0xA2, 0x01, 0x67 passkey, 0x02, 0x42 0A0B
        val expected = fromHex("A20167706173736B657902420A0B")
        assertArrayEquals(expected, bytes)
    }
}
