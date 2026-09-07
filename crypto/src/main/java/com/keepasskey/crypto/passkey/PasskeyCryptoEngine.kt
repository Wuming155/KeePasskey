package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.cose.CoseKey
import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
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
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.crypto.signers.RSADigestSigner
import org.bouncycastle.crypto.util.PrivateKeyFactory
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

        // 私钥标量定长 64 字符 hex（大数值左补零），封装进 ProtectedString 供 KDBX 受保护字段存储。
        // KDBX 受保护字段以字符串承载是格式层不可消解的边界——字节数组与 BigInteger 中间量
        // 均不落 String 且在写入 ProtectedString 后立即废弃，编码 String 存活期即 ProtectedString 存活期。
        val privHex = String.format("%064x", priv.d)
        val privateKeyProtected = ProtectedString(privHex, isProtected = true)

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
        val privBase64 = Base64.getEncoder().encodeToString(privEncoded)
        val pubBase64 = Base64.getEncoder().encodeToString(pubEncoded)

        Arrays.fill(privEncoded, 0.toByte())

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
            privateKey = ProtectedString(privBase64, isProtected = true),
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
        val rsaGenParam = RSAKeyGenerationParameters(
            BigInteger.valueOf(65537),
            secureRandom,
            2048,
            12
        )
        generator.init(rsaGenParam)
        val keyPair = generator.generateKeyPair()

        val priv = keyPair.private as RSAPrivateCrtKeyParameters
        val pub = keyPair.public as RSAKeyParameters

        val privInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(priv)
        val pubInfo = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(pub)

        val privEncoded = privInfo.encoded
        val pubEncoded = pubInfo.encoded

        val privBase64 = Base64.getEncoder().encodeToString(privEncoded)
        val pubBase64 = Base64.getEncoder().encodeToString(pubEncoded)

        Arrays.fill(privEncoded, 0.toByte())

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
            privateKey = ProtectedString(privBase64, isProtected = true),
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
                PasskeyData.ALGORITHM_ES256 -> signEs256(workingKey, dataToSign)
                PasskeyData.ALGORITHM_ED25519 -> signEd25519(workingKey, dataToSign)
                PasskeyData.ALGORITHM_RS256 -> signRs256(workingKey, dataToSign)
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
                val (x, y) = extractEcPoint(publicKeyBytes)
                CoseKey.ec2P256(x, y)
            }
            PasskeyData.ALGORITHM_ED25519 -> {
                val rawPub = extractEd25519PublicKey(publicKeyBytes)
                CoseKey.ed25519(rawPub)
            }
            PasskeyData.ALGORITHM_RS256 -> {
                val (n, e) = extractRsaModulusAndExponent(publicKeyBytes)
                CoseKey.rsa2048(n, e)
            }
            else -> throw CryptoException.InvalidKeyException("不支持的 COSE Key 算法标识: $algorithmId")
        }
    }

    // ================= 私有实现与辅助工具 =================

    private fun signEs256(privateKeyBytes: ByteArray, dataToSign: ByteArray): ByteArray {
        val privKeyParams = parseEcPrivateKey(privateKeyBytes)

        val digest = SHA256Digest()
        digest.update(dataToSign, 0, dataToSign.size)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)

        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, privKeyParams)
        val components = signer.generateSignature(hash)
        val r = components[0]
        val s = components[1]

        return encodeDerSignature(r, s)
    }

    private fun signEd25519(privateKeyBytes: ByteArray, dataToSign: ByteArray): ByteArray {
        val privParam = if (privateKeyBytes.size == 32) {
            Ed25519PrivateKeyParameters(privateKeyBytes, 0)
        } else {
            PrivateKeyFactory.createKey(privateKeyBytes) as Ed25519PrivateKeyParameters
        }

        val signer = Ed25519Signer()
        signer.init(true, privParam)
        signer.update(dataToSign, 0, dataToSign.size)
        return signer.generateSignature()
    }

    private fun signRs256(privateKeyBytes: ByteArray, dataToSign: ByteArray): ByteArray {
        val privKey = PrivateKeyFactory.createKey(privateKeyBytes) as RSAKeyParameters
        val signer = RSADigestSigner(SHA256Digest())
        signer.init(true, privKey)
        signer.update(dataToSign, 0, dataToSign.size)
        return signer.generateSignature()
    }

    /**
     * 解析 EC (ES256/P-256) 私钥字节流为签名参数。
     * 兼容三种输入形态：32 字节原始标量 / 64 字节 hex 文本字节流 / PKCS#8 DER（失败回退 hex 文本）。
     *
     * P2-9 整改：标量必须满足 d ∈ [1, n-1]（SEC1 §3.2 私钥有效域），越界（含 d=0 / d≥n）
     * 一律 fail-closed 抛出本模块类型化 [CryptoException.InvalidKeyException]——杜绝全零字节流
     * 等病态输入生成非法私钥参与签名运算。显式范围校验为权威检查点，库层 IAE 统一归一为同一异常类型。
     */
    private fun parseEcPrivateKey(bytes: ByteArray): ECPrivateKeyParameters {
        val privKey = when {
            bytes.size == 32 -> {
                newEcPrivateKey(BigInteger(1, bytes))
            }
            bytes.size == 64 -> {
                // 兼容 hex 字符串对应的 ASCII 字节流
                try {
                    newEcPrivateKey(BigInteger(String(bytes, Charsets.UTF_8), 16))
                } catch (e: CryptoException.InvalidKeyException) {
                    throw e
                } catch (e: Exception) {
                    newEcPrivateKey(BigInteger(1, bytes))
                }
            }
            else -> {
                try {
                    val keyParam = PrivateKeyFactory.createKey(bytes) as ECPrivateKeyParameters
                    keyParam
                } catch (e: Exception) {
                    try {
                        // 回退尝试当作 UTF-8 hex 文本
                        newEcPrivateKey(BigInteger(String(bytes, Charsets.UTF_8), 16))
                    } catch (e2: CryptoException.InvalidKeyException) {
                        throw e2
                    } catch (e2: Exception) {
                        throw CryptoException.InvalidKeyException("无法从字节流解析 EC 私钥（所有形态均失败）", e2)
                    }
                }
            }
        }
        // 权威检查点：显式标量范围校验（不依赖库层构造器的行为）
        validateEcScalarRange(privKey.d)
        return privKey
    }

    /**
     * 构造 EC 私钥参数；库层对标量越界抛出的 IllegalArgumentException 统一归一为
     * 本模块类型化的 [CryptoException.InvalidKeyException]（fail-closed，不作任何回退放行）。
     */
    private fun newEcPrivateKey(d: BigInteger): ECPrivateKeyParameters {
        return try {
            ECPrivateKeyParameters(d, domainParams)
        } catch (e: IllegalArgumentException) {
            throw CryptoException.InvalidKeyException("EC 私钥标量越界：d 须满足 [1, n-1]，实际值不合法，已拒绝签名运算", e)
        }
    }

    /**
     * EC 私钥标量有效性校验：d ∈ [1, n-1]，越界 fail-closed。
     */
    private fun validateEcScalarRange(d: BigInteger) {
        if (d.signum() < 1 || d >= ecParams.n) {
            throw CryptoException.InvalidKeyException(
                "EC 私钥标量越界：d 须满足 [1, n-1]，实际值不合法，已拒绝签名运算"
            )
        }
    }

    private fun extractEcPoint(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        return when {
            bytes.size == 65 && bytes[0] == 0x04.toByte() -> {
                val x = bytes.copyOfRange(1, 33)
                val y = bytes.copyOfRange(33, 65)
                Pair(x, y)
            }
            bytes.size == 64 -> {
                val x = bytes.copyOfRange(0, 32)
                val y = bytes.copyOfRange(32, 64)
                Pair(x, y)
            }
            else -> {
                // 尝试解析 X.509 SubjectPublicKeyInfo DER
                val spki = SubjectPublicKeyInfo.getInstance(bytes)
                val pointBytes = spki.publicKeyData.bytes
                if (pointBytes.size == 65 && pointBytes[0] == 0x04.toByte()) {
                    Pair(pointBytes.copyOfRange(1, 33), pointBytes.copyOfRange(33, 65))
                } else {
                    throw CryptoException.InvalidKeyException("无法从公钥数据解析 EC 坐标点: 大小=${bytes.size}")
                }
            }
        }
    }

    private fun extractEd25519PublicKey(bytes: ByteArray): ByteArray {
        return when (bytes.size) {
            32 -> bytes
            else -> {
                val spki = SubjectPublicKeyInfo.getInstance(bytes)
                val raw = spki.publicKeyData.bytes
                require(raw.size == 32) { "从 SPKI 提取的 Ed25519 公钥不是 32 字节: ${raw.size}" }
                raw
            }
        }
    }

    private fun extractRsaModulusAndExponent(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        val rsaPub = try {
            val spki = SubjectPublicKeyInfo.getInstance(bytes)
            RSAPublicKey.getInstance(spki.parsePublicKey())
        } catch (e: Exception) {
            ASN1InputStream(bytes).use { stream ->
                RSAPublicKey.getInstance(stream.readObject())
            }
        }
        return Pair(rsaPub.modulus.toByteArray(), rsaPub.publicExponent.toByteArray())
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

    /**
     * 将 R, S 组装为 ASN.1 DER 编码的 ECDSA 签名
     */
    private fun encodeDerSignature(r: BigInteger, s: BigInteger): ByteArray {
        val rBytes = r.toByteArray()
        val sBytes = s.toByteArray()

        val innerLen = 2 + rBytes.size + 2 + sBytes.size
        val der = ByteArray(2 + innerLen)

        der[0] = 0x30 // SEQUENCE
        der[1] = innerLen.toByte()
        der[2] = 0x02 // INTEGER
        der[3] = rBytes.size.toByte()
        System.arraycopy(rBytes, 0, der, 4, rBytes.size)

        val sOffset = 4 + rBytes.size
        der[sOffset] = 0x02 // INTEGER
        der[sOffset + 1] = sBytes.size.toByte()
        System.arraycopy(sBytes, 0, der, sOffset + 2, sBytes.size)

        return der
    }
}
