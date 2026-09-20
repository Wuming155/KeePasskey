package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.model.PasskeyKeyText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * [PasskeyLegacyKeyText] 宿主单测（`ISSUE-P3-213` 的写路径就地迁移面）：
 *
 * 1. **迁移成功路径**：v1 三种驻留形态（16 进制标量 / Base64 种子 / Base64 PKCS#8 DER）
 *    分别重包为 PKCS#8 PEM，且经生产 PEM 解码链路**往返还原为同一把密钥**；
 * 2. **迁移失败路径（fail-safe）**：形态非法 / 与算法不匹配 / 标量越界 / 算法不受支持
 *    一律返回 null——调用方据此放弃迁移、保持原文，绝不写入一份不可用的 PEM；
 * 3. **解码语义**：奇数长度 hex 按无符号解析（等价 `BigInteger(String, 16)`），
 *    两侧空白容忍（历史 `trim()` 语义）。
 */
class PasskeyLegacyKeyTextTest {

    // ── 迁移成功路径 ──

    @Test
    fun `ES256 十六进制标量文本重包为 PEM 并往返还原同一标量`() {
        val hexText = SCALAR_HEX.toByteArray(Charsets.US_ASCII)

        val pemChars = PasskeyLegacyKeyText.legacyTextToPemChars(hexText, PasskeyData.ALGORITHM_ES256)

        assertNotNull("合法 hex 标量必须可迁移", pemChars)
        val ascii = charsToAscii(pemChars!!)
        try {
            assertTrue("迁移产物必须是 PKCS#8 PEM", PasskeyKeyText.isPem(ascii))
            val roundTrip = PasskeyCryptoEngine.decodePemPrivateKeyText(ascii)
            assertNotNull(roundTrip)
            assertEquals(PasskeyData.ALGORITHM_ES256, roundTrip!!.algorithmId)
            assertEquals(SCALAR, BigInteger(1, roundTrip.keyBytes))
        } finally {
            ascii.fill(0)
            pemChars.fill('0')
        }
    }

    @Test
    fun `Ed25519 Base64 种子文本重包为 PEM 并往返还原同一种子`() {
        val seed = ByteArray(32) { (it + 1).toByte() }
        val base64Seed = Base64.getEncoder().encodeToString(seed).toByteArray(Charsets.US_ASCII)

        val pemChars = PasskeyLegacyKeyText.legacyTextToPemChars(base64Seed, PasskeyData.ALGORITHM_ED25519)

        assertNotNull("32 字节种子必须可迁移", pemChars)
        val ascii = charsToAscii(pemChars!!)
        try {
            val roundTrip = PasskeyCryptoEngine.decodePemPrivateKeyText(ascii)
            assertNotNull(roundTrip)
            assertEquals(PasskeyData.ALGORITHM_ED25519, roundTrip!!.algorithmId)
            assertArrayEquals(seed, roundTrip.keyBytes)
        } finally {
            ascii.fill(0)
            pemChars.fill('0')
            seed.fill(0)
        }
    }

    @Test
    fun `RS256 Base64 PKCS#8 DER 文本重包为 PEM 后仍可解码并签名`() {
        val rsaPrivate = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair().private
        val der = rsaPrivate.encoded.copyOf()
        try {
            val base64Der = Base64.getEncoder().encodeToString(der).toByteArray(Charsets.US_ASCII)

            val pemChars = PasskeyLegacyKeyText.legacyTextToPemChars(base64Der, PasskeyData.ALGORITHM_RS256)

            assertNotNull("结构合法的 RSA PKCS#8 DER 必须可迁移", pemChars)
            val ascii = charsToAscii(pemChars!!)
            try {
                val roundTrip = PasskeyCryptoEngine.decodePemPrivateKeyText(ascii)
                assertNotNull(roundTrip)
                assertEquals(PasskeyData.ALGORITHM_RS256, roundTrip!!.algorithmId)
                assertEquals(
                    "迁移后必须仍能真实签发（RS256 = 256 字节 PKCS#1 v1.5）",
                    256,
                    PasskeyCryptoEngine.signAssertionConsumingKey(
                        roundTrip.algorithmId, roundTrip.keyBytes, "migration challenge".toByteArray()
                    ).size
                )
            } finally {
                ascii.fill(0)
                pemChars.fill('0')
            }
        } finally {
            der.fill(0)
        }
    }

    // ── 迁移失败路径（fail-safe：返回 null，绝不抛给调用方） ──

    @Test
    fun `形态与算法不匹配时拒绝迁移`() {
        assertNull(
            "不支持 / 未知的算法标识不得产出 PEM",
            PasskeyLegacyKeyText.legacyTextToPemChars(SCALAR_HEX.toByteArray(Charsets.US_ASCII), 0)
        )
        assertNull(
            "Ed25519 只接受 32 字节种子：16 字节 hex 文本必须拒绝（否则会产出错算法密钥）",
            PasskeyLegacyKeyText.legacyTextToPemChars(
                "0102030405060708090a0b0c0d0e0f10".toByteArray(Charsets.US_ASCII),
                PasskeyData.ALGORITHM_ED25519
            )
        )
        assertNull(
            "RS256 只接受 PKCS#8 DER：裸 hex 标量文本必须拒绝",
            PasskeyLegacyKeyText.legacyTextToPemChars(SCALAR_HEX.toByteArray(Charsets.US_ASCII), PasskeyData.ALGORITHM_RS256)
        )
    }

    @Test
    fun `标量越界与结构非法一律拒绝迁移`() {
        assertNull(
            "全零 hex 标量（d=0）必须拒绝迁移——绝不能写入一份签名时必然失败的 PEM",
            PasskeyLegacyKeyText.legacyTextToPemChars("0".repeat(64).toByteArray(Charsets.US_ASCII), PasskeyData.ALGORITHM_ES256)
        )
        assertNull(
            "非 hex 且非 Base64 的文本必须拒绝迁移",
            PasskeyLegacyKeyText.legacyTextToPemChars("!!not-a-key!!".toByteArray(Charsets.US_ASCII), PasskeyData.ALGORITHM_ES256)
        )
        assertNull(
            "Base64 解出但结构不是 PKCS#8 的字节必须拒绝 RS256 迁移",
            PasskeyLegacyKeyText.legacyTextToPemChars(
                Base64.getEncoder().encodeToString(ByteArray(48) { it.toByte() }).toByteArray(Charsets.US_ASCII),
                PasskeyData.ALGORITHM_RS256
            )
        )
    }

    // ── 解码语义（自 app 层逐字收敛而来，锁定历史行为不变） ──

    @Test
    fun `奇数长度 hex 按无符号解析且 Base64 分支容忍两侧空白`() {
        // "abc" → 0x0abc（左对齐补零半字节，等价 BigInteger("abc", 16)）
        assertArrayEquals(
            byteArrayOf(0x0a, 0xbc.toByte()),
            PasskeyLegacyKeyText.legacyTextToKeyBytes("abc".toByteArray(Charsets.US_ASCII))
        )
        // 两侧空白容忍（历史 trim() 语义，作用于 Base64 分支）
        assertArrayEquals(
            byteArrayOf(0x00, 0x01, 0x02),
            PasskeyLegacyKeyText.legacyTextToKeyBytes(" \r\nAAEC\t ".toByteArray(Charsets.US_ASCII))
        )
        // hex 分支要求**整段**为 hex（v1 写入形态即是如此，无空白）；
        // 带空白的「看似 hex」文本按历史语义走 Base64 分支——此处锁定该口径不被改写
        assertArrayEquals(
            "带空白的 hex 文本走 Base64 分支（自 app 层逐字搬入的既有语义）",
            Base64.getDecoder().decode("0102"),
            PasskeyLegacyKeyText.legacyTextToKeyBytes(" 0102 ".toByteArray(Charsets.US_ASCII))
        )
    }

    private companion object {
        /** 合法 secp256r1 标量（< n）的 64 字符 hex 文本 */
        const val SCALAR_HEX = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        val SCALAR = BigInteger(SCALAR_HEX, 16)

        fun charsToAscii(chars: CharArray): ByteArray = ByteArray(chars.size) { chars[it].code.toByte() }
    }
}
