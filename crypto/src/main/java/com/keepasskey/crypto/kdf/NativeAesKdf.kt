package com.keepasskey.crypto.kdf

import com.keepasskey.crypto.NativeCryptoLibrary
import com.keepasskey.crypto.exception.CryptoException

/**
 * AES-KDF 原生 JNI 绑定（ISSUE-P3-34）。
 *
 * 原生实现位于 `crypto/src/main/rust/src/aes_kdf.rs`（RustCrypto `aes` + `sha2`，秘密缓冲
 * `Zeroizing` 全路径确定性擦除，`Aes256` 因 `zeroize` 特性其密钥调度随析构归零）。
 *
 * 与 `NativeArgon2` 保持同一套边界语义：
 * - [available] 为**真实试算**探活（非仅加载成功判定）；
 * - [derive] 失败归一为 [CryptoException.KdfException]，**不静默回退重算**（对齐 KeePassDX
 *   Limits 模式：宁可如实失败，也不在原生异常时悄悄用另一条路径重算并掩盖问题）；
 * - 桌面 JVM / 缺 ABI 时 [available] 为 `false`，由 [AesKdfEngine] 走 JCE 兜底。
 */
object NativeAesKdf {

    /** 复合密钥与派生输出长度（KDBX AES-KDF 恒为 32 字节）。 */
    const val KEY_LEN = 32

    /** 探活轮数：1 轮即可验证「调用通路 + 参数语义」可用，代价可忽略。 */
    private const val PROBE_ROUNDS = 1L

    /**
     * 可用性探活（懒加载一次）：先确保原生库已加载，再以固定合成输入做 **1 轮真实派生**，
     * 并与 [AesKdfJce]（独立实现）逐字节比对。
     *
     * 探活强度高于 `NativeArgon2` 的「非空即通过」：不仅能发现「库没加载 / 符号缺失」，
     * 还能发现「内核返回同长度垃圾值」这类静默错误——后者若漏过，会以
     * 「派生密钥错误 → HMAC 校验失败 → 用户无法解锁」的形式在真机上暴露。
     */
    val available: Boolean by lazy {
        // 必须先求值 loaded，否则外部函数调用会抛 UnsatisfiedLinkError（见 NativeCryptoLibrary KDoc）
        if (!NativeCryptoLibrary.loaded) {
            return@lazy false
        }
        try {
            val probeKey = ByteArray(KEY_LEN) { (it * 5 + 1).toByte() }
            val probeSeed = ByteArray(KEY_LEN) { (0xFF - it).toByte() }
            val native = deriveKey(probeKey, probeSeed, PROBE_ROUNDS)
            val reference = AesKdfJce.transform(probeKey, probeSeed, PROBE_ROUNDS)
            try {
                native != null && native.size == KEY_LEN && native.contentEquals(reference)
            } finally {
                native?.fill(0)
                reference.fill(0)
                probeKey.fill(0)
                probeSeed.fill(0)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * AES-KDF 密钥派生（原生实现）。
     *
     * @param compositeKey 复合密钥摘要（32 字节），调用方持有清零责任
     * @param seed KDF 种子（32 字节，作 AES 密钥）
     * @param rounds 迭代轮数（调用方须已按 `KdbxKdfParameterCodec.validateAesKdfBounds` 裁决）
     * @return 32 字节派生密钥；参数非法 / 派生失败返回 `null`
     */
    external fun deriveKey(compositeKey: ByteArray, seed: ByteArray, rounds: Long): ByteArray?

    /**
     * 原生派生统一入口：失败归一为 [CryptoException.KdfException]。
     */
    fun derive(compositeKey: ByteArray, seed: ByteArray, rounds: Long): ByteArray {
        val out = try {
            deriveKey(compositeKey, seed, rounds)
        } catch (e: UnsatisfiedLinkError) {
            throw CryptoException.KdfException("AES-KDF 原生库不可用", e)
        }
        if (out == null) {
            throw CryptoException.KdfException("AES-KDF 原生派生失败（参数越界或原生内核异常）")
        }
        return out
    }
}
