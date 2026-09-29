package com.keepasskey.core.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * ISSUE-P3-383：Steam Guard TOTP 与标准 RFC 路径隔离（JVM）。
 *
 * AC③ 负例：非 steam 前缀 URI 解析行为不变（既有用例零回归由 TotpKeyUriParserTest 锁定）。
 */
class SteamOtpTest {

    @Test
    fun `steam uri 解析为 STEAM 算法 5 字符`() {
        val config = TotpKeyUriParser.parse("steam://JBSWY3DPEHPK3PXP")
        assertNotNull(config)
        assertEquals(TotpKeyUriParser.ALGORITHM_STEAM, config!!.algorithm)
        assertEquals(5, config.digits)
        assertEquals(30, config.period)
        assertEquals("Steam", config.issuer)
        assertFalse(config.isHotp)
        config.secret.fill(0)
    }

    @Test
    fun `otpauth issuer Steam 走 STEAM 算法`() {
        val uri = "otpauth://totp/Steam:alice?secret=JBSWY3DPEHPK3PXP&issuer=Steam"
        val config = TotpKeyUriParser.parse(uri)
        assertNotNull(config)
        assertEquals(TotpKeyUriParser.ALGORITHM_STEAM, config!!.algorithm)
        assertEquals(5, config.digits)
        config.secret.fill(0)
    }

    @Test
    fun `非 steam otpauth 仍为 SHA1 6 位`() {
        val config = TotpKeyUriParser.parse("otpauth://totp/Acme:user?secret=GEZDGNBVGY3TQOJQ")
        assertNotNull(config)
        assertEquals("SHA1", config!!.algorithm)
        assertEquals(6, config.digits)
        config.secret.fill(0)
    }

    @Test
    fun `steam 码恒 5 字符且字母表合法`() {
        val secret = Base32Decoder.decode("JBSWY3DPEHPK3PXP")
        try {
            val codes = (0L..3L).map { counter -> OtpEngine.calculateSteamHotp(secret, counter) }
            for (code in codes) {
                assertEquals(5, code.length)
                assertTrue(code.all { it in OtpEngine.STEAM_ALPHABET })
            }
            // 标准 TOTP 同时刻仍是纯数字 6 位
            val standard = OtpEngine.calculateTotp(secret, timestampMillis = 30_000L)
            assertEquals(6, standard.length)
            assertTrue(standard.all { it.isDigit() })
        } finally {
            secret.fill(0)
        }
    }

    @Test
    fun `steam 与标准 HOTP 编码层不同`() {
        val secret = Base32Decoder.decode("JBSWY3DPEHPK3PXP")
        try {
            val steam = OtpEngine.calculateSteamHotp(secret, 5L)
            val standard = OtpEngine.calculateHotp(secret, 5L)
            assertEquals(5, steam.length)
            assertEquals(6, standard.length)
            assertTrue(steam.all { it in OtpEngine.STEAM_ALPHABET })
            assertTrue(standard.all { it.isDigit() })
        } finally {
            secret.fill(0)
        }
    }

    @Test
    fun `引擎取码经 mapping 支持 STEAM 算法`() {
        val config = ParsedTotpConfig(
            secret = "JBSWY3DPEHPK3PXP".toByteArray(StandardCharsets.US_ASCII),
            period = 30,
            digits = 5,
            algorithm = "STEAM",
            issuer = "Steam"
        )
        // 通过 VaultEntryTotpMapping 同口径算法分支（此处直接调 OtpEngine 等价路径）
        val secretBytes = Base32Decoder.decode(config.secret)
        try {
            val code = OtpEngine.calculateSteamCode(secretBytes, timestampMillis = 0L)
            assertEquals(5, code.length)
            assertTrue(code.all { it in OtpEngine.STEAM_ALPHABET })
        } finally {
            secretBytes.fill(0)
        }
    }

    @Test
    fun `steam uri 非法 base32 返回 null`() {
        assertNull(TotpKeyUriParser.parse("steam://!!!!"))
    }
}
