package com.keepasskey.crypto.strength

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * 口令强度评估用例（ISSUE-P3-36）。
 *
 * 三层断言：
 * 1. **可观测行为**（分档 / 标志位）——原生与降级两条路径都必须满足，不依赖原生库；
 * 2. **跨语言契约**——标志位取值必须与 Rust 侧 `FLAG_*` 逐位一致（借原生返回的真实位值锁定）；
 * 3. **降级不劣于接线前**——原有「长度 < 8 / 命中常见口令表」两条判据必须被完整保留。
 */
class PasswordStrengthTest {

    /** 接线前 `HealthCheckEngine` 的常见弱口令表（15 条，必须仍全部被判弱）。 */
    private val legacyCommonPasswords = listOf(
        "123456", "password", "12345678", "qwerty", "123456789",
        "12345", "1234", "111111", "1234567", "dragon",
        "welcome", "admin", "admin123", "root", "pass123"
    )

    private val definedFlags = PasswordStrengthFlags.COMMON_PASSWORD or
        PasswordStrengthFlags.TOO_SHORT or
        PasswordStrengthFlags.REPEATED_RUN or
        PasswordStrengthFlags.SEQUENCE or
        PasswordStrengthFlags.KEYBOARD_WALK or
        PasswordStrengthFlags.DATE_LIKE or
        PasswordStrengthFlags.SINGLE_CHAR_CLASS or
        PasswordStrengthFlags.LOW_UNIQUE_RATIO or
        PasswordStrengthFlags.PERIODIC_REPEAT

    // ==================== 可观测行为 ====================

    @Test
    fun `常见弱口令分档最低且命中常见口令标志`() {
        for (pw in legacyCommonPasswords) {
            val strength = PasswordStrengthEvaluator.evaluate(pw.toByteArray(Charsets.US_ASCII))
            assertEquals("[$pw] 应属最低档", PasswordStrengthEvaluator.SCORE_MIN, strength.score)
            assertTrue("[$pw] 应命中常见口令标志", strength.isCommonPassword)
        }
    }

    @Test
    fun `常见弱口令大小写不敏感`() {
        assertTrue(PasswordStrengthEvaluator.evaluate("PASSWORD".toByteArray()).isCommonPassword)
        assertTrue(PasswordStrengthEvaluator.evaluate("Qwerty".toByteArray()).isCommonPassword)
    }

    @Test
    fun `长随机口令分档最高`() {
        for (pw in listOf("tR7#kL9@mQ2!xZ4&vB6*", "Xk92!mQp#7Lz@4Rt&8Wn")) {
            val strength = PasswordStrengthEvaluator.evaluate(pw.toByteArray(Charsets.US_ASCII))
            assertEquals("[$pw] 应属最高档", PasswordStrengthEvaluator.SCORE_MAX, strength.score)
            assertEquals("[$pw] 不应命中常见口令表", false, strength.isCommonPassword)
        }
    }

    @Test
    fun `空口令为最低档并带长度不足标志`() {
        val strength = PasswordStrengthEvaluator.evaluate(ByteArray(0))
        assertEquals(PasswordStrengthEvaluator.SCORE_MIN, strength.score)
        assertTrue(strength.isTooShort)
        assertTrue(strength.isWeak)
        assertEquals(0.0, strength.guessesLog10, 0.0)
    }

    @Test
    fun `标志位始终落在已定义位掩码内`() {
        val samples = listOf(
            "", "password", "123456", "qwertyuiop", "abcdefgh", "20260101",
            "abcabcabc", "aaaa", "tR7#kL9@mQ2!xZ4&vB6*", "Password1!"
        )
        for (pw in samples) {
            val strength = PasswordStrengthEvaluator.evaluate(pw.toByteArray(Charsets.US_ASCII))
            assertEquals("[$pw] 出现未定义标志位", 0, strength.flags and definedFlags.inv())
            assertTrue("[$pw] 分档越界", strength.score in PasswordStrengthEvaluator.SCORE_MIN..PasswordStrengthEvaluator.SCORE_MAX)
        }
    }

    // ==================== 判据不劣于接线前 ====================

    @Test
    fun `长度不足与常见口令两条旧规则被完整保留`() {
        for (pw in legacyCommonPasswords) {
            assertTrue(
                "[$pw] 接线前判为弱，现在必须仍为弱",
                PasswordStrengthEvaluator.isWeak(pw.toByteArray(Charsets.US_ASCII))
            )
        }
        // 长度规则：任意 7 位口令都应为弱
        assertTrue(PasswordStrengthEvaluator.isWeak("aB3\$kL".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `旧实现无感的新增弱口令现在能被识别`() {
        // 这三条都**不在**接线前的 15 条表内、且长度 >= 8，旧实现会漏判
        for (pw in listOf("qwertyuiop", "abcabcabc", "20260101")) {
            assertFalse("[$pw] 不应出现在旧表内", legacyCommonPasswords.contains(pw))
            assertTrue("[$pw] 新实现应识别为弱口令", PasswordStrengthEvaluator.isWeak(pw.toByteArray(Charsets.US_ASCII)))
        }
    }

    @Test
    fun `正常强度口令不被误判为弱`() {
        for (pw in listOf("StrongPass#2026!", "AnotherStrong#2026!", "correct-Horse_42-battery")) {
            assertFalse("[$pw] 不应被判为弱口令", PasswordStrengthEvaluator.isWeak(pw.toByteArray(Charsets.US_ASCII)))
        }
    }

    // ==================== 降级路径 ====================

    @Test
    fun `降级实现同样覆盖长度与常见口令两条判据`() {
        for (pw in legacyCommonPasswords) {
            val strength = PasswordStrengthFallback.evaluate(pw.toByteArray(Charsets.US_ASCII))
            assertTrue("[$pw] 降级路径应命中常见口令", strength.isCommonPassword)
            assertTrue("[$pw] 降级路径应判为弱", strength.isWeak)
        }
        assertTrue(PasswordStrengthFallback.evaluate("aB3\$".toByteArray(Charsets.US_ASCII)).isTooShort)
    }

    @Test
    fun `降级实现不构造 String 也能识别大小写变体`() {
        assertTrue(PasswordStrengthFallback.evaluate("PASSWORD".toByteArray()).isCommonPassword)
        assertTrue(PasswordStrengthFallback.evaluate("Pass123".toByteArray()).isCommonPassword)
        // 近似命中：尾部数字/符号被剥除后命中
        assertTrue(PasswordStrengthFallback.evaluate("password1".toByteArray()).isCommonPassword)
        assertTrue(PasswordStrengthFallback.evaluate("qwerty!".toByteArray()).isCommonPassword)
    }

    /**
     * **共同覆盖面**（长度 / 常见口令 / 字符类别 / 唯一率）上两条路径判弱结论必须一致——
     * 降级路径绝不允许放行原生判弱的字符串。
     */
    @Test
    fun `原生与降级在共同覆盖面上的关键判定一致`() {
        val samples = listOf(
            "", "password", "PASSWORD", "123456", "qwerty", "12345678", "admin123",
            "aB3\$", "StrongPass#2026!", "tR7#kL9@mQ2!xZ4&vB6*", "AnotherStrong#2026!"
        )
        for (pw in samples) {
            val bytes = pw.toByteArray(Charsets.US_ASCII)
            val native = PasswordStrengthEvaluator.evaluate(bytes).isWeak
            val fallback = PasswordStrengthFallback.evaluate(bytes).isWeak
            assertEquals("[$pw] 原生与降级判弱结论不一致", native, fallback)
        }
    }

    /**
     * **两侧等价锁定**（原「降级路径不覆盖模式分析——差异如实锁定」用例的改造，B3 裁决 2026-10-09）。
     *
     * 历史：降级实现原为「只做长度 / 常见口令 / 字符类别 / 唯一率四类判定」的独立近似，本用例当时
     * 锁定「原生判弱、降级不判弱」的既有差异，并在 KDoc 中预告「若将来有人补齐降级实现的模式分析，
     * 本用例会失败并提示同步更新文档」。B3 裁决把降级改为 `strength.rs` 的**直译**后差异已消除，
     * 本用例随之改造为**等价锁定**（不删只改，测试资产纪律 §147）。
     */
    @Test
    fun `降级路径已直译覆盖模式分析——两侧判弱结论一致`() {
        for (pw in listOf("abcdefgh", "aaaaaaaaaa", "abcabcabc", "qwertyui", "20260101")) {
            val bytes = pw.toByteArray(Charsets.US_ASCII)
            assertTrue("[$pw] 原生路径应判弱", PasswordStrengthEvaluator.evaluate(bytes).isWeak)
            assertTrue("[$pw] 降级路径应判弱（直译后两侧同判据）", PasswordStrengthFallback.evaluate(bytes).isWeak)
        }
    }

    /**
     * **两侧直译等价（跨语言）**：直译后同一输入集上分档与标志位必须逐条相同。
     *
     * 宿主 `cargoHostBuild` 产物缺失时按既有跨语言契约口径 `Assume` 跳过（与其余跨语言用例同款）；
     * CI `:crypto:test` 有零跳过硬断言，故该执行点在 CI 必真跑。
     * `guessesLog10` 因两侧 `log2` / `log10` 分属不同 libm 实现，按定点容差比较（见类 KDoc）。
     */
    @Test
    fun `降级路径与原生在同输入集上分档与标志位逐条一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过跨语言契约用例", NativePasswordStrength.available)
        for (pw in EQUIVALENCE_CORPUS) {
            val bytes = pw.toByteArray(Charsets.UTF_8)
            val native = NativePasswordStrength.evaluate(bytes)
            val fallback = PasswordStrengthFallback.evaluate(bytes)
            assertTrue(
                "[$pw] 分档不一致：原生 ${native.score} / 降级 ${fallback.score}",
                native.score == fallback.score
            )
            assertTrue(
                "[$pw] 标志位不一致：原生 0x${native.flags.toString(16)} / 降级 0x${fallback.flags.toString(16)}",
                native.flags == fallback.flags
            )
            assertEquals(
                "[$pw] log10 定点差超容差",
                native.guessesLog10,
                fallback.guessesLog10,
                LOG10_TOLERANCE
            )
            bytes.fill(0)
        }
    }

    /**
     * **Rust 行为向量移植**（B3 裁决 2026-10-09）：与 Rust 侧 `strength_tests.rs` 同款向量在
     * **降级路径**上逐条复算。
     *
     * 原生不可用的宿主环境下，这是唯一可执行的「直译是否忠于 Rust」锁：直译若偏离 Rust 的阈值、
     * 类别判定、分行判定或周期算法，本用例即报红。
     */
    @Test
    fun `降级实现满足Rust侧同款行为向量`() {
        // 分档已知答案
        for (pw in listOf("password", "123456", "qwerty", "111111", "abc123", "qwertyuiop")) {
            assertEquals("[${pw}] 应属最低档", 0, fallbackScore(pw))
        }
        for (pw in listOf("tR7#kL9@mQ2!xZ4&vB6*", "Xk92!mQp#7Lz@4Rt&8Wn")) {
            assertEquals("[$pw] 应属最高档", PasswordStrengthEvaluator.SCORE_MAX, fallbackScore(pw))
        }
        // 各模式正例 / 反例（与 Rust 用例同向量）
        assertFallbackFlag(PasswordStrengthFlags.REPEATED_RUN, "aaaaBBBBcccc", true)
        assertFallbackFlag(PasswordStrengthFlags.REPEATED_RUN, "aBcDeFgHiJ", false)
        assertFallbackFlag(PasswordStrengthFlags.SEQUENCE, "xyz987wvu", true)
        assertFallbackFlag(PasswordStrengthFlags.SEQUENCE, "abcdEFGH", true)
        assertFallbackFlag(PasswordStrengthFlags.SEQUENCE, "aC3fH9kM", false)
        assertFallbackFlag(PasswordStrengthFlags.SEQUENCE, "wxyz", true)
        assertFallbackFlag(PasswordStrengthFlags.SEQUENCE, "7890", true)
        assertFallbackFlag(PasswordStrengthFlags.SEQUENCE, "wC3gJ8mR", false)
        assertFallbackFlag(PasswordStrengthFlags.KEYBOARD_WALK, "qwertyui", true)
        assertFallbackFlag(PasswordStrengthFlags.KEYBOARD_WALK, "asdfghjk", true)
        assertFallbackFlag(PasswordStrengthFlags.KEYBOARD_WALK, "qazplmok", false)
        assertFallbackFlag(PasswordStrengthFlags.KEYBOARD_WALK, "0qPz7w", false)
        assertFallbackFlag(PasswordStrengthFlags.DATE_LIKE, "20260101", true)
        assertFallbackFlag(PasswordStrengthFlags.DATE_LIKE, "1999", true)
        assertFallbackFlag(PasswordStrengthFlags.DATE_LIKE, "user1990name", true)
        assertFallbackFlag(PasswordStrengthFlags.DATE_LIKE, "user1234name", false)
        assertFallbackFlag(PasswordStrengthFlags.SINGLE_CHAR_CLASS, "abcdefghijkl", true)
        assertFallbackFlag(PasswordStrengthFlags.SINGLE_CHAR_CLASS, "abcDEF123!@#", false)
        assertFallbackFlag(PasswordStrengthFlags.LOW_UNIQUE_RATIO, "aaaaaaaaaa", true)
        assertFallbackFlag(PasswordStrengthFlags.LOW_UNIQUE_RATIO, "aBcDeFgHiJkL", false)
        assertFallbackFlag(PasswordStrengthFlags.PERIODIC_REPEAT, "abcabcabc", true)
        assertFallbackFlag(PasswordStrengthFlags.PERIODIC_REPEAT, "xyxyxyxy", true)
        assertFallbackFlag(PasswordStrengthFlags.PERIODIC_REPEAT, "abcdefghi", false)
        assertFallbackFlag(PasswordStrengthFlags.COMMON_PASSWORD, "PASSWORD", true)
        assertFallbackFlag(PasswordStrengthFlags.COMMON_PASSWORD, "password1", true)
        assertFallbackFlag(PasswordStrengthFlags.COMMON_PASSWORD, "qwerty!", true)
        assertFallbackFlag(PasswordStrengthFlags.COMMON_PASSWORD, "tR7#kL9@mQ2!xZ4&vB6*", false)
        assertFallbackFlag(PasswordStrengthFlags.TOO_SHORT, "aB3\$", true)
        assertFallbackFlag(PasswordStrengthFlags.TOO_SHORT, "aB3\$kLmQ", false)
        // 周期重复串不得被评为强口令
        for (pw in listOf("abcabcabc", "xyxyxyxy")) {
            assertTrue("[$pw] 周期重复串不应被评为强口令", fallbackScore(pw) <= 1)
        }
        // 长度分档上限（策略项）
        assertTrue("6 位口令不应超过 2 档", fallbackScore("aB3\$kL") <= 2)
        assertTrue("10 位口令不应达到 4 档", fallbackScore("aB3\$kLmQ7#") <= 3)
        assertEquals(
            "12 位四类可到最高档",
            PasswordStrengthEvaluator.SCORE_MAX,
            fallbackScore("aB3\$kLmQ7#xZ")
        )
        // ISSUE-P2-58 陷阱 #7 防线：超长重复串不得被评为强口令，且须命中周期标志
        for (pw in listOf("1234".repeat(100), "abc".repeat(100), "a".repeat(100_000))) {
            val strength = PasswordStrengthFallback.evaluate(pw.toByteArray(Charsets.US_ASCII))
            assertTrue("[${pw.length} 字符重复串] 不得被评为强口令（实际 ${strength.score}）", strength.score <= 1)
            assertTrue(
                "[${pw.length} 字符重复串] 须命中周期标志",
                strength.flags and PasswordStrengthFlags.PERIODIC_REPEAT != 0
            )
        }
        // 唯一率恰为 0.5（95 互异可打印字符 × 2）不得触发低唯一率（位图无漏计）
        val printable = (0x20..0x7E).map { it.toChar() }.joinToString("")
        val twoRounds = printable + printable
        assertFallbackFlag(PasswordStrengthFlags.LOW_UNIQUE_RATIO, twoRounds, false)
        // 非 ASCII 唯一计数语义
        assertFallbackFlag(PasswordStrengthFlags.LOW_UNIQUE_RATIO, "αβγδεζηθ", false)
        assertFallbackFlag(PasswordStrengthFlags.LOW_UNIQUE_RATIO, "αααααααα", true)
        assertFallbackFlag(PasswordStrengthFlags.LOW_UNIQUE_RATIO, "aαbβcγdδeε", false)
        // 长且杂的随机串保持最高档（长度上限不得误伤）
        assertEquals(
            "长且杂的随机串应保持最高档",
            PasswordStrengthEvaluator.SCORE_MAX,
            fallbackScore(longMixedRandomLike(300))
        )
    }

    @Test
    fun `降级实现永不抛异常`() {
        PasswordStrengthFallback.evaluate(ByteArray(0))
        PasswordStrengthFallback.evaluate(byteArrayOf(0xFF.toByte(), 0x00, 0x80.toByte()))
        PasswordStrengthFallback.evaluate(ByteArray(10_000) { it.toByte() })
        PasswordStrengthFallback.evaluate("口令密码".toByteArray())
    }

    /** 降级路径分档（按 UTF-8 编码取字节，对齐 Rust 用例的 `pw.as_bytes()`）。 */
    private fun fallbackScore(pw: String): Int =
        PasswordStrengthFallback.evaluate(pw.toByteArray(Charsets.UTF_8)).score

    private fun assertFallbackFlag(flag: Int, pw: String, expected: Boolean) {
        val actual = PasswordStrengthFallback.evaluate(pw.toByteArray(Charsets.UTF_8)).flags and flag != 0
        assertEquals(
            "[$pw] 标志 0x${flag.toString(16)} 期望 $expected 实际 $actual",
            expected,
            actual
        )
    }

    /** 逐字符递增的混合族（字母 / 大写 / 数字 / 符号轮转），用于「长且杂」白名单对照（对齐 Rust 用例构造）。 */
    private fun longMixedRandomLike(length: Int): String = buildString {
        val symbols = charArrayOf('#', '$', '!', '%')
        for (i in 0 until length) {
            append(
                when (i % 4) {
                    0 -> 'a' + (i % 26)
                    1 -> 'A' + ((i * 7) % 26)
                    2 -> '0' + ((i * 3) % 10)
                    else -> symbols[i % 4]
                }
            )
        }
    }

    private companion object {
        /** 两侧 `log10` 的定点容差（跨语言 libm 差异；分档 / 标志位仍要求逐条相等）。 */
        private const val LOG10_TOLERANCE = 0.02

        /** 等价对拍输入集：覆盖各标志位正反例、边界长度、非 ASCII、非法 / 超长形态。 */
        private val EQUIVALENCE_CORPUS = listOf(
            "", "a", "aB3\$", "password", "PASSWORD", "password1", "qwerty!", "123456",
            "qwertyuiop", "abcdefgh", "abcdWXYZ", "aaaaBBBB", "aaaaaaaaaa", "abcabcabc",
            "xyxyxyxy", "user1990name", "1999", "20260101", "user1234name", "qwertyui",
            "asdfghjk", "qazplmok", "0qPz7w", "aC3fH9kM", "wC3gJ8mR", "7890", "wxyz",
            "tR7#kL9@mQ2!xZ4&vB6*", "Xk92!mQp#7Lz@4Rt&8Wn", "aB3\$kL", "aB3\$kLmQ7#",
            "correct-Horse_42-battery", "口令密码测试", "aαbβcγdδeε", "1234".repeat(100)
        )
    }

    // ==================== 跨语言契约 ====================

    @Test
    fun `原生返回的标志位与 Kotlin 常量逐位一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过跨语言契约用例", NativePasswordStrength.available)

        // 逐项构造命中单一标志的输入，断言原生返回的位值与 Kotlin 常量相同
        val cases = listOf(
            "password" to PasswordStrengthFlags.COMMON_PASSWORD,
            "aB3\$" to PasswordStrengthFlags.TOO_SHORT,
            "aB3\$kLmQ7#xZ9!" to null,
            "aaaaBBBB" to PasswordStrengthFlags.REPEATED_RUN,
            "abcdWXYZ" to PasswordStrengthFlags.SEQUENCE,
            "qwertyui" to PasswordStrengthFlags.KEYBOARD_WALK,
            "user1990name" to PasswordStrengthFlags.DATE_LIKE,
            "abcdefghij" to PasswordStrengthFlags.SINGLE_CHAR_CLASS,
            "aaaaaaaaaa" to PasswordStrengthFlags.LOW_UNIQUE_RATIO,
            "abcabcabc" to PasswordStrengthFlags.PERIODIC_REPEAT
        )
        for ((pw, flag) in cases) {
            if (flag == null) continue
            val flags = NativePasswordStrength.evaluate(pw.toByteArray(Charsets.US_ASCII)).flags
            assertTrue(
                "[$pw] 应置位 0x${flag.toString(16)}，实际 flags=0x${flags.toString(16)}",
                flags and flag != 0
            )
        }
    }

    @Test
    fun `原生返回的分档落在契约区间内`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过跨语言契约用例", NativePasswordStrength.available)
        for (pw in listOf("", "a", "password", "tR7#kL9@mQ2!xZ4&vB6*", "abcabcabc")) {
            val strength = NativePasswordStrength.evaluate(pw.toByteArray(Charsets.US_ASCII))
            assertTrue(
                "[$pw] 分档越界: ${strength.score}",
                strength.score >= PasswordStrengthEvaluator.SCORE_MIN && strength.score <= PasswordStrengthEvaluator.SCORE_MAX
            )
            assertTrue("[$pw] log10 应为非负", strength.guessesLog10 >= 0.0)
        }
    }

    // ==================== 跨语言契约：集合成员一致性（ISSUE-P3-506） ====================

    /**
     * `ISSUE-P3-506`：`PasswordStrengthFallback.COMMON_PASSWORDS` 原为 `private`，使
     * `PasswordStrength.kt` KDoc 的「两侧集合成员一致」承诺在**全仓无任何可执行点**。
     * 本用例冻结 Kotlin 侧集合的**条数与去重**（33 条，与 `strength.rs::COMMON_PASSWORDS` 同数；
     * Rust 侧由 `common_passwords_table_is_frozen_and_distinct` 冻结同一数字）。
     */
    @Test
    fun `常见口令表为已冻结的33条去重集合`() {
        val entries = PasswordStrengthFallback.commonPasswordsForTest()
        assertEquals("两侧词表条数须一致（冻结基线）", 33, entries.size)
        assertEquals(
            "常见口令表不得含重复条目",
            entries.size,
            entries.map { String(it, Charsets.US_ASCII) }.toSet().size
        )
        for (entry in entries) {
            val text = String(entry, Charsets.US_ASCII)
            assertEquals("常见口令表条目应为全小写 ASCII：[$text]", text, text.lowercase())
        }
    }

    /**
     * `ISSUE-P3-506`：经原生内核建立**跨语言**执行点——Kotlin 侧每条常见口令都必须被 Rust 内核
     * 判定为 `COMMON_PASSWORD`（Kotlin ⊆ Rust）；配合两侧并列的条数 / 去重冻结（|Kotlin| = |Rust| = 33）
     * 即得两侧集合**相等**，样本外漂移（任一侧新增 / 删除 / 改写）必被其中之一拦截。
     *
     * 原生不可用（未构建宿主库）时按既有跨语言契约口径 `Assume` 跳过；
     * CI `:crypto:test` 有零跳过硬断言（`build.yml`），故该执行点在 CI 必真跑。
     */
    @Test
    fun `常见口令表每条均被原生内核判定为常见口令`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过跨语言契约用例", NativePasswordStrength.available)
        for (entry in PasswordStrengthFallback.commonPasswordsForTest()) {
            val flags = NativePasswordStrength.evaluate(entry).flags
            assertTrue(
                "[${String(entry, Charsets.US_ASCII)}] 原生内核未判为常见口令，两侧词表可能已漂移",
                flags and PasswordStrengthFlags.COMMON_PASSWORD != 0
            )
        }
    }
}
