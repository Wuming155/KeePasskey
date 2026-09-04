package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.crypto.digests.SHA256Digest
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * 通行密钥 (Passkey / WebAuthn) 密码学计算引擎。
 * 遵循 W3C WebAuthn Level 3 与 FIDO2 规范：
 * 1. 支持标准 ES256 (ECDSA P-256 / secp256r1 with SHA-256) 密钥生成；
 * 2. 构建 COSE_Key 结构与 AuthenticatorData 二进制块；
 * 3. 使用确定性 RFC 6979 DSA 签名对 `authenticatorData || clientDataHash` 进行数字签名。
 */
object PasskeyCryptoEngine {

    private val ecParams: X9ECParameters = SECNamedCurves.getByName("secp256r1")
    private val domainParams = ECDomainParameters(ecParams.curve, ecParams.g, ecParams.n, ecParams.h)
    private val secureRandom = SecureRandom()

    /**
     * 生成全新的 ES256 密钥对及凭据标识 (Credential ID)
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

        // 随机生成 32 字节唯一凭据标识 (Credential ID)
        val credIdBytes = ByteArray(32)
        secureRandom.nextBytes(credIdBytes)
        val credIdBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(credIdBytes)

        // 导出未压缩的椭圆曲线点 (0x04 || X || Y)
        val pubEncoded = pub.q.getEncoded(false)
        val pubBase64 = Base64.getEncoder().encodeToString(pubEncoded)

        // 私钥数值转为 Hex 存储
        val privHex = priv.d.toString(16)
        val privateKeyProtected = ProtectedString(privHex, isProtected = true)

        val handle = if (userHandle.isBlank()) {
            val handleBytes = ByteArray(16)
            secureRandom.nextBytes(handleBytes)
            Base64.getUrlEncoder().withoutPadding().encodeToString(handleBytes)
        } else {
            userHandle
        }

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
     * 对认证断言数据进行 ECDSA-SHA256 (ES256) 签名
     * @param privateKeyHex 私钥大整数 16 进制字符串
     * @param dataToSign 需签名的字节流 (通常为 authenticatorData || clientDataHash)
     * @return DER 编码的 ECDSA 签名字节
     */
    fun signAssertion(privateKeyHex: String, dataToSign: ByteArray): ByteArray {
        val d = BigInteger(privateKeyHex, 16)
        val privKeyParams = ECPrivateKeyParameters(d, domainParams)

        // 对数据计算 SHA-256 哈希
        val digest = SHA256Digest()
        digest.update(dataToSign, 0, dataToSign.size)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)

        // 采用确定性 k 生成器 (RFC 6979) 防范随机数偏差泄露私钥
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, privKeyParams)
        val components = signer.generateSignature(hash)
        val r = components[0]
        val s = components[1]

        return encodeDerSignature(r, s)
    }

    /**
     * 构建标准 AuthenticatorData 二进制块 (RFC 8152 / W3C WebAuthn)
     * @param rpId 依赖方域名
     * @param flags 标志位 (UP = 0x01, UV = 0x04, BE = 0x08, BS = 0x10, AT = 0x40)
     * @param signCount 签名计数器
     */
    fun buildAuthenticatorData(
        rpId: String,
        flags: Byte,
        signCount: Int
    ): ByteArray {
        val sha256 = SHA256Digest()
        val rpIdBytes = rpId.toByteArray(Charsets.UTF_8)
        sha256.update(rpIdBytes, 0, rpIdBytes.size)
        val rpIdHash = ByteArray(32)
        sha256.doFinal(rpIdHash, 0)

        val result = ByteArray(37)
        System.arraycopy(rpIdHash, 0, result, 0, 32)
        result[32] = flags
        result[33] = ((signCount shr 24) and 0xFF).toByte()
        result[34] = ((signCount shr 16) and 0xFF).toByte()
        result[35] = ((signCount shr 8) and 0xFF).toByte()
        result[36] = (signCount and 0xFF).toByte()
        return result
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
