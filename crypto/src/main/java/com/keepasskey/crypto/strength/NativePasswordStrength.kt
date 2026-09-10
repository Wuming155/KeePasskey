package com.keepasskey.crypto.strength

import com.keepasskey.crypto.NativeCryptoLibrary
import com.keepasskey.crypto.exception.CryptoException

/**
 * 口令强度评估原生 JNI 绑定（ISSUE-P3-36）。
 *
 * 原生实现位于 `crypto/src/main/rust/src/strength.rs`（同 crate 同 `.so`，见 [NativeCryptoLibrary]）。
 *
 * 返回契约（跨 FFI 的**定长 3 元 `IntArray`**，避免传结构与浮点）：
 * `[0] = score(0..4)`、`[1] = log10(猜测次数) × 100`、`[2] = flags`。
 */
object NativePasswordStrength {

    /** `IntArray` 定长布局元素个数（与 Rust 侧 `ESTIMATE_LEN` 一致）。 */
    private const val LAYOUT_SIZE = 3

    /** `log10 × 100` 的定点缩放因子。 */
    private const val LOG10_SCALE = 100.0

    /**
     * 可用性探活（懒加载一次）：先确保原生库已加载，再以固定输入 `password` 真实评估一次，
     * 校验布局长度、分值下界与「常见口令」标志位均符合预期。
     *
     * 探活强度高于「非空即通过」：能发现「内核返回同长度垃圾值」这类静默错误。
     */
    val available: Boolean by lazy {
        // 必须先求值 loaded，否则外部函数调用会抛 UnsatisfiedLinkError（见 NativeCryptoLibrary KDoc）
        if (!NativeCryptoLibrary.loaded) {
            return@lazy false
        }
        try {
            val probe = PROBE_PASSWORD.toByteArray(Charsets.US_ASCII)
            val layout = estimate(probe)
            probe.fill(0)
            layout != null &&
                layout.size == LAYOUT_SIZE &&
                layout[0] == 0 &&
                (layout[2] and PasswordStrengthFlags.COMMON_PASSWORD) != 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 口令强度评估（原生）。
     *
     * @param password UTF-8 字节；调用方持有清零点责任
     * @return 定长 3 元 `IntArray`；内核异常返回 `null`
     */
    external fun estimate(password: ByteArray): IntArray?

    /**
     * 原生评估统一入口：布局非法 / 内核失败归一为 [CryptoException.HashException]
     * （强度评估不属加密也不属 KDF，归入哈希族以复用既有异常层次，见 KDoc 说明）。
     */
    fun evaluate(password: ByteArray): PasswordStrength {
        val layout = try {
            estimate(password)
        } catch (e: UnsatisfiedLinkError) {
            throw CryptoException.HashException("口令强度评估原生库不可用", e)
        }
        if (layout == null || layout.size != LAYOUT_SIZE) {
            throw CryptoException.HashException("口令强度评估原生内核返回布局非法")
        }
        return PasswordStrength(
            score = layout[0].coerceIn(
                PasswordStrengthEvaluator.SCORE_MIN,
                PasswordStrengthEvaluator.SCORE_MAX
            ),
            guessesLog10 = layout[1] / LOG10_SCALE,
            flags = layout[2]
        )
    }

    /** 探活用固定输入（公开常量，非真实凭据）。 */
    private const val PROBE_PASSWORD = "password"
}
