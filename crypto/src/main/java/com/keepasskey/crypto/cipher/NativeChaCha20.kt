package com.keepasskey.crypto.cipher

import com.keepasskey.crypto.NativeCryptoLibrary
import com.keepasskey.crypto.exception.CryptoException

/**
 * ChaCha20（RFC 8439）原生 JNI 绑定（ISSUE-P3-153 / §145）。
 *
 * 原生实现位于 `crypto/src/main/rust/src/chacha20_stream.rs`（RustCrypto `chacha20`，
 * `zeroize` 特性使密钥调度随对象析构归零，与 aes / twofish 同纪律）。
 *
 * **动机**：ChaCha20 的 BC 纯 Java 实现真机吞吐仅 2.6~2.7 MB/s（AES 硬件路径的 ~1/118，
 * 见 `docs/records/真机吞吐实测记录_2026-09-17.md` §2.1），Rust 内核实测 ≈118 MB/s（≈44×）。
 *
 * **设计**：密钥流是 (key, nonce, byte_offset) 的纯函数——无状态按偏移定位，
 * 无需跨 JNI 持有流对象生命周期；调用方按已处理字节数推进偏移即可（流包装见
 * [ChaCha20CipherEngine] 内部的流实现）。
 *
 * 探活：懒加载一次，以 **RFC 8439 §2.4.2 官方向量**自测（自包含，不依赖 BC 对照——
 * 与 [NativeTwofish] 的 BC 对照探活不同，ChaCha20 的 BC 路径在本引擎中是回退路径，
 * 且探活应独立于任何 JCE provider 环境）。
 */
object NativeChaCha20 {

    const val KEY_LENGTH = 32
    const val NONCE_LENGTH = 12

    private const val KAT_PLAINTEXT =
        "Ladies and Gentlemen of the class of '99: If I could offer you " +
            "only one tip for the future, sunscreen would be it."
    private const val KAT_CIPHERTEXT_HEX =
        "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b" +
            "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
            "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
            "5af90bbf74a35be6b40b8eedf2785e42874d"

    /**
     * 可用性探活（懒加载一次）：先确保原生库已加载，再以 RFC 8439 §2.4.2 官方向量
     * （块计数器 1 = 字节偏移 64）做端到端自测。
     */
    val available: Boolean by lazy {
        // 必须先求值 loaded，否则外部函数调用会抛 UnsatisfiedLinkError（见 NativeCryptoLibrary KDoc）
        if (!NativeCryptoLibrary.loaded) {
            return@lazy false
        }
        try {
            val key = ByteArray(KEY_LENGTH) { it.toByte() }
            val nonce = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0x4a, 0, 0, 0, 0)
            val plain = KAT_PLAINTEXT.toByteArray(Charsets.US_ASCII)
            try {
                val out = applyKeystreamChecked(key, nonce, 64, plain)
                try {
                    out.toHexString() == KAT_CIPHERTEXT_HEX.lowercase()
                } finally {
                    out.fill(0)
                }
            } finally {
                key.fill(0)
                nonce.fill(0)
                plain.fill(0)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 密钥流施加（JNI 外部函数）。
     *
     * @param key 32 字节密钥
     * @param nonce 12 字节 nonce（KDBX 侧即 EncryptionIV 的 12 字节）
     * @param byteOffset 密钥流字节偏移（≥ 0；调用方按已处理字节数推进）
     * @param data 待加 / 解密的字节（内容将被密钥流 XOR 后以**新数组**返回）
     * @return 变换结果；参数不合法或内核异常返回 `null`
     */
    external fun applyKeystream(
        key: ByteArray,
        nonce: ByteArray,
        byteOffset: Long,
        data: ByteArray
    ): ByteArray?

    /** 原生调用统一入口：失败归一为 [CryptoException.CipherException]（fail-closed）。 */
    fun applyKeystreamChecked(
        key: ByteArray,
        nonce: ByteArray,
        byteOffset: Long,
        data: ByteArray
    ): ByteArray {
        if (key.size != KEY_LENGTH) {
            throw CryptoException.CipherException("ChaCha20 原生密钥长度必须为 $KEY_LENGTH 字节，实际 ${key.size}")
        }
        if (nonce.size != NONCE_LENGTH) {
            throw CryptoException.CipherException("ChaCha20 原生 nonce 长度必须为 $NONCE_LENGTH 字节，实际 ${nonce.size}")
        }
        if (byteOffset < 0) {
            throw CryptoException.CipherException("ChaCha20 原生偏移不得为负：$byteOffset")
        }
        val out = try {
            applyKeystream(key, nonce, byteOffset, data)
        } catch (e: UnsatisfiedLinkError) {
            throw CryptoException.CipherException("ChaCha20 原生库不可用", e)
        }
        return out ?: throw CryptoException.CipherException(
            "ChaCha20 原生密钥流施加失败（参数不合法或越过密钥流上界，offset=$byteOffset len=${data.size}）"
        )
    }
}
