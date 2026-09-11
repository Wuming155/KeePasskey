package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.cose.CoseKey
import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64

/**
 * 通行密钥 (Passkey / WebAuthn) 密码学计算引擎。
 * 遵循 W3C WebAuthn Level 3 与 FIDO2 规范：
 * 1. 支持三类主流公钥签名算法密钥对生成：
 *    - ES256 (ECDSA P-256 / secp256r1 with SHA-256)；
 *    - Ed25519 (EdDSA OKP 纯净曲线)；
 *    - RS256 (RSASSA-PKCS1-v1_5 with SHA-256 2048-bit)。
 * 2. 导出 COSE_Key 结构与支持证明数据 (Attested Credential Data) 的 AuthenticatorData 二进制块；
 * 3. 统一签名 API，集成确定性 RFC 6979 DSA、Ed25519 原生验签与 RSA-SHA256，所有私钥运算保证敏感内存清理。
 *
 * ISSUE-P1-02（生成侧内存脱敏）：密钥对生成全程**零 String 中间量**——ES256 私钥标量直接从
 * BigInteger 二进制形态编码为 hex [CharArray]，Ed25519/RS256 的 Base64 编码经 [CharArray] 通道
 * 封装，全部中间字节/字符副本用毕即 `fill(0)` 擦除。私钥文本形态的唯一长期持有者是
 * [ProtectedString]（InMemoryCipher 密文驻留），KDBX 受保护字段以文本承载属格式层不可消解边界。
 *
 * 纯结构性下沉：[PasskeyKeyCodec] 承载编解码/解析，[PasskeyAssertionSigner] 承载三类签名实现；
 * 本对象保留生成、装配与统一签名入口，公开 API 与敏感清零点不变。
 */
object PasskeyCryptoEngine {

    // AuthenticatorData Flags 标志位常量 (W3C WebAuthn Section 6.1)
    const val FLAG_UP: Byte = 0x01             // 用户在场 (User Present)
    const val FLAG_UV: Byte = 0x04             // 用户已验证 (User Verified)
    const val FLAG_BE: Byte = 0x08             // 支持备份 (Backup Eligibility)
    const val FLAG_BS: Byte = 0x10             // 已备份状态 (Backup State)
    const val FLAG_AT: Byte = 0x40             // 包含证明凭据数据 (Attested Credential Data Present)
    const val FLAG_ED: Byte = 0x80.toByte()    // 包含扩展数据 (Extension Data Present)

    // 默认自托管 / 虚拟 Authenticator AAGUID (16 字节全零)
    val DEFAULT_AAGUID: ByteArray = ByteArray(16) { 0 }

    private val ecParams: X9ECParameters = SECNamedCurves.getByName("secp256r1")
    private val domainParams = ECDomainParameters(ecParams.curve, ecParams.g, ecParams.n, ecParams.h)
    private val secureRandom = SecureRandom()

    /**
     * 生成全新的 ES256 (ECDSA P-256) 密钥对及凭据数据。
     * 公钥编码格式：未压缩椭圆曲线点 (0x04 || X || Y) Base64。
     * 私钥编码格式：标量大整数 16 进制字符串（封装于 ProtectedString 中）。
     */
    fun generateEs256KeyPair(
        relyingPartyId: String,
        userName: String,
        userHandle: String = "",
        userDisplayName: String = ""
    ): PasskeyData {
        val generator = ECKeyPairGenerator()
        val genParam = ECKeyGenerationParameters(domainParams, secureRandom)
        generator.init(genParam)
        val keyPair = generator.generateKeyPair()

        val priv = keyPair.private as ECPrivateKeyParameters
        val pub = keyPair.public as ECPublicKeyParameters

        val credIdBase64Url = generateRandomCredentialId()
        val pubEncoded = pub.q.getEncoded(false)
        val pubBase64 = Base64.getEncoder().encodeToString(pubEncoded)

        // ISSUE-P1-02（生成侧脱敏）：私钥标量不落 String——直接从 BigInteger 二进制形态编码为
        // 定长 64 字符小写 hex CharArray（标量二进制副本与字符副本用毕即清零），再封装进
        // ProtectedString 密文驻留层供 KDBX 受保护字段存储。
        val privHexChars = PasskeyKeyCodec.scalarToHexChars(priv.d, PasskeyKeyCodec.EC_SCALAR_HEX_CHARS)
        val privateKeyProtected = sealedFromPrivateChars(privHexChars)

        val handle = resolveUserHandle(userHandle)

        return PasskeyData(
            relyingPartyId = relyingPartyId,
            userHandle = handle,
            userName = userName,
            userDisplayName = userDisplayName.ifBlank { userName },
            credentialId = credIdBase64Url,
            algorithmId = PasskeyData.ALGORITHM_ES256,
            publicKeyBase64 = pubBase64,
            privateKey = privateKeyProtected,
            signCount = 0,
            backupEligible = true,
            backupState = true
        )
    }

    /**
     * 生成全新的 Ed25519 (EdDSA) 密钥对及凭据数据。
     * 公钥编码格式：32 字节原始公钥 (Raw Public Key) Base64。
     * 私钥编码格式：32 字节原始私钥种子 (Raw Private Seed) Base64（封装于 ProtectedString 中）。
     */
    fun generateEd25519KeyPair(
        relyingPartyId: String,
        userName: String,
        userHandle: String = "",
        userDisplayName: String = ""
    ): PasskeyData {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()

        val priv = keyPair.private as Ed25519PrivateKeyParameters
        val pub = keyPair.public as Ed25519PublicKeyParameters

        val privEncoded = priv.encoded
        val pubEncoded = pub.encoded
        val pubBase64 = Base64.getEncoder().encodeToString(pubEncoded)

        // ISSUE-P1-02（生成侧脱敏）：Base64 编码结果不落 String，经 CharArray 通道封装进
        // ProtectedString，字符副本与原始种子字节副本用毕即清零。
        val privChars = PasskeyKeyCodec.base64ToChars(privEncoded)
        Arrays.fill(privEncoded, 0.toByte())
        val privateKey = sealedFromPrivateChars(privChars)

        val credIdBase64Url = generateRandomCredentialId()
        val handle = resolveUserHandle(userHandle)

        return PasskeyData(
            relyingPartyId = relyingPartyId,
            userHandle = handle,
            userName = userName,
            userDisplayName = userDisplayName.ifBlank { userName },
            credentialId = credIdBase64Url,
            algorithmId = PasskeyData.ALGORITHM_ED25519,
            publicKeyBase64 = pubBase64,
            privateKey = privateKey,
            signCount = 0,
            backupEligible = true,
            backupState = true
        )
    }

    /**
     * 生成全新的 RS256 (RSASSA-PKCS1-v1_5 2048 位) 密钥对及凭据数据。
     * 公钥编码格式：X.509 SubjectPublicKeyInfo DER Base64。
     * 私钥编码格式：PKCS#8 PrivateKeyInfo DER Base64（封装于 ProtectedString 中）。
     */
    fun generateRs256KeyPair(
        relyingPartyId: String,
        userName: String,
        userHandle: String = "",
        userDisplayName: String = ""
    ): PasskeyData {
        val generator = RSAKeyPairGenerator()
        // P3-13 整改（TASK-29）：素数确定性参数 certainty 由 12 提升至 80——
        // 原值下伪素数（False-prime）漏检概率约 1/2^12，远低于工业要求；80 对齐
        // BouncyCastle 官方示例与主流密码库默认（单素数误判概率 ≤ 2^-80），
        // 生成耗时可接受（Miller-Rabin 轮数增加对 2048 位密钥仅为毫秒级）。
        val rsaGenParam = RSAKeyGenerationParameters(
            BigInteger.valueOf(65537),
            secureRandom,
            2048,
            80
        )
        generator.init(rsaGenParam)
        val keyPair = generator.generateKeyPair()

        val priv = keyPair.private as RSAPrivateCrtKeyParameters
        val pub = keyPair.public as RSAKeyParameters

        val privInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(priv)
        val pubInfo = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(pub)

        val privEncoded = privInfo.encoded
        val pubEncoded = pubInfo.encoded

        val pubBase64 = Base64.getEncoder().encodeToString(pubEncoded)

        // ISSUE-P1-02（生成侧脱敏）：PKCS#8 DER 的 Base64 编码不落 String，经 CharArray 通道
        // 封装进 ProtectedString，字符副本与 DER 中间副本用毕即清零。
        val privChars = PasskeyKeyCodec.base64ToChars(privEncoded)
        Arrays.fill(privEncoded, 0.toByte())
        val privateKey = sealedFromPrivateChars(privChars)

        val credIdBase64Url = generateRandomCredentialId()
        val handle = resolveUserHandle(userHandle)

        return PasskeyData(
            relyingPartyId = relyingPartyId,
            userHandle = handle,
            userName = userName,
            userDisplayName = userDisplayName.ifBlank { userName },
            credentialId = credIdBase64Url,
            algorithmId = PasskeyData.ALGORITHM_RS256,
            publicKeyBase64 = pubBase64,
            privateKey = privateKey,
            signCount = 0,
            backupEligible = true,
            backupState = true
        )
    }

    /**
     * 统一 Passkey 认证断言签名 API。
     * 支持 ES256 (-7)、Ed25519 (-8) 与 RS256 (-257)；执行完毕后自动清零临时敏感密钥缓冲。
     *
     * @param algorithmId COSE 算法标识
     * @param privateKeyBytes 承载私钥材料的字节流（原始私钥标量、种子或 PKCS#8 DER 编码）
     * @param dataToSign 待签名原始字节流 (通常为 authenticatorData || clientDataHash)
     * @return 遵循 WebAuthn 规范的签名产物（ES256 输出 ASN.1 DER，Ed25519 输出 64B raw，RS256 输出 256B PKCS1-v1_5）
     */
    fun signAssertion(algorithmId: Int, privateKeyBytes: ByteArray, dataToSign: ByteArray): ByteArray {
        val workingKey = privateKeyBytes.clone()
        try {
            return when (algorithmId) {
                PasskeyData.ALGORITHM_ES256 -> PasskeyAssertionSigner.signEs256(workingKey, dataToSign)
                PasskeyData.ALGORITHM_ED25519 -> PasskeyAssertionSigner.signEd25519(workingKey, dataToSign)
                PasskeyData.ALGORITHM_RS256 -> PasskeyAssertionSigner.signRs256(workingKey, dataToSign)
                else -> throw CryptoException.InvalidKeyException("不支持的 Passkey 签名算法标识: $algorithmId")
            }
        } finally {
            Arrays.fill(workingKey, 0.toByte())
        }
    }

    /**
     * 构建标准 AuthenticatorData 二进制块（无证明凭据数据）
     */
    fun buildAuthenticatorData(
        rpId: String,
        flags: Byte,
        signCount: Int
    ): ByteArray {
        return buildAuthenticatorData(rpId, flags, signCount, null, null)
    }

    /**
     * 构建包含可选证明凭据数据 (Attested Credential Data) 的 AuthenticatorData 二进制块。
     * 当 flags 包含 FLAG_AT (0x40) 时，自动组装：
     * aaguid (16B) + credentialIdLength (2B 大端) + credentialId + COSE 公钥 CBOR。
     */
    fun buildAuthenticatorData(
        rpId: String,
        flags: Byte,
        signCount: Int,
        credentialId: ByteArray?,
        cosePublicKey: ByteArray?,
        aaguid: ByteArray = DEFAULT_AAGUID
    ): ByteArray {
        val sha256 = SHA256Digest()
        val rpIdBytes = rpId.toByteArray(Charsets.UTF_8)
        sha256.update(rpIdBytes, 0, rpIdBytes.size)
        val rpIdHash = ByteArray(32)
        sha256.doFinal(rpIdHash, 0)

        val out = ByteArrayOutputStream()
        // 1. rpIdHash (32 字节)
        out.write(rpIdHash)
        // 2. flags (1 字节)
        out.write(flags.toInt() and 0xFF)
        // 3. signCount (4 字节大端整数)
        out.write((signCount ushr 24) and 0xFF)
        out.write((signCount ushr 16) and 0xFF)
        out.write((signCount ushr 8) and 0xFF)
        out.write(signCount and 0xFF)

        // 4. Attested Credential Data 段 (仅当 flags 具有 FLAG_AT 时写入)
        val hasAttestedData = (flags.toInt() and FLAG_AT.toInt()) != 0
        if (hasAttestedData) {
            requireNotNull(credentialId) { "flags 声明 AT (0x40) 时 credentialId 不能为空" }
            requireNotNull(cosePublicKey) { "flags 声明 AT (0x40) 时 cosePublicKey 不能为空" }
            require(credentialId.size <= 0xFFFF) { "credentialId 长度超出 16 位整数上限: ${credentialId.size}" }

            // 4.1 aaguid (16 字节)
            val finalAaguid = if (aaguid.size == 16) aaguid else DEFAULT_AAGUID
            out.write(finalAaguid)

            // 4.2 credentialIdLength (2 字节大端整数)
            out.write((credentialId.size ushr 8) and 0xFF)
            out.write(credentialId.size and 0xFF)

            // 4.3 credentialId
            out.write(credentialId)

            // 4.4 credentialPublicKey (COSE_Key CBOR)
            out.write(cosePublicKey)
        }

        return out.toByteArray()
    }

    /**
     * 根据算法标识与原始公钥字节流组装对应的 COSE_Key CBOR 二进制结构。
     * 供上层构建 attestationObject 时直接调用。
     *
     * @param algorithmId COSE 算法标识
     * @param publicKeyBytes 公钥数据（EC 支持 65B 未压缩点/64B raw/SPKI；Ed25519 支持 32B raw/SPKI；RSA 支持 SPKI 或 ASN.1 RSAPublicKey）
     */
    fun coseKeyFor(algorithmId: Int, publicKeyBytes: ByteArray): ByteArray {
        return when (algorithmId) {
            PasskeyData.ALGORITHM_ES256 -> {
                val (x, y) = PasskeyKeyCodec.extractEcPoint(publicKeyBytes)
                CoseKey.ec2P256(x, y)
            }
            PasskeyData.ALGORITHM_ED25519 -> {
                val rawPub = PasskeyKeyCodec.extractEd25519PublicKey(publicKeyBytes)
                CoseKey.ed25519(rawPub)
            }
            PasskeyData.ALGORITHM_RS256 -> {
                val (n, e) = PasskeyKeyCodec.extractRsaModulusAndExponent(publicKeyBytes)
                CoseKey.rsa2048(n, e)
            }
            else -> throw CryptoException.InvalidKeyException("不支持的 COSE Key 算法标识: $algorithmId")
        }
    }

    // ================= 私有实现与辅助工具 =================

    /**
     * 以 [CharArray] 承载的私钥文本封装受保护字段（InMemoryCipher 密文驻留），
     * 封装完成后字符副本立即清零。生成侧统一出口：私钥材料在此之后仅以
     * [ProtectedString] 形态存活，明文仅存活于受控读取瞬间。
     */
    private fun sealedFromPrivateChars(chars: CharArray): ProtectedString {
        try {
            return ProtectedString(chars, isProtected = true)
        } finally {
            Arrays.fill(chars, '0')
        }
    }

    private fun generateRandomCredentialId(): String {
        val credIdBytes = ByteArray(32)
        secureRandom.nextBytes(credIdBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(credIdBytes)
    }

    private fun resolveUserHandle(userHandle: String): String {
        return if (userHandle.isBlank()) {
            val handleBytes = ByteArray(16)
            secureRandom.nextBytes(handleBytes)
            Base64.getUrlEncoder().withoutPadding().encodeToString(handleBytes)
        } else {
            userHandle
        }
    }
}
