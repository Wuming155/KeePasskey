package com.keepasskey.core.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * 针对 TotpKeyUriParser 与 RFC 6238 标准时间向量的单元测试 (Wave 3-E P1-11)
 *
 * ISSUE-P2-12：ParsedTotpConfig.secret 已改为 Base32 文本字节（ASCII），
 * 断言相应调整；并新增“解析器不持有调用方输入缓冲 / 返回独占副本”的字节语义回归。
 */
class TotpKeyUriParserTest {

    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    private fun secretText(config: ParsedTotpConfig): String =
        String(config.secret, StandardCharsets.US_ASCII)

    @Test
    fun `TotpKeyUriParser 标准 KeyUri 完整参数解析`() {
        val uri = "otpauth://totp/Acme:user@example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&period=60&digits=8&algorithm=SHA256&issuer=Acme"
        val config = TotpKeyUriParser.parse(uri)

        assertNotNull(config)
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", secretText(config!!))
        assertEquals(60, config.period)
        assertEquals(8, config.digits)
        assertEquals("SHA256", config.algorithm)
        assertEquals("Acme", config.issuer)
        assertEquals("user@example.com", config.account)
        config.secret.fill(0)
    }

    @Test
    fun `TotpKeyUriParser 缺省参数宽容默认值回退`() {
        val uri = "otpauth://totp/simpleAccount?secret=JBSWY3DPEHPK3PXP"
        val config = TotpKeyUriParser.parse(uri)

        assertNotNull(config)
        assertEquals("JBSWY3DPEHPK3PXP", secretText(config!!))
        assertEquals(30, config.period)
        assertEquals(6, config.digits)
        assertEquals("SHA1", config.algorithm)
        assertEquals("simpleAccount", config.account)
        config.secret.fill(0)
    }

    @Test
    fun `TotpKeyUriParser 纯 Base32 密钥宽容解析`() {
        val rawSecret = "gez dgn bvg y3t qoj q"
        val config = TotpKeyUriParser.parse(rawSecret)

        assertNotNull(config)
        assertEquals("GEZDGNBVGY3TQOJQ", secretText(config!!))
        assertEquals(30, config.period)
        assertEquals(6, config.digits)
        assertEquals("SHA1", config.algorithm)
        config.secret.fill(0)
    }

    @Test
    fun `字节入口解析_输入缓冲清零后配置仍可独立消费`() {
        val input = "otpauth://totp/Acme:user@example.com?secret=JBSWY3DPEHPK3PXP"
            .toByteArray(StandardCharsets.UTF_8)
        val config = TotpKeyUriParser.parse(input)

        assertNotNull(config)
        // 解析器不修改调用方输入（借用语义）；调用方清零输入后，配置持有的种子为独立副本
        assertEquals("JBSWY3DPEHPK3PXP", secretText(config!!))
        input.fill(0)
        assertEquals("JBSWY3DPEHPK3PXP", secretText(config))
        // 消费后调用方按契约清零配置持有的种子
        config.secret.fill(0)
        assertEquals(0, config.secret.count { it != 0.toByte() })
    }

    @Test
    fun `字节入口对 null 与空输入返回 null`() {
        assertNull(TotpKeyUriParser.parse(null as ByteArray?))
        assertNull(TotpKeyUriParser.parse(ByteArray(0)))
        assertNull(TotpKeyUriParser.parse("   ".toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `TotpKeyUriParser 非法输入返回 null`() {
        assertNull(TotpKeyUriParser.parse(null as String?))
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
        // TASK-46：计算侧入参为 ByteArray（Base32 解码产物，用毕擦除）
        val timestampMillis = 59_000L
        val secretBytes = Base32Decoder.decode(rfcSecret)
        val code = try {
            OtpEngine.calculateTotp(
                secretKey = secretBytes,
                timestampMillis = timestampMillis,
                periodSeconds = 30,
                digits = 6,
                algorithm = OtpEngine.HashAlgorithm.SHA1
            )
        } finally {
            secretBytes.fill(0)
        }
        assertEquals("287082", code)

        val remaining = OtpEngine.getRemainingSeconds(
            timestampMillis = timestampMillis,
            periodSeconds = 30
        )
        // 59 % 30 = 29, 30 - 29 = 1 秒
        assertEquals(1, remaining)
    }
}
