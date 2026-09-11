package com.keepasskey.core.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * ISSUE-P3-49：HOTP 解析与计数器递增单元测试。
 *
 * 覆盖：`otpauth://hotp/` 类型段与 `counter` 参数解析、TOTP 不受影响，
 * 以及 [HotpCounterSupport.incrementCounter] 的替换 / 追加 / fail-closed 分支。
 */
class HotpSupportTest {

    private fun bytes(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    private fun parse(s: String): ParsedTotpConfig? = TotpKeyUriParser.parse(bytes(s))

    private fun increment(s: String): String? =
        HotpCounterSupport.incrementCounter(s.toCharArray())?.concatToString()

    @Test
    fun `解析 HOTP URI 得到类型与计数器`() {
        val config = parse("otpauth://hotp/ACME:alice?secret=JBSWY3DPEHPK3PXP&counter=5&digits=6")
        assertTrue(config!!.isHotp)
        assertEquals(5L, config.counter)
        assertEquals(6, config.digits)
    }

    @Test
    fun `解析 TOTP URI 不标记为 HOTP`() {
        val config = parse("otpauth://totp/ACME:alice?secret=JBSWY3DPEHPK3PXP&period=30")
        assertFalse(config!!.isHotp)
        assertEquals(0L, config.counter)
    }

    @Test
    fun `纯 Base32 种子不是 HOTP`() {
        val config = parse("JBSWY3DPEHPK3PXP")
        assertFalse(config!!.isHotp)
    }

    @Test
    fun `递增已有计数器且保留其余参数与种子`() {
        val original = "otpauth://hotp/ACME:alice?secret=JBSWY3DPEHPK3PXP&counter=41&digits=8"
        val incremented = increment(original)!!
        assertEquals(
            "otpauth://hotp/ACME:alice?secret=JBSWY3DPEHPK3PXP&counter=42&digits=8",
            incremented
        )
        val reparsed = parse(incremented)!!
        assertEquals(42L, reparsed.counter)
        assertEquals(8, reparsed.digits)
        assertTrue(reparsed.secret.contentEquals(parse(original)!!.secret))
    }

    @Test
    fun `计数器为末位参数时同样可递增`() {
        assertEquals(
            "otpauth://hotp/l?secret=JBSWY3DPEHPK3PXP&counter=10",
            increment("otpauth://hotp/l?secret=JBSWY3DPEHPK3PXP&counter=9")
        )
    }

    @Test
    fun `缺省计数器时追加 counter 等于 1`() {
        val withQuery = increment("otpauth://hotp/l?secret=JBSWY3DPEHPK3PXP")!!
        assertEquals("otpauth://hotp/l?secret=JBSWY3DPEHPK3PXP&counter=1", withQuery)
        assertEquals(1L, parse(withQuery)!!.counter)
    }

    @Test
    fun `无查询串时以问号追加`() {
        assertEquals("otpauth://hotp/l?counter=1", increment("otpauth://hotp/l"))
    }

    @Test
    fun `非 ASCII 标签不被破坏`() {
        val original = "otpauth://hotp/示例:alice?secret=JBSWY3DPEHPK3PXP&counter=2"
        val incremented = increment(original)!!
        assertEquals("otpauth://hotp/示例:alice?secret=JBSWY3DPEHPK3PXP&counter=3", incremented)
        assertEquals(3L, parse(incremented)!!.counter)
    }

    @Test
    fun `非法输入一律 fail-closed 返回 null`() {
        assertNull(HotpCounterSupport.incrementCounter(CharArray(0)))
        assertNull(increment("https://example.com"))
        assertNull(increment("otpauth://hotp/l?secret=X&counter=abc"))
        assertNull(increment("otpauth://hotp/l?secret=X&counter="))
        assertNull(increment("otpauth://hotp/l?secret=X&counter=${Long.MAX_VALUE}"))
    }

    @Test
    fun `路径段中的 counter 字样不被误当作参数`() {
        // 标签里出现 counter=，但查询串无该参数 → 应追加而非替换标签
        val incremented = increment("otpauth://hotp/counter=9?secret=JBSWY3DPEHPK3PXP")!!
        assertEquals("otpauth://hotp/counter=9?secret=JBSWY3DPEHPK3PXP&counter=1", incremented)
    }
}
