package com.keepasskey.crypto.passkey

import com.keepasskey.crypto.NativeCryptoLibrary
import com.keepasskey.crypto.exception.CryptoException

/**
 * Passkey 签名原生 JNI 绑定（ISSUE-P3-153 / §146）。
 *
 * 原生实现位于 `crypto/src/main/rust/src/passkey_sign.rs`（RustCrypto `p256` / `ed25519-dalek`，
 * 确定性 RFC 6979 / RFC 8032，与 BC 生产路径产出**逐字节相同**的签名——对拍成立的根据）。
 *
 * 动机：BC 纯 Java 真机单次签名 ES256 ≈17.7 ms / Ed25519 ≈3.8 ms；Rust 内核 ≈1.07 / ≈0.17 ms
 * （16~22×，见 `docs/records/真机吞吐实测记录_2026-09-17.md` §2.2）。RS256 已裁定不下沉。
 *
 * 探活：懒加载一次，以**官方 RFC 向量**自测（ES256 = RFC 6979 A.2.5 SHA-256 "sample"；
 * Ed25519 = RFC 8032 §7.1 TEST 1），自包含、不依赖 BC。
 */
object NativePasskeySign {

    /** ES256 私钥标量长度（仅接受 32 字节原始标量形态；PKCS#8 等编码走 BC 兜底）。 */
    const val ES256_SCALAR_LENGTH = 32

    /** Ed25519 私钥种子长度（仅接受 32 字节原始种子形态；PKCS#8 等编码走 BC 兜底）。 */
    const val ED25519_SEED_LENGTH = 32

    private val es256ProbeKey =
        "c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721".hexToByteArray()
    private val es256ProbeMessage = "sample".toByteArray(Charsets.US_ASCII)
    private val es256ProbeSignature =
        ("3046022100efd48b2aacb6a8fd1140dd9cd45e81d69d2c877b56aaf991c34d0ea84eaf3716" +
            "022100f7cb1c942d657c41d436c7a1b6e29f65f3e900dbb9aff4064dc4ab2f843acda8").hexToByteArray()

    private val ed25519ProbeSeed =
        "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToByteArray()
    // RFC 8032 §7.1 TEST 1：**空报文**的签名
    private val ed25519ProbeMessage = ByteArray(0)
    private val ed25519ProbeSignature =
        ("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
            "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b").hexToByteArray()

    /**
     * 可用性探活（懒加载一次）：原生库已加载且两个官方向量均逐字节复现。
     */
    val available: Boolean by lazy {
        if (!NativeCryptoLibrary.loaded) {
            return@lazy false
        }
        try {
            es256KatMatches() && ed25519KatMatches()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * ES256 签名（JNI 外部函数）。
     *
     * @param privateKey 32 字节 P-256 私钥标量（无符号大端）
     * @param data 待签名报文（authenticatorData || clientDataHash）
     * @return ASN.1 DER 签名；参数不合法或内核异常返回 `null`
     */
    external fun es256Sign(privateKey: ByteArray, data: ByteArray): ByteArray?

    /**
     * Ed25519 签名（JNI 外部函数）。
     *
     * @param privateKey 32 字节 Ed25519 私钥种子（原始形态）
     * @param data 待签名报文
     * @return 64 字节 raw 签名；参数不合法或内核异常返回 `null`
     */
    external fun ed25519Sign(privateKey: ByteArray, data: ByteArray): ByteArray?

    /** ES256 统一入口：失败归一为 [CryptoException.CipherException]（fail-closed）。 */
    fun es256SignChecked(privateKey: ByteArray, data: ByteArray): ByteArray {
        val out = try {
            es256Sign(privateKey, data)
        } catch (e: UnsatisfiedLinkError) {
            throw CryptoException.CipherException("Passkey ES256 原生库不可用", e)
        }
        return out ?: throw CryptoException.CipherException(
            "Passkey ES256 原生签名失败（私钥须为 32 字节合法 P-256 标量）"
        )
    }

    /** Ed25519 统一入口：失败归一为 [CryptoException.CipherException]（fail-closed）。 */
    fun ed25519SignChecked(privateKey: ByteArray, data: ByteArray): ByteArray {
        val out = try {
            ed25519Sign(privateKey, data)
        } catch (e: UnsatisfiedLinkError) {
            throw CryptoException.CipherException("Passkey Ed25519 原生库不可用", e)
        }
        return out ?: throw CryptoException.CipherException(
            "Passkey Ed25519 原生签名失败（私钥须为 32 字节种子）"
        )
    }

    private fun es256KatMatches(): Boolean {
        val out = es256Sign(es256ProbeKey, es256ProbeMessage) ?: return false
        return out.contentEquals(es256ProbeSignature)
    }

    private fun ed25519KatMatches(): Boolean {
        val out = ed25519Sign(ed25519ProbeSeed, ed25519ProbeMessage) ?: return false
        return out.contentEquals(ed25519ProbeSignature)
    }
}
