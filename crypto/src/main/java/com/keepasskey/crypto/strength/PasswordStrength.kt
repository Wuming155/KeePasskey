package com.keepasskey.crypto.strength

/**
 * 口令强度判定标志位（ISSUE-P3-36）。
 *
 * ⚠️ **跨语言契约**：位值与 Rust 侧 `strength.rs` 的 `FLAG_*` 常量**必须逐位一致**，
 * 由 `PasswordStrengthNativeParityTest` 锁定。新增标志位必须两侧同步并各补一条断言。
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
 * [PasswordStrengthFallback]（字节级近似，**行为不劣于接线前的既有启发式**）。
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
        return PasswordStrengthFallback.evaluate(password)
    }

    /**
     * 是否应判为「弱口令」的便捷入口（内部即 [PasswordStrength.isWeak]，单次评估）。
     */
    fun isWeak(password: ByteArray): Boolean = evaluate(password).isWeak
}

/**
 * 原生不可用时的降级实现（**字节级近似**）。
 *
 * 降级定位与边界（如实声明）：
 * - 覆盖长度、常见口令、字符类别数、字符唯一率四类判定，**不覆盖**顺序段 / 键盘行走 /
 *   周期重复 / 日期形状等需要字符语义的模式分析（这些依赖原生内核）；
 * - 类别与大小写判定按 **ASCII 字节**进行；非 ASCII 字节一律计入「其他」类别，
 *   不会因解码失败而抛异常或放行；
 * - **全程不构造 `String`**：候选口令只以小写化后的可清零 `ByteArray` 参与比较，
 *   词表侧以 ASCII 字节数组承载（词表是公开常量，非敏感数据），符合敏感数据铁律。
 */
internal object PasswordStrengthFallback {

    /** `log2(10)`，与 Rust 侧同值。 */
    private const val LOG2_10 = 3.3219280948873626

    /**
     * 常见口令表（全小写 ASCII 字节）。
     *
     * ⚠️ 与 Rust 侧 `strength.rs::COMMON_PASSWORDS` 是**跨语言重复定义**（FFI 边界无法共享数据），
     * 两者集合成员必须保持一致，由 `PasswordStrengthNativeParityTest` 的对照用例锁定。
     */
    private val COMMON_PASSWORDS: List<ByteArray> = listOf(
        "123456", "password", "12345678", "qwerty", "123456789", "12345", "1234", "111111",
        "1234567", "dragon", "welcome", "admin", "admin123", "root", "pass123",
        "qwertyuiop", "asdfghjkl", "zxcvbnm", "1qaz2wsx", "qazwsx", "qwerty123", "abc123",
        "a123456", "letmein", "iloveyou", "monkey", "000000", "666666", "888888", "123123",
        "112233", "121212", "111111111"
    ).map { it.toByteArray(Charsets.US_ASCII) }

    fun evaluate(password: ByteArray): PasswordStrength {
        if (password.isEmpty()) {
            return PasswordStrength(
                score = PasswordStrengthEvaluator.SCORE_MIN,
                guessesLog10 = 0.0,
                flags = PasswordStrengthFlags.TOO_SHORT
            )
        }

        var hasLower = false
        var hasUpper = false
        var hasDigit = false
        var hasSymbol = false
        val seen = BooleanArray(BYTE_RANGE)
        var unique = 0
        for (byte in password) {
            val value = byte.toInt() and 0xFF
            if (!seen[value]) {
                seen[value] = true
                unique++
            }
            when (value) {
                in LOWER_A..LOWER_Z -> hasLower = true
                in UPPER_A..UPPER_Z -> hasUpper = true
                in DIGIT_0..DIGIT_9 -> hasDigit = true
                else -> hasSymbol = true
            }
        }

        var flags = PasswordStrengthFlags.NONE
        if (password.size < PasswordStrengthEvaluator.MIN_RECOMMENDED_LEN) {
            flags = flags or PasswordStrengthFlags.TOO_SHORT
        }
        val classCount = listOf(hasLower, hasUpper, hasDigit, hasSymbol).count { it }
        if (classCount == 1) {
            flags = flags or PasswordStrengthFlags.SINGLE_CHAR_CLASS
        }
        val uniqueRatio = unique.toDouble() / password.size
        if (uniqueRatio < PasswordStrengthEvaluator.LOW_UNIQUE_RATIO) {
            flags = flags or PasswordStrengthFlags.LOW_UNIQUE_RATIO
        }

        val lowered = asciiLowercase(password)
        val hitsCommon = try {
            COMMON_PASSWORDS.any { it.contentEquals(lowered) } || stemHit(lowered)
        } finally {
            // 候选口令的小写副本属敏感数据，比较完毕立即清零
            lowered.fill(0)
        }
        if (hitsCommon) {
            flags = flags or PasswordStrengthFlags.COMMON_PASSWORD
        }

        val charset = (if (hasLower) 26 else 0) + (if (hasUpper) 26 else 0) +
            (if (hasDigit) 10 else 0) + (if (hasSymbol) 33 else 0)
        val baseLog10 = password.size * log2(charset.toDouble()) / LOG2_10
        var log10 = if (hitsCommon) minOf(baseLog10, 4.0) else baseLog10
        if (flags and PasswordStrengthFlags.SINGLE_CHAR_CLASS != 0) {
            log10 -= 2.0
        }
        if (flags and PasswordStrengthFlags.LOW_UNIQUE_RATIO != 0) {
            log10 -= (PasswordStrengthEvaluator.LOW_UNIQUE_RATIO - uniqueRatio) * 8.0
        }
        log10 = log10.coerceAtLeast(0.0)

        return PasswordStrength(
            score = scoreOf(log10).coerceAtMost(lengthCap(password.size)),
            guessesLog10 = log10,
            flags = flags
        )
    }

    /** ASCII 小写化副本（非 ASCII 字节原样保留，不引入区域设置相关行为）。调用方负责清零。 */
    private fun asciiLowercase(password: ByteArray): ByteArray {
        val out = ByteArray(password.size)
        for (i in password.indices) {
            val value = password[i].toInt() and 0xFF
            out[i] = if (value in UPPER_A..UPPER_Z) (value + 32).toByte() else value.toByte()
        }
        return out
    }

    /** 近似命中：剥除尾部数字与 ASCII 符号后命中常见口令表（`password1` / `qwerty!` 一族）。 */
    private fun stemHit(lowered: ByteArray): Boolean {
        var end = lowered.size
        while (end > 0) {
            val value = lowered[end - 1].toInt() and 0xFF
            val isDigit = value in DIGIT_0..DIGIT_9
            val isAsciiPunct = value < ASCII_MAX &&
                !isDigit && value !in LOWER_A..LOWER_Z && value !in UPPER_A..UPPER_Z && value != SPACE
            if (!isDigit && !isAsciiPunct) break
            end--
        }
        if (end == 0 || end == lowered.size) return false
        return COMMON_PASSWORDS.any { prefixEquals(it, lowered, end) }
    }

    private fun prefixEquals(candidate: ByteArray, lowered: ByteArray, length: Int): Boolean {
        if (candidate.size != length) return false
        for (i in 0 until length) {
            if (candidate[i] != lowered[i]) return false
        }
        return true
    }

    private fun log2(value: Double): Double = Math.log(value) / Math.log(2.0)

    private fun scoreOf(log10: Double): Int = when {
        log10 < 3.0 -> 0
        log10 < 6.0 -> 1
        log10 < 8.0 -> 2
        log10 < 11.0 -> 3
        else -> PasswordStrengthEvaluator.SCORE_MAX
    }

    /** 与 Rust 侧 `length_cap` 同策略：不足 8 位不高于 2 档；不足 12 位不高于 3 档。 */
    private fun lengthCap(length: Int): Int = when {
        length < PasswordStrengthEvaluator.MIN_RECOMMENDED_LEN -> 2
        length < LONG_ENOUGH_LEN -> 3
        else -> PasswordStrengthEvaluator.SCORE_MAX
    }

    private const val LONG_ENOUGH_LEN = 12
    private const val BYTE_RANGE = 256
    private const val ASCII_MAX = 128
    private const val SPACE = 0x20
    private const val LOWER_A = 0x61
    private const val LOWER_Z = 0x7A
    private const val UPPER_A = 0x41
    private const val UPPER_Z = 0x5A
    private const val DIGIT_0 = 0x30
    private const val DIGIT_9 = 0x39
}
