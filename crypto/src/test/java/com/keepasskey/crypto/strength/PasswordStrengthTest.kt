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
     * **已知差异如实锁定**：降级实现只做「长度 / 常见口令 / 字符类别 / 唯一率」四类判定，
     * 不含顺序段、重复段、键盘行走、周期重复等模式分析，因此下列口令在
     * 原生路径上判弱、在降级路径上不判弱。
     *
     * 这不构成缺陷（降级即接线前的既有行为，`ISSUE-P3-36` 正文与
     * [PasswordStrengthFallback] 的 KDoc 均如实声明），但必须被用例锁定：
     * 若将来有人补齐降级实现的模式分析，本用例会失败并提示同步更新文档。
     */
    @Test
    fun `降级路径不覆盖模式分析——差异如实锁定`() {
        for (pw in listOf("abcdefgh", "aaaaaaaaaa")) {
            val bytes = pw.toByteArray(Charsets.US_ASCII)
            assertTrue("[$pw] 原生路径应判弱", PasswordStrengthEvaluator.evaluate(bytes).isWeak)
            assertFalse("[$pw] 降级路径按设计不判弱（差异已在文档中声明）", PasswordStrengthFallback.evaluate(bytes).isWeak)
        }
    }

    @Test
    fun `降级实现永不抛异常`() {
        PasswordStrengthFallback.evaluate(ByteArray(0))
        PasswordStrengthFallback.evaluate(byteArrayOf(0xFF.toByte(), 0x00, 0x80.toByte()))
        PasswordStrengthFallback.evaluate(ByteArray(10_000) { it.toByte() })
        PasswordStrengthFallback.evaluate("口令密码".toByteArray())
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
}
