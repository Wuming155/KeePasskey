package com.keepasskey.crypto.cose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

/**
 * COSE_Key 结构与确定性 CBOR 编码规范单元测试
 */
class CoseKeyTest {

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    @Test
    fun `测试 EC2 P-256 COSE Key 编码字节布局与顺序`() {
        val x = ByteArray(32) { 0xAA.toByte() }
        val y = ByteArray(32) { 0xBB.toByte() }

        val cbor = CoseKey.ec2P256(x, y)

        // 预期结构:
        // A5 (map of 5)
        // 01 02 (1: 2, kty: EC2)
        // 03 26 (3: -7, alg: ES256)
        // 20 01 (-1: 1, crv: P-256)
        // 21 5820 + 32字节AA (-2: x)
        // 22 5820 + 32字节BB (-3: y)
        val hex = cbor.toHex()
        assertTrue(hex.startsWith("A5010203262001215820"))
        assertTrue(hex.contains("225820"))

        val totalExpectedLen = 1 + 2 + 2 + 2 + (1 + 2 + 32) + (1 + 2 + 32)
        assertEquals(totalExpectedLen, cbor.size)
    }

    @Test
    fun `测试 Ed25519 OKP COSE Key 编码字节布局与顺序`() {
        val pubKey = ByteArray(32) { 0xCC.toByte() }

        val cbor = CoseKey.ed25519(pubKey)

        // 预期结构:
        // A4 (map of 4)
        // 01 01 (1: 1, kty: OKP)
        // 03 27 (3: -8, alg: EdDSA)
        // 20 06 (-1: 6, crv: Ed25519)
        // 21 5820 + 32字节CC (-2: pubKey)
        val hex = cbor.toHex()
        assertTrue(hex.startsWith("A4010103272006215820"))

        val totalExpectedLen = 1 + 2 + 2 + 2 + (1 + 2 + 32)
        assertEquals(totalExpectedLen, cbor.size)
    }

    @Test
    fun `测试 RSA-2048 COSE Key 编码字节布局与符号剥离`() {
        // 模拟带正符号位 0x00 前导字节的 256 字节模数 (共 257 字节)
        val rawN = ByteArray(257) { if (it == 0) 0.toByte() else 0x11.toByte() }
        // 模拟带正符号位 0x00 前导字节的指数 65537 (0x00 0x01 0x00 0x01)
        val rawE = byteArrayOf(0x00, 0x01, 0x00, 0x01)

        val cbor = CoseKey.rsa2048(rawN, rawE)

        // 预期结构 (键集 {1,3,-1,-2} 的编码字节 0x01<0x03<0x20<0x21 恰与插入序一致，
        // 即 RFC 8949 Canonical 键序；-257 为键 3 的值而非键):
        // A4 (map of 4)
        // 01 03 (1: 3, kty: RSA)
        // 03 390100 (3: -257, alg: RS256)
        // 20 590100 + 256 字节 11 (-1: n, 59 0100 代表 2 字节大端长度 256)
        // 21 43 010001 (-2: e, 43 代表 3 字节定长 byte string)
        val hex = cbor.toHex()
        assertTrue(hex.startsWith("A401030339010020590100"))
        assertTrue(hex.endsWith("2143010001"))

        val expectedLen = 1 + 2 + 4 + (1 + 3 + 256) + (1 + 1 + 3)
        assertEquals(expectedLen, cbor.size)
    }
}
