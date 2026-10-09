package com.keepasskey.crypto.strength

import com.keepasskey.crypto.NativeFallbackLog

/**
 * 口令强度判定标志位（ISSUE-P3-36）。
 *
 * ⚠️ **跨语言契约**：位值与 Rust 侧 `strength.rs` 的 `FLAG_*` 常量**必须逐位一致**，
 * 由 `PasswordStrengthTest.原生返回的标志位与 Kotlin 常量逐位一致` 锁定。新增标志位必须两侧同步并各补一条断言。
 */
object PasswordStrengthFlags {
    /** 无任何标志。 */
    const val NONE = 0

    /** 完全或近似命中常见口令表。 */
    const val COMMON_PASSWORD = 1

    /** 长度不足 [PasswordStrengthEvaluator.MIN_RECOMMENDED_LEN]。 */
    const val TOO_SHORT = 1 shl 1

    /** 存在同字符重复段（长度 ≥ 3）。 */
    const val REPEATED_RUN = 1 shl 2

    /** 存在单调顺序段（如 `abc` / `321`，长度 ≥ 3）。 */
    const val SEQUENCE = 1 shl 3

    /** 存在键盘相邻行走段（如 `qwert` / `asdf`，长度 ≥ 4）。 */
    const val KEYBOARD_WALK = 1 shl 4

    /** 呈日期/年份形状。 */
    const val DATE_LIKE = 1 shl 5

    /** 仅含单一字符类别。 */
    const val SINGLE_CHAR_CLASS = 1 shl 6

    /** 字符唯一率过低（< 50%）。 */
    const val LOW_UNIQUE_RATIO = 1 shl 7

    /** 整串是短周期块的高次重复（如 `abcabcabc`）。 */
    const val PERIODIC_REPEAT = 1 shl 8
}

/**
 * 口令强度评估结果。
 *
 * @param score 分档 `0..4`（越大越强）
 * @param guessesLog10 `log10(估计猜测次数)`
 * @param flags [PasswordStrengthFlags] 位或
 */
data class PasswordStrength(
    val score: Int,
    val guessesLog10: Double,
    val flags: Int
) {
    /** 是否命中常见口令表（完全或近似）。 */
    val isCommonPassword: Boolean get() = has(PasswordStrengthFlags.COMMON_PASSWORD)

    /** 是否长度不足推荐下限。 */
    val isTooShort: Boolean get() = has(PasswordStrengthFlags.TOO_SHORT)

    /** 是否含模式化结构（重复 / 顺序 / 键盘行走 / 周期重复 / 日期）。 */
    val hasPatternedStructure: Boolean
        get() = has(PasswordStrengthFlags.REPEATED_RUN) ||
            has(PasswordStrengthFlags.SEQUENCE) ||
            has(PasswordStrengthFlags.KEYBOARD_WALK) ||
            has(PasswordStrengthFlags.PERIODIC_REPEAT) ||
            has(PasswordStrengthFlags.DATE_LIKE)

    /**
     * 是否应判为「弱口令」——**接线前后语义兼容的判定式**：
     * 保留既有两条规则（长度不足 [PasswordStrengthFlags.TOO_SHORT] / 命中常见口令表
     * [PasswordStrengthFlags.COMMON_PASSWORD]），并新增「分档 ≤ 1」，覆盖
     * `qwertyuiop` / `abcabcabc` / `20260101` 等旧实现完全无感的口令。
     *
     * 之所以做成属性而非各消费方自行拼装：避免健康检查、设置页与后续 UI 各自复制一份判据而漂移。
     */
    val isWeak: Boolean get() = isTooShort || isCommonPassword || score <= 1

    private fun has(flag: Int): Boolean = (flags and flag) != 0
}

/**
 * 口令强度评估统一入口（ISSUE-P3-36）。
 *
 * 优先走 Rust 原生内核（`crypto/src/main/rust/src/strength.rs`），原生不可用时降级为
 * [PasswordStrengthFallback]——后者自 B3 裁决（2026-10-09）起为该内核的**直译**，
 * 与原生在同一输入集上给出**相同的分档与标志位**（原「字节级近似、不覆盖模式分析」的定位已作废）。
 *
 * 模型范围（如实声明）：**模式惩罚型评估，不是 zxcvbn**。由「字符集熵基线 + 模式惩罚」构成，
 * 不引入 zxcvbn 的数十万条频率语料。选择原生的首要理由是**秘密治理**
 * （口令字节不入 JVM 字符串、Rust 侧 `Zeroizing` 确定性擦除），**不是**吞吐。
 *
 * 调用方契约：`password` 为 UTF-8 字节，调用方持有清零点责任（本入口不克隆、不留存）。
 */
object PasswordStrengthEvaluator {

    /** 最低分档。 */
    const val SCORE_MIN = 0

    /** 最高分档。 */
    const val SCORE_MAX = 4

    /** 推荐的最短长度（对齐 NIST SP 800-63B 与既有健康检查的 `< 8` 判据）。 */
    const val MIN_RECOMMENDED_LEN = 8

    /** 唯一率惩罚阈值。 */
    const val LOW_UNIQUE_RATIO = 0.5

    /** 原生内核当前是否可用（供诊断与测试断言，不应作为业务分支依据）。 */
    val nativeAvailable: Boolean get() = NativePasswordStrength.available

    /**
     * 评估口令强度。
     *
     * @param password UTF-8 字节；空数组是**合法输入**（返回 [SCORE_MIN] 且带 TOO_SHORT 标志）
     */
    fun evaluate(password: ByteArray): PasswordStrength {
        if (NativePasswordStrength.available) {
            return NativePasswordStrength.evaluate(password)
        }
        // ISSUE-P2-499：探活失败静默回落不可观测——回落时一次性记「已回落」事实（不含口令 / 参数）
        NativeFallbackLog.noteFallbackOnce("口令强度")
        return PasswordStrengthFallback.evaluate(password)
    }

    /**
     * 是否应判为「弱口令」的便捷入口（内部即 [PasswordStrength.isWeak]，单次评估）。
     */
    fun isWeak(password: ByteArray): Boolean = evaluate(password).isWeak
}
