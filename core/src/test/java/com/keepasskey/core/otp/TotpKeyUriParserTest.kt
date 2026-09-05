package com.keepasskey.core.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 针对 TotpKeyUriParser 与 RFC 6238 标准时间向量的单元测试 (Wave 3-E P1-11)
 */
class TotpKeyUriParserTest {

    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun `TotpKeyUriParser 标准 KeyUri 完整参数解析`() {
        val uri = "otpauth://totp/Acme:user@example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&period=60&digits=8&algorithm=SHA256&issuer=Acme"
        val config = TotpKeyUriParser.parse(uri)

        assertNotNull(config)
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", config!!.secret)
        assertEquals(60, config.period)
        assertEquals(8, config.digits)
        assertEquals("SHA256", config.algorithm)
        assertEquals("Acme", config.issuer)
        assertEquals("user@example.com", config.account)
    }

    @Test
    fun `TotpKeyUriParser 缺省参数宽容默认值回退`() {
        val uri = "otpauth://totp/simpleAccount?secret=JBSWY3DPEHPK3PXP"
        val config = TotpKeyUriParser.parse(uri)

        assertNotNull(config)
        assertEquals("JBSWY3DPEHPK3PXP", config!!.secret)
        assertEquals(30, config.period)
        assertEquals(6, config.digits)
        assertEquals("SHA1", config.algorithm)
        assertEquals("simpleAccount", config.account)
    }

    @Test
    fun `TotpKeyUriParser 纯 Base32 密钥宽容解析`() {
        val rawSecret = "gez dgn bvg y3t qoj q"
        val config = TotpKeyUriParser.parse(rawSecret)

        assertNotNull(config)
        assertEquals("GEZDGNBVGY3TQOJQ", config!!.secret)
        assertEquals(30, config.period)
        assertEquals(6, config.digits)
        assertEquals("SHA1", config.algorithm)
    }

    @Test
    fun `TotpKeyUriParser 非法输入返回 null`() {
        assertNull(TotpKeyUriParser.parse(null))
        assertNull(TotpKeyUriParser.parse(""))
        assertNull(TotpKeyUriParser.parse("   "))
        assertNull(TotpKeyUriParser.parse("http://example.com/not-otp"))
        assertNull(TotpKeyUriParser.parse("otpauth://totp/Account?digits=6")) // 缺少 secret
        assertNull(TotpKeyUriParser.parse("!@#$ invalid_chars 8901"))
    }

    @Test
    fun `RFC 6238 官方时间向量验证 - t 等于 59s 时 6位 SHA1 计算码等于 287082`() {
        // RFC 6238 官方测试密钥: "12345678901234567890" Base32: GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
        // 在 t = 59 秒时，步长 30s，counter = 59 / 30 = 1
        // HOTP(K, 1) 的 6 位截断结果为 287082
        val timestampMillis = 59_000L
        val code = OtpEngine.calculateTotp(
            secretKeyBase32 = rfcSecret,
            timestampMillis = timestampMillis,
            periodSeconds = 30,
            digits = 6,
            algorithm = OtpEngine.HashAlgorithm.SHA1
        )
        assertEquals("287082", code)

        val remaining = OtpEngine.getRemainingSeconds(
            timestampMillis = timestampMillis,
            periodSeconds = 30
        )
        // 59 % 30 = 29, 30 - 29 = 1 秒
        assertEquals(1, remaining)
    }
}
