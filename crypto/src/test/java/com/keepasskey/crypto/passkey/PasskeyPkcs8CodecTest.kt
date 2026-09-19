package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.model.PasskeyKeyText
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
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

    /**
     * `ISSUE-P2-211` 形状守卫：生成侧 Ed25519 必须是 **RFC 8410 的 `version = 0`
     * `PrivateKeyInfo`**（恰 3 个成员、无 `publicKey` / `attributes`）。
     *
     * 为什么必须钉死到字节级：BC 的 `PrivateKeyInfoFactory` 会产出 RFC 5958
     * `OneAsymmetricKey`（`version = 1` + `publicKey [1]`），而 **OpenSSL 系实现
     * （含 KeePassXC）拒收该形态**——实测 `cryptography` 抛 `ASN.1 parsing error`，
     * 隔离实验证明「只要 version=1 即被拒」。该缺陷此前靠「自家 writer → 自家 reader」
     * 长期不可见，故这里以结构断言把它钉成可执行契约（回退即红）。
     */
    @Test
    fun `Ed25519 生成侧必须是 RFC 8410 的 version 0 三成员 PrivateKeyInfo`() {
        val seed = ByteArray(32) { it.toByte() }
        val pemChars = PasskeyPkcs8Codec.encodeEd25519ToPem(seed)
        val der = PasskeyKeyText.pemToDer(charsToAscii(pemChars))
        try {
            val top = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der))
            assertEquals(
                "RFC 8410：Ed25519 PrivateKeyInfo 恰含 version / AlgorithmIdentifier / privateKey 三个成员",
                3,
                top.size()
            )
            assertEquals(
                "Ed25519 私钥必须写 version=0（version=1 的 OneAsymmetricKey 会被 OpenSSL 系实现拒收）",
                0,
                ASN1Integer.getInstance(top.getObjectAt(0)).value.intValueExact()
            )

            val algId = ASN1Sequence.getInstance(top.getObjectAt(1))
            assertEquals("AlgorithmIdentifier 的 parameters 必须缺省（RFC 8410 §3）", 1, algId.size())
            assertEquals(
                "AlgorithmIdentifier 必须是 id-Ed25519",
                ED25519_OID,
                ASN1ObjectIdentifier.getInstance(algId.getObjectAt(0)).id
            )

            // privateKey 为 OCTET STRING，其内容又是 CurvePrivateKey（OCTET STRING），内层即 32 字节种子
            val curvePrivateKey = ASN1OctetString.getInstance(top.getObjectAt(2)).octets
            val inner = ASN1OctetString.getInstance(ASN1Primitive.fromByteArray(curvePrivateKey)).octets
            assertArrayEquals("内层 CurvePrivateKey 必须就是原种子", seed, inner)

            assertEquals("version=0 的最小形态 DER 总长恒为 48 字节", DER_TOTAL_BYTES, der!!.size)
        } finally {
            der?.fill(0)
            pemChars.fill('0')
            seed.fill(0)
        }
    }

    /**
     * 存量兼容（`ISSUE-P2-211` 验收标准 ②）：旧版本写出的 **v1 `OneAsymmetricKey`**
     * 形态必须继续被解码接受——否则既有库中的 Ed25519 条目会**无法断言**。
     * 这里直接以 BC 工厂方法现造该形态（即修复前的生产输出）作为回归输入。
     */
    @Test
    fun `旧版 v1 OneAsymmetricKey 形态的 Ed25519 私钥必须继续可解码`() {
        val seed = ByteArray(32) { (0xF0 - it).toByte() }
        val legacyDer = PrivateKeyInfoFactory.createPrivateKeyInfo(
            org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        ).encoded
        try {
            assertEquals(
                "回归输入必须是 v1 形态（否则本用例测不到存量兼容路径）",
                1,
                ASN1Integer.getInstance(
                    ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(legacyDer)).getObjectAt(0)
                ).value.intValueExact()
            )
            val decoded = PasskeyPkcs8Codec.derToSigningKey(legacyDer)
            assertEquals(PasskeyData.ALGORITHM_ED25519, decoded.algorithmId)
            assertArrayEquals(seed, decoded.keyBytes)
        } finally {
            legacyDer.fill(0)
            seed.fill(0)
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

    private companion object {
        /** id-Ed25519（RFC 8410 §3） */
        const val ED25519_OID = "1.3.101.112"

        /**
         * RFC 8410 `version = 0` 最小形态的 DER 总长：
         * 外层 `30 2e`（2）+ version `02 01 00`（3）+ AlgorithmIdentifier（7）
         * + privateKey `04 22`（2）+ CurvePrivateKey `04 20`（2）+ 种子（32）= **48**
         * ⇒ 外层内容长度 46（`0x2e`，即 OpenSSL 产出的 `302e020100300506032b6570…` 同形）。
         */
        const val DER_TOTAL_BYTES = 48
    }

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
