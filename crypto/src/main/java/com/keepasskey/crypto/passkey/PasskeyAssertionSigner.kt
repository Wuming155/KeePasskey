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
        // ISSUE-P3-153 / §146：32 字节原始标量走 Rust 内核（确定性 RFC 6979 与 BC 逐字节一致，
        // 真机 sign 17.7ms → ~1ms）；其余编码形态（PKCS#8 等）走 BC 兜底（原生内核只吃原始标量）。
        // 原生返回 null（非法标量：d=0 / d≥n 等）**回落 BC**——由 BC 校验语义抛既有的
        // `InvalidKeyException`（既有闸门用例锁定该类型），不引入异常类型漂移。
        if (NativePasskeySign.available && privateKeyBytes.size == NativePasskeySign.ES256_SCALAR_LENGTH) {
            val native = NativePasskeySign.es256Sign(privateKeyBytes, dataToSign)
            if (native != null) return native
        }
        return bcSignEs256(privateKeyBytes, dataToSign)
    }

    private fun bcSignEs256(privateKeyBytes: ByteArray, dataToSign: ByteArray): ByteArray {
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
        // ISSUE-P3-153 / §146：32 字节原始种子走 Rust 内核（RFC 8032 确定性，真机 3.8ms → ~0.2ms）；
        // 其余编码形态走 BC 兜底。原生返回 null 时回落 BC（兜底可用性优先）。
        if (NativePasskeySign.available && privateKeyBytes.size == NativePasskeySign.ED25519_SEED_LENGTH) {
            val native = NativePasskeySign.ed25519Sign(privateKeyBytes, dataToSign)
            if (native != null) return native
        }
        return bcSignEd25519(privateKeyBytes, dataToSign)
    }

    private fun bcSignEd25519(privateKeyBytes: ByteArray, dataToSign: ByteArray): ByteArray {
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
