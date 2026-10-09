package com.keepasskey.crypto.strength

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 原生不可用时的降级实现——`crypto/src/main/rust/src/strength.rs` 的**直译**（主体；模式判据见
 * [PasswordStrengthFallbackPatterns]）。
 *
 * **定位变更（B3 裁决，2026-10-09）**：本类原为「仅覆盖长度 / 常见口令 / 字符类别 / 唯一率四类判定的
 * **独立近似**」，与原生内核存在语义漂移（重复段 / 顺序段 / 键盘行走 / 周期重复 / 日期形状四条判据
 * 原生判、降级不判）。现改为**逐条直译**——常量、判据顺序、惩罚系数、`score_of` / `length_cap` /
 * `clamp_x100` 均与 Rust 侧一一对应。
 *
 * **两侧一致性口径**（如实声明）：分档 `score` 与标志位 `flags` 在**同一输入集上逐条一致**，由
 * 宿主侧行为向量用例（`PasswordStrengthTest`，与 `strength_tests.rs` 同款向量）与设备侧对拍用例
 * （`NativePasswordStrengthDeviceTest`）双向锁定；`guessesLog10` 允许**末位定点差**——两侧
 * `log2` / `log10` 分别取自 Rust libm 与 JDK `Math`，ULP 级差异不可消除，不作为等价判据。
 *
 * **敏感数据边界（不变）**：全程**不构造 `String`**。口令字节经 `CharsetDecoder` 解码为可清零的
 * `CharArray`（直译需要字符语义：字符集分类、唯一计数、周期均按字符计），用毕 `fill('\u0000')`；
 * 唯一计数的小表同样受管清零。词表为公开常量，非敏感数据。
 *
 * **与 Rust 的已知残余差异**（语言标准库边界所致，不构成缺陷）：
 * - 非法 UTF-8 的替换粒度：Rust `from_utf8_lossy` 与 JDK `CharsetDecoder(REPLACE)` 对同一条非法
 *   子序列插入的 `U+FFFD` 个数可能不同——仅影响**非法字节**输入（生产口令恒为合法 UTF-8）；
 * - 空白字符判定：Rust 用 `char::is_whitespace`（Unicode `White_Space`），本侧以
 *   `Char.isWhitespace() || Character.isSpaceChar(c)` 近似（JDK 无该属性的直接 API）。
 */
internal object PasswordStrengthFallback {

    private const val MIN_RECOMMENDED_LEN = PasswordStrengthEvaluator.MIN_RECOMMENDED_LEN
    private const val SCORE_MAX = PasswordStrengthEvaluator.SCORE_MAX
    private const val LONG_ENOUGH_LEN = 12

    /** 热路径分析长度上限（与 Rust 侧同值）；超额部分不计熵信用、另按线性惩罚扣减。 */
    private const val MAX_ANALYZED_CHARS = 256

    /** 超额每字符的线性惩罚（`log10` 维度），与 Rust 侧同值。 */
    private const val EXCESS_PENALTY_PER_CHAR = 0.05

    /**
     * `log2(10)`——与 `strength.rs::LOG2_10` **同字面量**，保证两侧取到同一 double
     * （本批同时把 Rust 侧该常量的笔误值订正为 `log2(10)`，消除唯一的常数级漂移）。
     */
    internal const val LOG2_10 = 3.321928094887362

    private const val ASCII_MAX = 128

    /**
     * 常见口令表（全小写 ASCII 字节）。
     *
     * ⚠️ 与 Rust 侧 `strength.rs::COMMON_PASSWORDS` 是**跨语言重复定义**（FFI 边界无法共享数据），
     * 两者集合成员必须保持一致，由 `PasswordStrengthTest` 的集合守卫锁定——Kotlin 侧
     * `常见口令表为已冻结的33条去重集合`（冻结条数与去重）与 `常见口令表每条均被原生内核判定为常见口令`
     * （样本外漂移亦被拦截，经原生内核建立跨语言执行点）两例，加 Rust 侧
     * `common_passwords_table_is_frozen_and_distinct`（条数与去重）三例共同闭合。
     */
    private val COMMON_PASSWORDS: List<ByteArray> = listOf(
        "123456", "password", "12345678", "qwerty", "123456789", "12345", "1234", "111111",
        "1234567", "dragon", "welcome", "admin", "admin123", "root", "pass123",
        "qwertyuiop", "asdfghjkl", "zxcvbnm", "1qaz2wsx", "qazwsx", "qwerty123", "abc123",
        "a123456", "letmein", "iloveyou", "monkey", "000000", "666666", "888888", "123123",
        "112233", "121212", "111111111"
    ).map { it.toByteArray(Charsets.US_ASCII) }

    /**
     * **测试专用**读取口（`ISSUE-P3-506`）：暴露降级路径的常见口令表供集合成员守卫核对，生产路径不读。
     *
     * 返回不可变调用方：每条为副本（`copyOf`），且保持 `ByteArray` 形态以不违反本类的
     * 「词表以 ASCII 字节数组承载、不构造 `String`」纪律（词表是公开常量，非敏感数据）。
     */
    internal fun commonPasswordsForTest(): List<ByteArray> =
        COMMON_PASSWORDS.map { it.copyOf() }

    /** 评估过程中的可变累加器（标志位 + `log10` 猜测次数），对应 Rust 侧的局部变量对。 */
    internal class Acc(var flags: Int, var log10: Double)

    /**
     * 口令强度评估（降级路径）。
     *
     * @param password UTF-8 字节；空数组是**合法输入**（返回最低档且带 `TOO_SHORT`）
     */
    fun evaluate(password: ByteArray): PasswordStrength {
        if (password.isEmpty()) {
            return PasswordStrength(
                score = PasswordStrengthEvaluator.SCORE_MIN,
                guessesLog10 = 0.0,
                flags = PasswordStrengthFlags.TOO_SHORT
            )
        }
        val chars = decodeUtf8Lossy(password)
        try {
            return evaluateChars(chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    /** UTF-8 解码为可清零的 `CharArray`（非法字节按 U+FFFD 替换，对齐 Rust `from_utf8_lossy`）。 */
    private fun decodeUtf8Lossy(bytes: ByteArray): CharArray {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val buffer = decoder.decode(ByteBuffer.wrap(bytes))
        val chars = CharArray(buffer.remaining())
        buffer.get(chars)
        if (buffer.hasArray()) buffer.array().fill('\u0000')
        return chars
    }

    /**
     * `strength.rs::estimate_str` 的直译：判据顺序与 Rust 逐条对应。
     *
     * 顺序**不可交换**——[applyCommonPassword] 与
     * [PasswordStrengthFallbackPatterns.applyPeriodicRepeat] 用 `min` 收口，其余为线性扣减，
     * 故必须按 Rust 的先后执行。
     */
    private fun evaluateChars(chars: CharArray): PasswordStrength {
        val len = chars.size
        if (len == 0) {
            return PasswordStrength(PasswordStrengthEvaluator.SCORE_MIN, 0.0, PasswordStrengthFlags.TOO_SHORT)
        }
        val analyzedLen = if (len < MAX_ANALYZED_CHARS) len else MAX_ANALYZED_CHARS
        val excess = len - analyzedLen

        val acc = Acc(0, 0.0)
        if (len < MIN_RECOMMENDED_LEN) acc.flags = acc.flags or PasswordStrengthFlags.TOO_SHORT

        val profile = charsetProfile(chars, analyzedLen)
        if (profile.classCount == 1) acc.flags = acc.flags or PasswordStrengthFlags.SINGLE_CHAR_CLASS
        acc.log10 = analyzedLen.toDouble() * log2(profile.size.toDouble()) / LOG2_10

        applyCommonPassword(acc, chars, analyzedLen)
        PasswordStrengthFallbackPatterns.applyPeriodicRepeat(acc, chars, len)
        PasswordStrengthFallbackPatterns.applySubtractivePatterns(acc, chars, analyzedLen)
        if (excess > 0) acc.log10 -= EXCESS_PENALTY_PER_CHAR * excess

        val log10 = if (acc.log10 > 0.0) acc.log10 else 0.0
        return PasswordStrength(
            score = minOf(scoreOf(log10), lengthCap(len)),
            guessesLog10 = clampX100(log10) / 100.0,
            flags = acc.flags
        )
    }

    /** 常见口令判定（完全命中 → 上限 `1.0`；剥除尾部数字 / 标点后命中 → 上限 `4.0`）。 */
    private fun applyCommonPassword(acc: Acc, chars: CharArray, len: Int) {
        if (matchesCommonTable(chars, len)) {
            acc.flags = acc.flags or PasswordStrengthFlags.COMMON_PASSWORD
            acc.log10 = minOf(acc.log10, 1.0)
            return
        }
        val stemLen = trimmedAsciiStemLength(chars, len)
        if (stemLen in 1 until len && matchesCommonTable(chars, stemLen)) {
            acc.flags = acc.flags or PasswordStrengthFlags.COMMON_PASSWORD
            acc.log10 = minOf(acc.log10, 4.0)
        }
    }

    /**
     * 前缀是否命中常见口令表。
     *
     * 表项恒为 ASCII 小写，故前缀含非 ASCII 字符时**不可能命中**（Rust 侧
     * `flat_map(to_lowercase).eq(table)` 的直接推论）——此处等价地提前返回，避免全表扫描。
     */
    private fun matchesCommonTable(chars: CharArray, len: Int): Boolean {
        for (i in 0 until len) if (chars[i].code >= ASCII_MAX) return false
        for (table in COMMON_PASSWORDS) {
            if (table.size == len && tableMatches(chars, table)) return true
        }
        return false
    }

    private fun tableMatches(chars: CharArray, table: ByteArray): Boolean {
        for (i in table.indices) {
            if (asciiLower(chars[i]) != (table[i].toInt() and 0xFF)) return false
        }
        return true
    }

    /** ASCII 小写化（非 ASCII 原样返回）；表项恒为 ASCII 小写，故与 Rust 语义一致。 */
    private fun asciiLower(c: Char): Int {
        val v = c.code
        return if (v in 0x41..0x5A) v + 32 else v
    }

    /** 剥除尾部「ASCII 数字或标点」后的长度（对齐 Rust `trim_end_matches`，**不含空白**）。 */
    private fun trimmedAsciiStemLength(chars: CharArray, len: Int): Int {
        var end = len
        while (end > 0 && isAsciiDigitOrPunctuation(chars[end - 1])) end--
        return end
    }

    /** 对齐 Rust `is_ascii_digit() || is_ascii_punctuation()`（标点为 ASCII 可见标点，不含控制字符与空白）。 */
    private fun isAsciiDigitOrPunctuation(c: Char): Boolean {
        val v = c.code
        return (v in 0x30..0x39) ||
            (v in 0x21..0x2F) || (v in 0x3A..0x40) ||
            (v in 0x5B..0x60) || (v in 0x7B..0x7E)
    }

    /** 字符集画像（规模 + 类别数）。 */
    internal class CharsetProfile(val size: Int, val classCount: Int)

    /**
     * 字符集规模与类别数（对齐 `strength.rs::charset_profile_str`）。
     *
     * 类别宽度：小写 `26` / 大写 `26` / 数字 `10` / ASCII 符号 `33` / 空白 `1` / 非 ASCII `100`
     * （非 ASCII 保守取 100，不放大非拉丁语系强度）。
     */
    internal fun charsetProfile(chars: CharArray, len: Int): CharsetProfile {
        var lower = false
        var upper = false
        var digit = false
        var symbol = false
        var space = false
        var other = false
        for (i in 0 until len) {
            val c = chars[i]
            val v = c.code
            when {
                v in 0x61..0x7A -> lower = true
                v in 0x41..0x5A -> upper = true
                v in 0x30..0x39 -> digit = true
                isUnicodeWhitespace(c) -> space = true
                v < ASCII_MAX -> symbol = true
                else -> other = true
            }
        }
        var size = 0
        var classes = 0
        if (lower) {
            size += 26
            classes++
        }
        if (upper) {
            size += 26
            classes++
        }
        if (digit) {
            size += 10
            classes++
        }
        if (symbol) {
            size += 33
            classes++
        }
        if (space) {
            size += 1
            classes++
        }
        if (other) {
            size += 100
            classes++
        }
        return CharsetProfile(size, classes)
    }

    /** 近似 Unicode `White_Space`（对齐 Rust `char::is_whitespace`；差异边界见类 KDoc）。 */
    internal fun isUnicodeWhitespace(c: Char): Boolean =
        c.isWhitespace() || Character.isSpaceChar(c)

    /** 分档映射（阈值按 `log10(猜测次数)`），与 Rust `score_of` 同阈值。 */
    private fun scoreOf(log10: Double): Int = when {
        log10 < 3.0 -> 0
        log10 < 6.0 -> 1
        log10 < 8.0 -> 2
        log10 < 11.0 -> 3
        else -> SCORE_MAX
    }

    /** 长度分档上限（**策略项，非熵推导**，与 Rust `length_cap` 同策略）。 */
    private fun lengthCap(len: Int): Int = when {
        len < MIN_RECOMMENDED_LEN -> 2
        len < LONG_ENOUGH_LEN -> 3
        else -> SCORE_MAX
    }

    /** `log10 × 100` 定点化（饱和到 `i32` 安全区，防溢出而非语义裁剪），与 Rust `clamp_x100` 同口径。 */
    private fun clampX100(log10: Double): Int {
        val scaled = Math.round(log10 * 100.0)
        return when {
            scaled <= 0L -> 0
            scaled >= 1_000_000L -> 1_000_000
            else -> scaled.toInt()
        }
    }

    /** `log2`（供基线与周期单元复用），与 Rust `f64::log2` 同义（ULP 级差异见类 KDoc）。 */
    internal fun log2(value: Double): Double = Math.log(value) / Math.log(2.0)

    /** `log10`（供周期重复判据使用），与 Rust `f64::log10` 同义（ULP 级差异见类 KDoc）。 */
    internal fun log10Of(value: Double): Double = Math.log10(value)
}
