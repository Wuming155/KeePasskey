package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.model.PasskeyKeyText
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.spec.ECGenParameterSpec

/**
 * [PasskeyPkcs8Codec] 宿主单测（KeePassXC / KeePassDX 互操作面）：
 *
 * 1. **解码方向**：真实 BC `KeyPairGenerator` 产出的 PKCS#8 DER（等价于 KeePassXC 写入
 *    `KPEX_PASSKEY_PRIVATE_KEY_PEM` 的 DER 内容）必须被逐算法还原为签名侧可消费的字节；
 * 2. **编码方向**：本仓生成侧产出的 PEM 与解码方向**互为往返**（encode → decode → 原标量/种子）；
 * 3. **负向**：垃圾字节、非 secp256r1 曲线、裸 SEC1 结构一律 fail-closed。
 */
class PasskeyPkcs8CodecTest {

    @Before
    fun setUp() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    // ── ES256 ──

    @Test
    fun `ES256 PKCS#8 解码为 32 字节定长标量且与原私钥一致`() {
        val kg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        kg.initialize(ECGenParameterSpec("secp256r1"))
        val priv = kg.generateKeyPair().private

        val secret = PasskeyPkcs8Codec.derToSigningKey(priv.pkcs8Der())

        assertEquals(PasskeyData.ALGORITHM_ES256, secret.algorithmId)
        assertEquals(32, secret.keyBytes.size)
        // 与原私钥标量逐位一致
        val expectedD = (priv as org.bouncycastle.jce.interfaces.ECPrivateKey).d
        assertEquals(expectedD, BigInteger(1, secret.keyBytes))
    }

    @Test
    fun `ES256 生成侧 PEM 与解码方向互为往返`() {
        val kg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        kg.initialize(ECGenParameterSpec("secp256r1"))
        val expectedD = (kg.generateKeyPair().private as org.bouncycastle.jce.interfaces.ECPrivateKey).d

        val pemChars = PasskeyPkcs8Codec.encodeEcToPem(expectedD)
        try {
            assertTrue("生成侧必须产出 PKCS#8 PEM", PasskeyKeyText.isPem(charsToAscii(pemChars)))
            val decoded = PasskeyPkcs8Codec.pemCharsToSigningKey(pemChars)
            assertEquals(PasskeyData.ALGORITHM_ES256, decoded.algorithmId)
            assertEquals(32, decoded.keyBytes.size)
            assertEquals(expectedD, BigInteger(1, decoded.keyBytes))
        } finally {
            pemChars.fill('0')
        }
    }

    @Test
    fun `ES256 命名曲线 OID 必须被写出（KeePassXC 可读的必要条件）`() {
        val pemChars = PasskeyPkcs8Codec.encodeEcToPem(BigInteger.valueOf(12345))
        val der = PasskeyKeyText.pemToDer(charsToAscii(pemChars))
        try {
            // 1.2.840.10045.3.1.7（secp256r1）的 DER 编码：06 08 2A 86 48 CE 3D 03 01 07
            val namedCurveOid = byteArrayOf(
                0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(),
                0x3D, 0x03, 0x01, 0x07
            )
            assertTrue("EC PKCS#8 必须内联命名曲线 OID（不得内联显式曲线参数）", der!!.containsSeq(namedCurveOid))
        } finally {
            der?.fill(0)
            pemChars.fill('0')
        }
    }

    // ── Ed25519 ──

    @Test
    fun `Ed25519 PKCS#8 解码为 32 字节种子且往来可复原`() {
        val kg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val priv = kg.generateKeyPair().private

        val secret = PasskeyPkcs8Codec.derToSigningKey(priv.pkcs8Der())
        assertEquals(PasskeyData.ALGORITHM_ED25519, secret.algorithmId)
        assertEquals(32, secret.keyBytes.size)
        assertArrayEquals(priv.extractEd25519Seed(), secret.keyBytes)

        // 生成侧同构：种子 → PEM → 解码回种子
        val pemChars = PasskeyPkcs8Codec.encodeEd25519ToPem(secret.keyBytes)
        try {
            val roundTrip = PasskeyPkcs8Codec.pemCharsToSigningKey(pemChars)
            assertEquals(PasskeyData.ALGORITHM_ED25519, roundTrip.algorithmId)
            assertArrayEquals(secret.keyBytes, roundTrip.keyBytes)
        } finally {
            pemChars.fill('0')
        }
    }

    // ── RS256 ──

    @Test
    fun `RS256 PKCS#8 解码后与原 DER 逐字节一致且往来可复原`() {
        val kg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        kg.initialize(2048)
        val priv = kg.generateKeyPair().private
        val der = priv.pkcs8Der()

        val secret = PasskeyPkcs8Codec.derToSigningKey(der)
        assertEquals(PasskeyData.ALGORITHM_RS256, secret.algorithmId)
        assertArrayEquals("RS256 交付整段 PKCS#8 DER", der, secret.keyBytes)
        assertEquals(256, PasskeyCryptoEngine.signAssertion(
            PasskeyData.ALGORITHM_RS256, secret.keyBytes, "payload".toByteArray()
        ).size)

        // 生成侧同构：参数 → PEM → 解码回等价 DER（同一把密钥）
        val pemChars = PasskeyPkcs8Codec.encodeToPem(PrivateKeyFactory.createKey(der))
        try {
            val roundTrip = PasskeyPkcs8Codec.pemCharsToSigningKey(pemChars)
            assertEquals(PasskeyData.ALGORITHM_RS256, roundTrip.algorithmId)
            assertEquals(2048, readRsaModulusBitLength(roundTrip.keyBytes))
        } finally {
            der.fill(0)
            pemChars.fill('0')
        }
    }

    // ── 负向：fail-closed 边界 ──

    @Test
    fun `垃圾字节流 fail-closed 拒绝`() {
        val garbage = ByteArray(48) { it.toByte() }
        try {
            PasskeyPkcs8Codec.derToSigningKey(garbage)
            fail("垃圾字节流必须被拒绝")
        } catch (_: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun `不支持曲线 P-384 fail-closed 拒绝`() {
        val kg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        kg.initialize(ECGenParameterSpec("secp384r1"))
        val priv = kg.generateKeyPair().private
        try {
            PasskeyPkcs8Codec.derToSigningKey(priv.pkcs8Der())
            fail("非 secp256r1 曲线必须被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("曲线"))
        }
    }

    @Test
    fun `非 PKCS#8 的 EC SEC1 裸结构 fail-closed 拒绝`() {
        val kg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        kg.initialize(ECGenParameterSpec("secp256r1"))
        val priv = kg.generateKeyPair().private as org.bouncycastle.jce.interfaces.ECPrivateKey
        val bareSec1 = org.bouncycastle.asn1.DERSequence(
            org.bouncycastle.asn1.ASN1EncodableVector().apply {
                add(org.bouncycastle.asn1.ASN1Integer(1))
                add(org.bouncycastle.asn1.DEROctetString(priv.d.toByteArray()))
            }
        ).encoded
        try {
            PasskeyPkcs8Codec.derToSigningKey(bareSec1)
            fail("裸 SEC1 结构（无 PKCS#8 包装）必须被拒绝")
        } catch (_: IllegalArgumentException) {
            // 预期：顶层结构不是合法 PrivateKeyInfo
        }
    }

    @Test
    fun `非 PEM 文本 fail-closed 拒绝`() {
        try {
            PasskeyPkcs8Codec.pemCharsToSigningKey("not-a-pem".toCharArray())
            fail("非 PEM 文本必须被拒绝")
        } catch (_: IllegalArgumentException) {
            // 预期
        }
    }

    // ── 测试辅助 ──

    private fun PrivateKey.pkcs8Der(): ByteArray = encoded.copyOf()

    private fun PrivateKey.extractEd25519Seed(): ByteArray {
        val edPriv = this as java.security.interfaces.EdECPrivateKey
        val bytes = edPriv.bytes.orElseThrow { IllegalStateException("Ed25519 私钥未暴露种子") }
        return bytes.copyOf()
    }

    private fun charsToAscii(chars: CharArray): ByteArray = ByteArray(chars.size) { chars[it].code.toByte() }

    private fun ByteArray.containsSeq(pattern: ByteArray): Boolean {
        outer@ for (start in 0..(size - pattern.size)) {
            for (i in pattern.indices) if (this[start + i] != pattern[i]) continue@outer
            return true
        }
        return false
    }

    /** 从 PKCS#8 包裹的 RSAPrivateKey DER 读模数位长（结构健全性的粗检） */
    private fun readRsaModulusBitLength(pkcs8Der: ByteArray): Int {
        org.bouncycastle.asn1.ASN1InputStream(pkcs8Der).use { input ->
            val info = org.bouncycastle.asn1.ASN1Sequence.getInstance(input.readObject())
            val inner = org.bouncycastle.asn1.ASN1OctetString.getInstance(info.getObjectAt(2)).octets
            org.bouncycastle.asn1.ASN1InputStream(inner).use { innerInput ->
                val rsa = org.bouncycastle.asn1.ASN1Sequence.getInstance(innerInput.readObject())
                val modulus = org.bouncycastle.asn1.ASN1Integer.getInstance(rsa.getObjectAt(1)).value
                return modulus.bitLength()
            }
        }
    }
}
