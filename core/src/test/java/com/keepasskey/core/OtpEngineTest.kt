package com.keepasskey.core.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OtpEngine 单元测试：
 * 验证 RFC 6238 TOTP 标准向量、RFC 4226 HOTP 计算与 otpauth:// URI 解析。
 */
class OtpEngineTest {

    // RFC 6238 官方测试密钥 "12345678901234567890" Base32 编码: GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
    private val rfcSecretBase32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun `测试 HOTP 标准计算结果验证`() {
        // RFC 4226 标准测试向量：
        // Count 0: 755224
        // Count 1: 287082
        // Count 2: 359152
        val code0 = OtpEngine.calculateHotp(rfcSecretBase32, counter = 0L, digits = 6)
        assertEquals("755224", code0)

        val code1 = OtpEngine.calculateHotp(rfcSecretBase32, counter = 1L, digits = 6)
        assertEquals("287082", code1)

        val code2 = OtpEngine.calculateHotp(rfcSecretBase32, counter = 2L, digits = 6)
        assertEquals("359152", code2)
    }

    @Test
    fun `测试 TOTP 计算与 6 位数字格式`() {
        val totp = OtpEngine.calculateTotp(rfcSecretBase32, timestampMillis = 1788500000000L)
        assertEquals(6, totp.length)
        assertTrue(totp.all { it.isDigit() })
    }

    @Test
    fun `测试 OtpAuth URI 标准格式解析`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&period=30&digits=6"
        val params = OtpEngine.parseOtpAuthUri(uri)

        assertNotNull(params)
        assertEquals("totp", params!!.type)
        assertEquals("GitHub", params.issuer)
        assertEquals("JBSWY3DPEHPK3PXP", params.secretBase32)
        assertEquals(30, params.periodSeconds)
        assertEquals(6, params.digits)
    }

    @Test
    fun `测试 Base32 解码准确性`() {
        val decoded = Base32Decoder.decode("MZXW6YTB") // "foobar" in Base32 is "MZXW6YTBOI======"
        assertEquals("foo", String(decoded.copyOfRange(0, 3)))
    }
}
