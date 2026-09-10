package com.keepasskey.crypto.cipher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PKCS#7 填充的单元用例（ISSUE-P3-35）。
 *
 * 重点锁定一条真实踩过的缺陷：`unpaddedLength` 必须接受**任意长度（分组整数倍）**的数据
 * 并只校验最后一个分组。早期实现只接受单分组输入，导致「整型解密」在明文超过 16 字节时
 * 一律被判为填充非法（`TwofishNativeParityTest` 的 `len=16` 用例即由此变红）。
 */
class Pkcs7Test {

    @Test
    fun `pad 始终补齐到分组整数倍且严格变长`() {
        for (length in 0..33) {
            val data = ByteArray(length) { it.toByte() }
            val padded = Pkcs7.pad(data)
            assertEquals("len=$length", 0, padded.size % Pkcs7.BLOCK_SIZE)
            assert(padded.size > length)
            // 前缀保持原样
            for (i in 0 until length) {
                assertEquals("len=$length 前缀 $i", data[i], padded[i])
            }
            // 填充字节全部等于填充长度
            val padLen = padded.size - length
            for (i in length until padded.size) {
                assertEquals("len=$length 填充字节", padLen.toByte(), padded[i])
            }
            // 往返（任意长度的分组整数倍数据必须走 unpaddedLength 形态）
            val unpadded = Pkcs7.unpaddedLength(padded)
            assertEquals("len=$length 去填充长度", length, unpadded)
            assertArrayEquals("len=$length 往返", data, padded.copyOf(unpadded))
        }
    }

    @Test
    fun `整块对齐时追加一整个填充块`() {
        val padded = Pkcs7.pad(ByteArray(16))
        assertEquals(32, padded.size)
        for (i in 16 until 32) {
            assertEquals(Pkcs7.BLOCK_SIZE.toByte(), padded[i])
        }
    }

    @Test
    fun `unpaddedLength 支持任意长度的分组整数倍数据`() {
        // 4 个分组：前三组为数据，末组为填充
        val data = ByteArray(48) { (it + 1).toByte() }
        val padded = Pkcs7.pad(data)
        assertEquals(64, padded.size)
        assertEquals(48, Pkcs7.unpaddedLength(padded))

        // 单分组满填充 → 去填充长度为 0
        assertEquals(0, Pkcs7.unpaddedLength(Pkcs7.pad(ByteArray(0))))
        // 恰好一个数据分组 + 满填充块
        assertEquals(16, Pkcs7.unpaddedLength(Pkcs7.pad(ByteArray(16))))
    }

    @Test
    fun `非法填充一律 fail-closed 返回负值`() {
        // 长度非分组整数倍
        assertEquals(-1, Pkcs7.unpaddedLength(ByteArray(0)))
        assertEquals(-1, Pkcs7.unpaddedLength(ByteArray(17)))
        // 填充长度为 0
        val zeroPad = ByteArray(16) { 1 }
        zeroPad[15] = 0
        assertEquals(-1, Pkcs7.unpaddedLength(zeroPad))
        // 填充长度越界（> 分组长度）
        val tooLong = ByteArray(16) { 1 }
        tooLong[15] = 17
        assertEquals(-1, Pkcs7.unpaddedLength(tooLong))
        // 填充字节不一致
        val inconsistent = ByteArray(16) { it.toByte() }
        inconsistent[15] = 4
        inconsistent[14] = 3
        assertEquals(-1, Pkcs7.unpaddedLength(inconsistent))
    }

    @Test
    fun `unpad 单分组形态对非法输入返回 null`() {
        assertNull(Pkcs7.unpad(ByteArray(0)))
        assertNull(Pkcs7.unpad(ByteArray(15)))
        assertNull(Pkcs7.unpad(ByteArray(32)))
        assertEquals(0, Pkcs7.unpad(Pkcs7.pad(ByteArray(0)))?.size)
    }
}
