package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.signers.RSADigestSigner
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.Base64

/**
 * 通行密钥密码学引擎与 WebAuthn 签名验证闭环单元测试：
 * 1. 覆盖 ES256 / Ed25519 / RS256 三大主流算法密钥对生成、统一签名与 BouncyCastle 密码学验签闭环；
 * 2. 覆盖 AuthenticatorData 带证明数据 (AT 段) 与无证明数据的二进制规范结构断言；
 * 3. 覆盖 coseKeyFor 动态提取转换与 CBOR 序列化；
 * 4. 覆盖 PasskeyData 与 KDBX 自定义字段双向无损转换。
 */
class PasskeyCryptoEngineTest {

    @Test
    fun `测试 ES256 密钥对生成、签名与 BouncyCastle 验签闭环`() {
        val passkey = PasskeyCryptoEngine.generateEs256KeyPair(
            relyingPartyId = "github.com",
            userName = "alice_dev",
            userDisplayName = "Alice Developer"
        )

        assertEquals("github.com", passkey.relyingPartyId)
        assertEquals("alice_dev", passkey.userName)
        assertEquals("Alice Developer", passkey.userDisplayName)
        assertEquals(PasskeyData.ALGORITHM_ES256, passkey.algorithmId)
        assertTrue(passkey.credentialId.isNotBlank())
        assertTrue(passkey.publicKeyBase64.isNotBlank())
        assertTrue(passkey.privateKey.length > 0)

        // 签名与验签闭环
        val authData = PasskeyCryptoEngine.buildAuthenticatorData("github.com", 0x05, 1)
        val clientDataHash = ByteArray(32) { (it + 1).toByte() }
        val dataToSign = authData + clientDataHash

        // 从私钥 hex 转出原始私钥字节
        val privBytes = BigInteger(passkey.privateKey.readString(), 16).toByteArray()
        val cleanPrivBytes = if (privBytes.size > 32 && privBytes[0] == 0.toByte()) {
            privBytes.copyOfRange(1, privBytes.size)
        } else {
            privBytes
        }

        val signature = PasskeyCryptoEngine.signAssertion(
            algorithmId = PasskeyData.ALGORITHM_ES256,
            privateKeyBytes = cleanPrivBytes,
            dataToSign = dataToSign
        )

        assertNotNull(signature)
        assertEquals(0x30.toByte(), signature[0]) // ASN.1 SEQUENCE

        // 使用 BouncyCastle 原生 ECDSASigner 验证 ECDSA 签名
        val pubBytes = Base64.getDecoder().decode(passkey.publicKeyBase64)
        assertEquals(65, pubBytes.size) // 0x04 || X || Y
        assertEquals(0x04.toByte(), pubBytes[0])

        val ecNamedCurves = SECNamedCurves.getByName("secp256r1")
        val domainParams = ECDomainParameters(ecNamedCurves.curve, ecNamedCurves.g, ecNamedCurves.n, ecNamedCurves.h)
        val point = ecNamedCurves.curve.decodePoint(pubBytes)
        val pubParams = ECPublicKeyParameters(point, domainParams)

        val verifier = ECDSASigner()
        verifier.init(false, pubParams)

        // 反序列化 DER SEQUENCE { r, s }
        val asn1Stream = ASN1InputStream(signature)
        val seq = asn1Stream.readObject() as ASN1Sequence
        val r = (seq.getObjectAt(0) as ASN1Integer).value
        val s = (seq.getObjectAt(1) as ASN1Integer).value

        // 计算 dataToSign 的 SHA-256 摘要
        val digest = SHA256Digest()
        digest.update(dataToSign, 0, dataToSign.size)
        val hash = ByteArray(32)
        digest.doFinal(hash, 0)

        assertTrue("ES256 签名应当被 BouncyCastle 验签成功", verifier.verifySignature(hash, r, s))

        // 测试 coseKeyFor 转换
        val coseKeyBytes = PasskeyCryptoEngine.coseKeyFor(PasskeyData.ALGORITHM_ES256, pubBytes)
        assertTrue(coseKeyBytes.isNotEmpty())
        assertEquals(0xA5.toByte(), coseKeyBytes[0]) // Map of 5
    }

    @Test
    fun `测试 Ed25519 密钥对生成、签名与 BouncyCastle 验签闭环`() {
        val passkey = PasskeyCryptoEngine.generateEd25519KeyPair(
            relyingPartyId = "example.org",
            userName = "charlie",
            userDisplayName = "Charlie"
        )

        assertEquals("example.org", passkey.relyingPartyId)
        assertEquals(PasskeyData.ALGORITHM_ED25519, passkey.algorithmId)

        val pubBytes = Base64.getDecoder().decode(passkey.publicKeyBase64)
        val privBytes = Base64.getDecoder().decode(passkey.privateKey.readString())
        assertEquals(32, pubBytes.size)
        assertEquals(32, privBytes.size)

        val dataToSign = "WebAuthn Ed25519 Assertion Challenge Data".toByteArray(Charsets.UTF_8)

        // 统一签名 API 签名
        val signature = PasskeyCryptoEngine.signAssertion(
            algorithmId = PasskeyData.ALGORITHM_ED25519,
            privateKeyBytes = privBytes,
            dataToSign = dataToSign
        )

        assertEquals(64, signature.size) // Ed25519 签名必须为严格 64 字节

        // 使用 BouncyCastle 的 Ed25519Signer 进行验签
        val pubParams = Ed25519PublicKeyParameters(pubBytes, 0)
        val verifier = Ed25519Signer()
        verifier.init(false, pubParams)
        verifier.update(dataToSign, 0, dataToSign.size)
        assertTrue("Ed25519 签名应当被 BouncyCastle 验签通过", verifier.verifySignature(signature))

        // 测试 coseKeyFor 转换
        val coseKeyBytes = PasskeyCryptoEngine.coseKeyFor(PasskeyData.ALGORITHM_ED25519, pubBytes)
        assertTrue(coseKeyBytes.isNotEmpty())
        assertEquals(0xA4.toByte(), coseKeyBytes[0]) // Map of 4
    }

    @Test
    fun `测试 RS256 密钥对生成、签名与 BouncyCastle 验签闭环`() {
        val passkey = PasskeyCryptoEngine.generateRs256KeyPair(
            relyingPartyId = "enterprise.local",
            userName = "admin",
            userDisplayName = "System Administrator"
        )

        assertEquals("enterprise.local", passkey.relyingPartyId)
        assertEquals(PasskeyData.ALGORITHM_RS256, passkey.algorithmId)

        val pubBytes = Base64.getDecoder().decode(passkey.publicKeyBase64)
        val privBytes = Base64.getDecoder().decode(passkey.privateKey.readString())

        val dataToSign = "Enterprise RS256 WebAuthn Assertion Payload".toByteArray(Charsets.UTF_8)

        val signature = PasskeyCryptoEngine.signAssertion(
            algorithmId = PasskeyData.ALGORITHM_RS256,
            privateKeyBytes = privBytes,
            dataToSign = dataToSign
        )

        assertEquals(256, signature.size) // RSA-2048 签名为 256 字节 (2048 位)

        // 使用 BouncyCastle 的 RSADigestSigner 验签
        val rsaPubKeyParams = PublicKeyFactory.createKey(pubBytes) as RSAKeyParameters
        val verifier = RSADigestSigner(SHA256Digest())
        verifier.init(false, rsaPubKeyParams)
        verifier.update(dataToSign, 0, dataToSign.size)
        assertTrue("RS256 签名应当被 BouncyCastle 验签通过", verifier.verifySignature(signature))

        // 测试 coseKeyFor 转换
        val coseKeyBytes = PasskeyCryptoEngine.coseKeyFor(PasskeyData.ALGORITHM_RS256, pubBytes)
        assertTrue(coseKeyBytes.isNotEmpty())
        assertEquals(0xA4.toByte(), coseKeyBytes[0]) // Map of 4
    }

    @Test
    fun `测试 AuthenticatorData 二进制块格式标准（无证明数据）`() {
        val authData = PasskeyCryptoEngine.buildAuthenticatorData(
            rpId = "webauthn.io",
            flags = 0x05, // UP (0x01) + UV (0x04)
            signCount = 42
        )

        assertEquals(37, authData.size)
        // 验证 flags 字段 (第 32 字节)
        assertEquals(0x05.toByte(), authData[32])
        // 验证 signCount 计数器大端写入
        assertEquals(0.toByte(), authData[33])
        assertEquals(0.toByte(), authData[34])
        assertEquals(0.toByte(), authData[35])
        assertEquals(42.toByte(), authData[36])
    }

    @Test
    fun `测试 AuthenticatorData 二进制块格式标准（含 AT 证明凭据数据）`() {
        val credId = ByteArray(16) { (it + 1).toByte() }
        val dummyCoseKey = byteArrayOf(0xA5.toByte(), 0x01, 0x02)

        val flags = (PasskeyCryptoEngine.FLAG_UP.toInt() or
                PasskeyCryptoEngine.FLAG_UV.toInt() or
                PasskeyCryptoEngine.FLAG_AT.toInt()).toByte()

        val authData = PasskeyCryptoEngine.buildAuthenticatorData(
            rpId = "webauthn.io",
            flags = flags,
            signCount = 100,
            credentialId = credId,
            cosePublicKey = dummyCoseKey
        )

        val expectedSize = 37 + 16 + 2 + credId.size + dummyCoseKey.size
        assertEquals(expectedSize, authData.size)

        // 1. flags 位于索引 32，必须包含 AT (0x40)
        assertEquals(flags, authData[32])
        assertTrue((authData[32].toInt() and PasskeyCryptoEngine.FLAG_AT.toInt()) != 0)

        // 2. signCount = 100 (大端 4 字节，索引 33..36)
        assertEquals(100.toByte(), authData[36])

        // 3. aaguid 16 字节全零 (索引 37..52)
        val aaguidPart = authData.copyOfRange(37, 53)
        assertArrayEquals(PasskeyCryptoEngine.DEFAULT_AAGUID, aaguidPart)

        // 4. credIdLength 2 字节大端 (索引 53..54) -> 16 字节
        assertEquals(0.toByte(), authData[53])
        assertEquals(16.toByte(), authData[54])

        // 5. credId 字节流 (索引 55..70)
        val credIdPart = authData.copyOfRange(55, 55 + credId.size)
        assertArrayEquals(credId, credIdPart)

        // 6. COSE 公钥结尾
        val cosePart = authData.copyOfRange(55 + credId.size, authData.size)
        assertArrayEquals(dummyCoseKey, cosePart)
    }

    @Test
    fun `测试 hex 编码私钥经 ByteArray API 签名可用`() {
        val passkey = PasskeyCryptoEngine.generateEs256KeyPair("example.com", "bob")
        val clientDataHash = ByteArray(32) { (it + 1).toByte() }
        val authData = PasskeyCryptoEngine.buildAuthenticatorData("example.com", 0x01, 1)

        val dataToSign = authData + clientDataHash
        // 私钥以定长 64 字符 hex 存于 ProtectedString；签名一律经 ByteArray 通道（敏感铁律）
        val privBytes = java.math.BigInteger(passkey.privateKey.readString(), 16).toByteArray()
            .let { raw -> if (raw.size > 32 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw }
        try {
            val signature = PasskeyCryptoEngine.signAssertion(
                com.keepasskey.core.model.PasskeyData.ALGORITHM_ES256,
                privBytes,
                dataToSign
            )
            assertNotNull(signature)
            assertTrue(signature.isNotEmpty())
            assertEquals(0x30.toByte(), signature[0])
        } finally {
            java.util.Arrays.fill(privBytes, 0.toByte())
        }
    }

    @Test
    fun `测试 PasskeyData 与 KDBX 自定义字段双向互转`() {
        val passkey = PasskeyCryptoEngine.generateEs256KeyPair(
            relyingPartyId = "login.microsoft.com",
            userName = "admin@ms.com",
            userDisplayName = "MS Admin"
        )

        val customFields = passkey.toCustomFields()
        assertTrue(customFields.size >= 10)

        val restored = PasskeyData.fromCustomFields(customFields)
        assertNotNull(restored)
        assertEquals(passkey.relyingPartyId, restored!!.relyingPartyId)
        assertEquals(passkey.userName, restored.userName)
        assertEquals(passkey.credentialId, restored.credentialId)
        assertEquals(passkey.algorithmId, restored.algorithmId)
        assertEquals(passkey.publicKeyBase64, restored.publicKeyBase64)
        assertEquals(passkey.privateKey.readString(), restored.privateKey.readString())
    }

    // ================= ISSUE-P1-02 生成侧零 String 约束回归 =================

    @Test
    fun `测试 ISSUE-P1-02 ES256 私钥保持定长 64 字符小写 hex 且经受控字节流通道签名可用`() {
        val passkey = PasskeyCryptoEngine.generateEs256KeyPair("p1-02.example", "byte-gen")

        // 1. 文本契约：生成侧改走 CharArray 编码后，私钥驻留文本必须仍为
        //    定长 64 字符小写 hex（等价 String.format("%064x")，既有解析路径不变）
        val privText = passkey.privateKey.useUtf8 { String(it, Charsets.US_ASCII) }
        assertEquals(64, privText.length)
        assertTrue("私钥必须为小写 hex 编码", privText.all { it in '0'..'9' || it in 'a'..'f' })

        // 2. 字节流消费契约：usePrivateKeyBytes 读出即签名（hex 文本字节流形态由签名引擎兼容解析）
        val clientDataHash = ByteArray(32) { (it + 1).toByte() }
        val authData = PasskeyCryptoEngine.buildAuthenticatorData("p1-02.example", 0x01, 1)
        val dataToSign = authData + clientDataHash

        passkey.usePrivateKeyBytes { raw ->
            val signature = PasskeyCryptoEngine.signAssertion(
                PasskeyData.ALGORITHM_ES256, raw, dataToSign
            )
            assertEquals(0x30.toByte(), signature[0]) // ASN.1 SEQUENCE
        }
    }

    @Test
    fun `测试 ISSUE-P1-02 Ed25519 与 RS256 私钥经受控字节流通道签名可用`() {
        // Ed25519：字节流通道交付 Base64 文本字节流（44B），消费侧解码后应还原 32 字节种子
        val ed = PasskeyCryptoEngine.generateEd25519KeyPair("p1-02-ed.example", "byte-ed")
        val dataEd = "P1-02 ed25519 byte channel".toByteArray(Charsets.UTF_8)
        ed.usePrivateKeyBytes { raw ->
            assertEquals(44, raw.size)
            val seed = Base64.getDecoder().decode(raw)
            try {
                assertEquals(32, seed.size)
                val signature = PasskeyCryptoEngine.signAssertion(
                    PasskeyData.ALGORITHM_ED25519, seed, dataEd
                )
                assertEquals(64, signature.size)
            } finally {
                java.util.Arrays.fill(seed, 0.toByte())
            }
        }

        // RS256：字节流通道交付 PKCS#8 DER 的 Base64 文本字节流，消费侧解码后应可签名
        val rs = PasskeyCryptoEngine.generateRs256KeyPair("p1-02-rs.example", "byte-rs")
        val dataRs = "P1-02 rs256 byte channel".toByteArray(Charsets.UTF_8)
        rs.usePrivateKeyBytes { raw ->
            val der = Base64.getDecoder().decode(raw)
            try {
                val signature = PasskeyCryptoEngine.signAssertion(
                    PasskeyData.ALGORITHM_RS256, der, dataRs
                )
                assertEquals(256, signature.size)
            } finally {
                java.util.Arrays.fill(der, 0.toByte())
            }
        }
    }

    // ================= EC 私钥标量范围校验（P2-9 fail-closed） =================

    companion object {
        /** secp256r1 群阶 n */
        private val SECP256R1_N = BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16
        )

        /** BigInteger → 定长 32 字节大端标量（左补零 / 去符号位） */
        private fun toScalar32(value: BigInteger): ByteArray {
            val raw = value.toByteArray()
            val unsigned = if (raw.size == 33 && raw[0] == 0.toByte()) raw.copyOfRange(1, 33) else raw
            require(unsigned.size <= 32) { "标量超出 256 位: $value" }
            val out = ByteArray(32)
            System.arraycopy(unsigned, 0, out, 32 - unsigned.size, unsigned.size)
            return out
        }

        private val sampleDataToSign: ByteArray =
            "P2-9 scalar range validation challenge".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `测试 EC 私钥标量 d=0 被 fail-closed 拒绝`() {
        val zeroScalar = ByteArray(32)
        assertThrows(CryptoException.InvalidKeyException::class.java) {
            PasskeyCryptoEngine.signAssertion(
                PasskeyData.ALGORITHM_ES256, zeroScalar, sampleDataToSign
            )
        }
    }

    @Test
    fun `测试 EC 私钥标量 d=n 被 fail-closed 拒绝`() {
        val nScalar = toScalar32(SECP256R1_N)
        assertThrows(CryptoException.InvalidKeyException::class.java) {
            PasskeyCryptoEngine.signAssertion(
                PasskeyData.ALGORITHM_ES256, nScalar, sampleDataToSign
            )
        }
    }

    @Test
    fun `测试 EC 私钥标量 d大于n 被 fail-closed 拒绝`() {
        val overNScalar = toScalar32(SECP256R1_N.add(BigInteger.ONE))
        assertThrows(CryptoException.InvalidKeyException::class.java) {
            PasskeyCryptoEngine.signAssertion(
                PasskeyData.ALGORITHM_ES256, overNScalar, sampleDataToSign
            )
        }
    }

    @Test
    fun `测试边界标量 d=1 与 d=n-1 签名可用`() {
        // d = 1：合法域下界
        val oneScalar = toScalar32(BigInteger.ONE)
        val sigLower = PasskeyCryptoEngine.signAssertion(
            PasskeyData.ALGORITHM_ES256, oneScalar, sampleDataToSign
        )
        assertEquals(0x30.toByte(), sigLower[0]) // ASN.1 SEQUENCE

        // d = n-1：合法域上界
        val maxScalar = toScalar32(SECP256R1_N.subtract(BigInteger.ONE))
        val sigUpper = PasskeyCryptoEngine.signAssertion(
            PasskeyData.ALGORITHM_ES256, maxScalar, sampleDataToSign
        )
        assertEquals(0x30.toByte(), sigUpper[0])
    }

    @Test
    fun `测试 64 字节 hex 文本形态私钥越界同样被拒绝`() {
        // 全零 hex 文本（64 个 '0' 的 ASCII 字节流）→ 解析为 d=0 → 拒绝
        val zeroHexAscii = "0".repeat(64).toByteArray(Charsets.UTF_8)
        assertThrows(CryptoException.InvalidKeyException::class.java) {
            PasskeyCryptoEngine.signAssertion(
                PasskeyData.ALGORITHM_ES256, zeroHexAscii, sampleDataToSign
            )
        }

        // d ≥ n 的 hex 文本 → 拒绝
        val nHexAscii = SECP256R1_N.toString(16).padStart(64, '0').toByteArray(Charsets.UTF_8)
        assertThrows(CryptoException.InvalidKeyException::class.java) {
            PasskeyCryptoEngine.signAssertion(
                PasskeyData.ALGORITHM_ES256, nHexAscii, sampleDataToSign
            )
        }
    }
}
