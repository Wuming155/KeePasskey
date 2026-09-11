package com.keepasskey.crypto.passkey

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.crypto.signers.RSADigestSigner
import org.bouncycastle.crypto.util.PrivateKeyFactory
import java.math.BigInteger

/**
 * Passkey 断言签名实现协作单元（自 [PasskeyCryptoEngine] 纯结构性下沉）。
 *
 * 覆盖三类 COSE 算法：
 * - ES256：ECDSA P-256 + SHA-256，采用确定性 RFC 6979 ([HMacDSAKCalculator])，输出 ASN.1 DER；
 * - Ed25519：EdDSA 原生签名，输出 64 字节 raw；
 * - RS256：RSASSA-PKCS1-v1_5 + SHA-256，输出 256 字节 PKCS#1 v1.5。
 *
 * 入参私钥字节流的克隆与用毕清零由调用方 [PasskeyCryptoEngine.signAssertion] 统一负责。
 */
internal object PasskeyAssertionSigner {

    internal fun signEs256(privateKeyBytes: ByteArray, dataToSign: ByteArray): ByteArray {
        val privKeyParams = PasskeyKeyCodec.parseEcPrivateKey(privateKeyBytes)

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

    internal fun signEd25519(privateKeyBytes: ByteArray, dataToSign: ByteArray): ByteArray {
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

    internal fun signRs256(privateKeyBytes: ByteArray, dataToSign: ByteArray): ByteArray {
        val privKey = PrivateKeyFactory.createKey(privateKeyBytes) as RSAKeyParameters
        val signer = RSADigestSigner(SHA256Digest())
        signer.init(true, privKey)
        signer.update(dataToSign, 0, dataToSign.size)
        return signer.generateSignature()
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
