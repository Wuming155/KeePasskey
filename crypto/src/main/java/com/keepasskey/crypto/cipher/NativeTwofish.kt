package com.keepasskey.crypto.cipher

import com.keepasskey.crypto.NativeCryptoLibrary
import com.keepasskey.crypto.exception.CryptoException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Twofish-CBC 原生 JNI 绑定（ISSUE-P3-35）。
 *
 * 原生实现位于 `crypto/src/main/rust/src/twofish_cbc.rs`（RustCrypto `twofish`，
 * `zeroize` 特性使密钥调度随对象析构归零，链值与分组缓冲经 `Zeroizing` 全路径擦除）。
 *
 * **职责边界**：原生侧只做 CBC 分组变换，**不含填充**；PKCS#7 与流式语义统一由
 * Kotlin 侧（[Pkcs7] / [CbcEncryptingOutputStream] / [CbcDecryptingInputStream]）承担，
 * 确保整型与流式两条路径共用同一份填充实现。
 *
 * [cbcEncryptBlocks] / [cbcDecryptBlocks] 的 `iv` 为**输入输出参数**：返回时被原地更新为
 * 最后一组密文（下一段的起始链值），这是上层能把长数据切成任意多段连续变换的前提。
 */
object NativeTwofish {

    /** 分组长度（字节）。 */
    const val BLOCK_SIZE = Pkcs7.BLOCK_SIZE

    /**
     * 可用性探活（懒加载一次）：先确保原生库已加载，再以固定合成输入加密**一个分组**，
     * 并与 BouncyCastle 的 ECB 结果逐字节比对。
     *
     * 对照基准刻意使用**裸 `Cipher`**（而非 [TwofishCipherEngine]）——后者会再走一遍
     * 「先问原生可用性」的分派，立即自递归。
     */
    val available: Boolean by lazy {
        // 必须先求值 loaded，否则外部函数调用会抛 UnsatisfiedLinkError（见 NativeCryptoLibrary KDoc）
        if (!NativeCryptoLibrary.loaded) {
            return@lazy false
        }
        try {
            val probeKey = ByteArray(32) { (it * 7 + 3).toByte() }
            val probeIv = ByteArray(BLOCK_SIZE) { (it * 5 + 1).toByte() }
            val probePlain = ByteArray(BLOCK_SIZE) { (it * 11 + 2).toByte() }
            val native = cbcEncryptBlocks(probeKey, probeIv.copyOf(), probePlain)
            val reference = bcCbcEncryptBlock(probeKey, probeIv, probePlain)
            try {
                native != null && native.size == BLOCK_SIZE && native.contentEquals(reference)
            } finally {
                native?.fill(0)
                reference.fill(0)
                probeKey.fill(0)
                probeIv.fill(0)
                probePlain.fill(0)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * CBC 链式加密（**输入长度必须为 [BLOCK_SIZE] 的整数倍**，不做填充）。
     *
     * @param iv 输入输出参数：调用时为当前链值，返回时被原地更新为最后一组密文
     * @return 密文；`key`/`iv`/`data` 任一不合法或内核异常返回 `null`
     */
    external fun cbcEncryptBlocks(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray?

    /**
     * CBC 链式解密（**不做去填充**；语义与加密侧对称，`iv` 同样原地演化）。
     *
     * @return 明文；`key`/`iv`/`data` 任一不合法或内核异常返回 `null`
     */
    external fun cbcDecryptBlocks(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray?

    /** 原生加密统一入口：失败归一为 [CryptoException.CipherException]。 */
    fun encryptBlocks(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val out = try {
            cbcEncryptBlocks(key, iv, data)
        } catch (e: UnsatisfiedLinkError) {
            throw CryptoException.CipherException("Twofish 原生库不可用", e)
        }
        return out ?: throw CryptoException.CipherException("Twofish 原生加密失败（参数不合法或内核异常）")
    }

    /** 原生解密统一入口：失败归一为 [CryptoException.CipherException]。 */
    fun decryptBlocks(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val out = try {
            cbcDecryptBlocks(key, iv, data)
        } catch (e: UnsatisfiedLinkError) {
            throw CryptoException.CipherException("Twofish 原生库不可用", e)
        }
        return out ?: throw CryptoException.CipherException("Twofish 原生解密失败（参数不合法或内核异常）")
    }

    /**
     * 单分组 CBC 加密（仅供探活对照；非生产路径）。
     *
     * 必须用 **CBC 而非 ECB**：CBC 首块为 `E(P ⊕ IV)`，只有携带同一 IV 的 CBC 结果才与
     * 原生 `cbcEncryptBlocks` 可比——若拿 ECB 结果对照，除非 IV 恰为全零，否则必然不等，
     * 会导致探活恒假、原生路径被静默弃用。
     */
    private fun bcCbcEncryptBlock(key: ByteArray, iv: ByteArray, block: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("Twofish/CBC/NoPadding", ChaCha20CipherEngine.bouncyCastleProvider())
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Twofish"), IvParameterSpec(iv))
        return cipher.doFinal(block)
    }
}
