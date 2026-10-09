package com.keepasskey.crypto.strength

import kotlin.math.abs

/**
 * 降级实现的**模式判据**部分——`strength.rs` 的模式惩罚段（重复段 / 顺序段 / 键盘行走 / 日期形状 /
 * 周期重复 / 低唯一率）的直译。
 *
 * 本文件即 B3 裁决（2026-10-09）中「补齐的模式分析」：原降级实现只做长度 / 常见口令 / 字符类别 /
 * 唯一率四类判定，与原生内核存在**语义漂移**；直译后两侧在同一输入集上给出相同的标志位与分档。
 * 各判据的阈值、跨类别 / 跨排判定、单趟扫描语义均与 Rust 逐条对应，对应关系见各函数 KDoc。
 *
 * 所有函数都在调用方给定的 `CharArray` 上只读遍历，不持有引用、不构造 `String`。
 */
internal object PasswordStrengthFallbackPatterns {

    /** 同字符重复段的判定阈值（`strength.rs::REPEAT_RUN_MIN`）。 */
    private const val REPEAT_RUN_MIN = 3

    /** 单调顺序段的判定阈值（`strength.rs::SEQUENCE_RUN_MIN`）。 */
    private const val SEQUENCE_RUN_MIN = 3

    /** 键盘相邻行走段的判定阈值（`strength.rs::KEYBOARD_WALK_MIN`）。 */
    private const val KEYBOARD_WALK_MIN = 4

    /** 周期重复块的最小重复次数（`strength.rs::PERIODIC_MIN_REPEATS`）。 */
    private const val PERIODIC_MIN_REPEATS = 3

    /** 顺序段类别标签：必须**同类别**（与 Rust `CLASS_LOWER/CLASS_UPPER/CLASS_DIGIT` 同值）。 */
    private const val CLASS_LOWER = 0
    private const val CLASS_UPPER = 1
    private const val CLASS_DIGIT = 2

    /** 数字字符 `'0'` 的码点，用于两位数 / 四位年份的数值化。 */
    private const val DIGIT_ZERO = 0x30

    /**
     * 键盘四排（数字行 + 三字母行），与 `strength.rs::KEYBOARD_ROWS` **逐字符相同**
     * （含行尾的 `-` `=` `\` `;` `'` `,` `.` `/` 位）。
     */
    private val KEYBOARD_ROWS = listOf(
        "`1234567890-=",
        "qwertyuiop[]\\",
        "asdfghjkl;'",
        "zxcvbnm,./"
    )

    /**
     * 整串周期性判据（`strength.rs` 的 `if let Some((unit_bytes, repeats))` 段）。
     *
     * **须先于单段惩罚执行**——它把整串按「一个单元」重估（`min` 收口）；且在**全量字符**上判定、
     * 不受 `MAX_ANALYZED_CHARS` 截断影响，否则 `abc × 100` 一类超长重复块会因截断后不整除而漏判。
     */
    fun applyPeriodicRepeat(acc: PasswordStrengthFallback.Acc, chars: CharArray, len: Int) {
        val period = minimalPeriod(chars, len) ?: return
        val unitLen = period.first
        val repeats = period.second
        if (repeats < PERIODIC_MIN_REPEATS) return
        val unitSize = PasswordStrengthFallback.charsetProfile(chars, unitLen).size
        val unitLog10 = unitLen.toDouble() * PasswordStrengthFallback.log2(unitSize.toDouble()) /
            PasswordStrengthFallback.LOG2_10
        acc.flags = acc.flags or PasswordStrengthFlags.PERIODIC_REPEAT
        acc.log10 = minOf(
            acc.log10,
            unitLog10 + PasswordStrengthFallback.log10Of(repeats.toDouble())
        )
    }

    /**
     * 线性扣减型模式判据（重复段 / 顺序段 / 键盘行走 / 日期形状 / 低唯一率）。
     *
     * 各项均为 `log10 -= 系数 × 规模` 形式，彼此可交换，但**内部顺序仍按 Rust** 以利对照。
     */
    fun applySubtractivePatterns(acc: PasswordStrengthFallback.Acc, chars: CharArray, len: Int) {
        applyRepeatRun(acc, chars, len)
        applySequenceRun(acc, chars, len)
        applyKeyboardWalk(acc, chars, len)
        applyDateLike(acc, chars, len)
        applyLowUniqueRatio(acc, chars, len)
    }

    /** 同字符重复段（`aaaa` → 4）：`log10 -= 0.8 × 段长`。 */
    private fun applyRepeatRun(acc: PasswordStrengthFallback.Acc, chars: CharArray, len: Int) {
        val run = longestRepeatRun(chars, len)
        if (run >= REPEAT_RUN_MIN) {
            acc.flags = acc.flags or PasswordStrengthFlags.REPEATED_RUN
            acc.log10 -= 0.8 * run
        }
    }

    /** 单调顺序段（`abc` / `321`）：`log10 -= 0.7 × 段长`。 */
    private fun applySequenceRun(acc: PasswordStrengthFallback.Acc, chars: CharArray, len: Int) {
        val run = longestSequenceRun(chars, len)
        if (run >= SEQUENCE_RUN_MIN) {
            acc.flags = acc.flags or PasswordStrengthFlags.SEQUENCE
            acc.log10 -= 0.7 * run
        }
    }

    /** 键盘相邻行走段（`qwert`）：`log10 -= 0.9 × 段长`。 */
    private fun applyKeyboardWalk(acc: PasswordStrengthFallback.Acc, chars: CharArray, len: Int) {
        val walk = longestKeyboardWalk(chars, len)
        if (walk >= KEYBOARD_WALK_MIN) {
            acc.flags = acc.flags or PasswordStrengthFlags.KEYBOARD_WALK
            acc.log10 -= 0.9 * walk
        }
    }

    /** 日期 / 年份形状：整串可解释为年份或 `YYYYMMDD` 一族 → `-4.0`；仅内含四位年份 → `-2.0`。 */
    private fun applyDateLike(acc: PasswordStrengthFallback.Acc, chars: CharArray, len: Int) {
        val weight = dateLikeWeight(chars, len) ?: return
        acc.flags = acc.flags or PasswordStrengthFlags.DATE_LIKE
        acc.log10 -= weight
    }

    /** 字符唯一率过低：`log10 -= (0.5 - 唯一率) × 8.0`（严格小于 `0.5` 才触发）。 */
    private fun applyLowUniqueRatio(acc: PasswordStrengthFallback.Acc, chars: CharArray, len: Int) {
        val unique = uniqueCharCount(chars, len)
        val uniqueRatio = unique.toDouble() / len.toDouble()
        val threshold = PasswordStrengthEvaluator.LOW_UNIQUE_RATIO
        if (uniqueRatio < threshold) {
            acc.flags = acc.flags or PasswordStrengthFlags.LOW_UNIQUE_RATIO
            acc.log10 -= (threshold - uniqueRatio) * 8.0
        }
    }

    /**
     * 最长同字符重复段长度（`aaaa` → 4），单趟 O(n)（issuance `ISSUE-P2-468` 直译）。
     */
    private fun longestRepeatRun(chars: CharArray, len: Int): Int {
        var best = 0
        var cur = 0
        var hasPrev = false
        var prev = '\u0000'
        for (i in 0 until len) {
            val c = chars[i]
            cur = if (hasPrev && c == prev) cur + 1 else 1
            if (cur > best) best = cur
            prev = c
            hasPrev = true
        }
        return best
    }

    /**
     * 最长单调顺序段长度（步长 ±1，步长方向须一致）。
     *
     * 与 Rust `longest_sequence_run_str` 同语义：`continued = step 存在 && (cur == 1 || step == prevStep)`。
     */
    private fun longestSequenceRun(chars: CharArray, len: Int): Int {
        var best = 0
        var cur = 0
        var hasPrev = false
        var prev = '\u0000'
        var prevStep: Int? = null
        for (i in 0 until len) {
            val c = chars[i]
            if (!hasPrev) {
                cur = 1
            } else {
                val step = stepOf(prev, c)
                cur = if (step != null && (cur == 1 || step == prevStep)) cur + 1 else 1
                prevStep = step
            }
            if (cur > best) best = cur
            prev = c
            hasPrev = true
        }
        return best
    }

    /**
     * 相邻字符在**同类别内**的索引差（±1 视为顺序步长），跨类别返回 `null`。
     *
     * 必须判类别：`C`（大写第 2 位）与 `3`（数字第 3 位）索引差恰为 1，但无顺序关系
     * （Rust 侧自测已锁定该负例 `wC3gJ8mR`）。
     */
    private fun stepOf(a: Char, b: Char): Int? {
        val indexA = classIndex(a) ?: return null
        val indexB = classIndex(b) ?: return null
        if (indexA.first != indexB.first) return null
        val d = indexB.second - indexA.second
        return if (d == 1 || d == -1) d else null
    }

    /** 字符的（类别, 该类字母表内序号）：小写 `a..z` / 大写 `A..Z` / 数字 `0..9`；其余 `null`。 */
    private fun classIndex(c: Char): Pair<Int, Int>? {
        val v = c.code
        return when {
            v in 0x61..0x7A -> CLASS_LOWER to (v - 0x61)
            v in 0x41..0x5A -> CLASS_UPPER to (v - 0x41)
            v in 0x30..0x39 -> CLASS_DIGIT to (v - 0x30)
            else -> null
        }
    }

    /**
     * 最长键盘相邻行走段长度（**同排内**横向相邻，单趟 O(n)）。
     *
     * **必须判排**：展平拼接后上一排末位与下一排首位索引相邻，若不判排会把跨排组合误判为行走
     * （Rust 侧负例 `0qPz7w`）。
     */
    private fun longestKeyboardWalk(chars: CharArray, len: Int): Int {
        var best = 0
        var cur = 0
        var hasPrev = false
        var prevRow = 0
        var prevCol = 0
        for (i in 0 until len) {
            val index = keyboardIndex(chars[i])
            if (index == null) {
                // 非键盘字符打断当前行走段（与 Rust 的 `break` 语义一致）
                cur = 0
                hasPrev = false
                continue
            }
            val row = index.first
            val col = index.second
            cur = if (hasPrev && prevRow == row && abs(prevCol - col) == 1) cur + 1 else 1
            if (cur > best) best = cur
            prevRow = row
            prevCol = col
            hasPrev = true
        }
        return best
    }

    /** 字符在键盘中的（排号, 列号）；不在任何排则 `null`（大小写不敏感，对齐 Rust `to_ascii_lowercase`）。 */
    private fun keyboardIndex(c: Char): Pair<Int, Int>? {
        val lower = if (c.code in 0x41..0x5A) (c.code + 32).toChar() else c
        for (row in KEYBOARD_ROWS.indices) {
            val col = KEYBOARD_ROWS[row].indexOf(lower)
            if (col >= 0) return row to col
        }
        return null
    }

    /** 日期 / 年份形状的惩罚权重；不呈日期形状返回 `null`（对齐 `strength.rs::date_like_weight_str`）。 */
    private fun dateLikeWeight(chars: CharArray, len: Int): Double? {
        if (len > 0 && allAsciiDigits(chars, 0, len)) {
            val whole = wholeDateShapeWeight(chars, len)
            if (whole != null) return whole
        }
        if (len >= 4) {
            var start = 0
            while (start + 4 <= len) {
                if (allAsciiDigits(chars, start, start + 4) && isYear(chars, start)) return 2.0
                start++
            }
        }
        return null
    }

    /** 整串为 4/6/8 位数字且可解释为年份 / `YYYYMMDD` / `YYMMDD` → `4.0`，否则 `null`。 */
    private fun wholeDateShapeWeight(chars: CharArray, len: Int): Double? = when (len) {
        4 -> if (isYear(chars, 0)) 4.0 else null
        6 -> if (isMonthDay(chars, 0)) 4.0 else null
        8 -> if (isYear(chars, 0) && isMonthDay(chars, 4)) 4.0 else null
        else -> null
    }

    /** `MM` + `DD` 组合是否合法（月 `1..=12`、日 `1..=31`，与 Rust 同宽松度、不校验月末）。 */
    private fun isMonthDay(chars: CharArray, start: Int): Boolean {
        val month = twoDigits(chars, start)
        val day = twoDigits(chars, start + 2)
        return month in 1..12 && day in 1..31
    }

    /** 四位数字是否为合理年份（`1900..=2099`，对齐 Rust `is_year`）。 */
    private fun isYear(chars: CharArray, start: Int): Boolean {
        val year = (chars[start].code - DIGIT_ZERO) * 1000 +
            (chars[start + 1].code - DIGIT_ZERO) * 100 +
            (chars[start + 2].code - DIGIT_ZERO) * 10 +
            (chars[start + 3].code - DIGIT_ZERO)
        return year in 1900..2099
    }

    private fun twoDigits(chars: CharArray, start: Int): Int =
        (chars[start].code - DIGIT_ZERO) * 10 + (chars[start + 1].code - DIGIT_ZERO)

    private fun allAsciiDigits(chars: CharArray, from: Int, to: Int): Boolean {
        for (i in from until to) if (chars[i].code !in 0x30..0x39) return false
        return true
    }

    /**
     * 不同字符个数（对齐 Rust `unique_char_count_str`）：ASCII 位图 + 非 ASCII 小表。
     *
     * 非 ASCII 表容量与 `MAX_ANALYZED_CHARS` 同阶（截断后前缀恒可容纳）；表满后新增非 ASCII
     * 字符**不再计数**（与 Rust 相同的边界语义）。含秘密字符的小表用毕清零。
     */
    private fun uniqueCharCount(chars: CharArray, len: Int): Int {
        val asciiSeen = BooleanArray(ASCII_MAX)
        val others = CharArray(MAX_ANALYZED_CHARS)
        var othersLen = 0
        var count = 0
        try {
            for (i in 0 until len) {
                val c = chars[i]
                val v = c.code
                if (v < ASCII_MAX) {
                    if (!asciiSeen[v]) {
                        asciiSeen[v] = true
                        count++
                    }
                } else if (!containsChar(others, othersLen, c) && othersLen < MAX_ANALYZED_CHARS) {
                    others[othersLen] = c
                    othersLen++
                    count++
                }
            }
        } finally {
            others.fill('\u0000')
        }
        return count
    }

    private fun containsChar(table: CharArray, len: Int, c: Char): Boolean {
        for (i in 0 until len) if (table[i] == c) return true
        return false
    }

    /**
     * 最小整周期 `(单位长度, 重复次数)`；非整周期串返回 `null`。
     *
     * 与 Rust `minimal_period_bytes` 同算法（`ISSUE-P3-480` 的 O(1) 额外空间因子约简）：
     * 由 Fine–Wilf 引理，极小周期整除任何整周期，故 `p` 自 `len` 起步、枚举 `len` 的素因子
     * 反复约简即得最小整周期。只接受**恰好整周期**（`p | len` 且逐字符相等），
     * 不把「偶然重复前缀」误判为周期串。
     *
     * 字符级与 Rust 的字节级**逐例等价**：UTF-8 前导 / 后续字节区间不交叠 ⇒ 字节级整周期必对齐
     * 字符边界，两侧 `repeats` 同值。
     */
    private fun minimalPeriod(chars: CharArray, len: Int): Pair<Int, Int>? {
        if (len < 2) return null
        var p = len
        var remaining = len
        var factor = 2
        while (factor <= remaining / factor) {
            if (remaining % factor == 0) {
                while (remaining % factor == 0) remaining /= factor
                while (p % factor == 0 && isPeriod(chars, len, p / factor)) p /= factor
            }
            factor++
        }
        if (remaining > 1) {
            while (p % remaining == 0 && isPeriod(chars, len, p / remaining)) p /= remaining
        }
        if (p == len) return null
        val repeats = len / p
        return if (repeats < 2) null else p to repeats
    }

    /** `chars` 是否以 `p` 为周期（`chars[i] == chars[i + p]`）。O(len)、O(1) 空间。 */
    private fun isPeriod(chars: CharArray, len: Int, p: Int): Boolean {
        for (i in 0 until len - p) if (chars[i] != chars[i + p]) return false
        return true
    }

    private const val ASCII_MAX = 128
    private const val MAX_ANALYZED_CHARS = 256
}
