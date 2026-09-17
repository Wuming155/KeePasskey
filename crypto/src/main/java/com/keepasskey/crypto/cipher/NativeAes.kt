package com.keepasskey.crypto.cipher

import com.keepasskey.crypto.NativeCryptoLibrary
import com.keepasskey.crypto.exception.CryptoException

/**
 * AES-256-CBC 原生 JNI 绑定（ISSUE-P3-155 追问 / §147）。
 *
 * 原生实现位于 `crypto/src/main/rust/src/aes_cbc.rs`（RustCrypto `aes`，`zeroize` 特性使
 * 密钥调度随对象析构归零，与 twofish / chacha20 同纪律）。
 *
 * **职责边界**：原生侧只做 **CBC 分组变换**，**不含填充**；PKCS#7 与流式语义统一由 Kotlin 侧
 * （[Pkcs7] / [CbcEncryptingOutputStream] / [CbcDecryptingInputStream]）承担——与
 * [NativeTwofish] 完全同构，确保整型与流式两条路径共用同一份填充实现。
 *
 * [cbcEncryptBlocks] / [cbcDecryptBlocks] 的 `iv` 为**输入输出参数**：返回时被原地更新为
 * 最后一组密文（下一段的起始链值），这是流式路径能把长数据切成任意多段连续变换的前提。
 */
object NativeAes {

    /** 分组长度（字节）。 */
    const val BLOCK_SIZE = Pkcs7.BLOCK_SIZE

    /** 唯一受支持的密钥长度（AES-256；与 `aes_cbc.rs` 的 `KEY_LEN` 同值）。 */
    const val KEY_LENGTH = 32

    // NIST CBC-AES256 官方向量（csrc.nist.gov《Block Cipher Modes of Operation · CBC》示例文档的
    // Block #1）。探活以**官方向量自测**，自包含、不依赖 JCE。
    private val KAT_KEY =
        "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4".hexToByteArray()
    private val KAT_IV = "000102030405060708090a0b0c0d0e0f".hexToByteArray()
    private val KAT_PLAIN = "6bc1bee22e409f96e93d7e117393172a".hexToByteArray()
    private val KAT_CIPHER = "f58c4c04d6e5f1ba779eabfb5f7bfbd6".hexToByteArray()

    /**
     * 可用性探活（懒加载一次）：先确保原生库已加载，再以 NIST 官方向量做加/解双向自测。
     */
    val available: Boolean by lazy {
        // 必须先求值 loaded，否则外部函数调用会抛 UnsatisfiedLinkError（见 NativeCryptoLibrary KDoc）
        if (!NativeCryptoLibrary.loaded) {
            return@lazy false
        }
        try {
            val enc = cbcEncryptBlocks(KAT_KEY, KAT_IV.copyOf(), KAT_PLAIN)
            val dec = cbcDecryptBlocks(KAT_KEY, KAT_IV.copyOf(), KAT_CIPHER)
            try {
                enc != null && enc.contentEquals(KAT_CIPHER) &&
                    dec != null && dec.contentEquals(KAT_PLAIN)
            } finally {
                enc?.fill(0)
                dec?.fill(0)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * CBC 链式加密（**输入长度必须为 [BLOCK_SIZE] 的整数倍**，不做填充）。
     *
     * @param iv 输入输出参数：调用时为当前链值，返回时被原地更新为最后一组密文
     * @return 密文；`key` 非 32 字节、`iv` 非 16 字节、`data` 非整数倍分组或内核异常返回 `null`
     */
    external fun cbcEncryptBlocks(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray?

    /**
     * CBC 链式解密（**不做去填充**；语义与加密侧对称，`iv` 同样原地演化）。
     *
     * @return 明文；参数不合法或内核异常返回 `null`
     */
    external fun cbcDecryptBlocks(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray?

    /** 原生加密统一入口：失败归一为 [CryptoException.CipherException]。 */
    fun encryptBlocks(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val out = try {
            cbcEncryptBlocks(key, iv, data)
        } catch (e: UnsatisfiedLinkError) {
            throw CryptoException.CipherException("AES 原生库不可用", e)
        }
        return out ?: throw CryptoException.CipherException("AES 原生加密失败（参数不合法或内核异常）")
    }

    /** 原生解密统一入口：失败归一为 [CryptoException.CipherException]。 */
    fun decryptBlocks(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val out = try {
            cbcDecryptBlocks(key, iv, data)
        } catch (e: UnsatisfiedLinkError) {
            throw CryptoException.CipherException("AES 原生库不可用", e)
        }
        return out ?: throw CryptoException.CipherException("AES 原生解密失败（参数不合法或内核异常）")
    }
}
