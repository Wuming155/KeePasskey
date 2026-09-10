package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.otp.ParsedTotpConfig
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * TASK-46：VaultEntryMapper.computeTotpCode 计算侧回归。
 * 种子经 Base32 解码为 ByteArray 后全程字节态参与计算，成功 / 失败路径均在
 * finally 中显式擦除（fail-clean）；本测试锁定外显行为：
 * - 有效配置出码（6 位全数字）
 * - 无效 / 空种子 fail-clean 返回 null（不抛异常、不落假码）
 * - 各哈希算法配置正常消费
 *
 * ISSUE-P2-12：ParsedTotpConfig.secret 改为 Base32 文本字节，构造与断言相应调整；
 * 新增 parseTotpConfig 字节读取路径回归（不擦除库内 ProtectedString）。
 */
class VaultEntryMapperTotpTest {

    private val mapper = VaultEntryMapper(StringsProvider { id, _ -> "s$id" })

    private fun secretBytes(text: String): ByteArray = text.toByteArray(StandardCharsets.US_ASCII)

    private fun secretText(config: ParsedTotpConfig): String =
        String(config.secret, StandardCharsets.US_ASCII)

    @Test
    fun `有效 TOTP 配置返回 6 位全数字验证码`() {
        // RFC 6238 官方测试密钥 Base32 编码（SHA-1 路径，当前时间出码）
        val config = ParsedTotpConfig(secret = secretBytes(RFC_SECRET))
        val code = mapper.computeTotpCode(config)
        assertEquals(6, code!!.length)
        assertTrue(code.all { it.isDigit() })
        config.secret.fill(0)
    }

    @Test
    fun `无效种子 fail-clean 返回 null 不抛异常`() {
        assertNull(mapper.computeTotpCode(ParsedTotpConfig(secret = secretBytes(""))))
        assertNull(mapper.computeTotpCode(ParsedTotpConfig(secret = secretBytes("!!!!"))))
        // 解码得空字节流 → SecretKeySpec 构造失败 → fail-clean 返回 null
        assertNull(mapper.computeTotpCode(ParsedTotpConfig(secret = secretBytes("===="))))
    }

    @Test
    fun `SHA-256 与 SHA-512 算法配置正常出码`() {
        val sha256 = mapper.computeTotpCode(
            ParsedTotpConfig(secret = secretBytes(RFC_SECRET), algorithm = "SHA256")
        )
        assertEquals(6, sha256!!.length)
        assertTrue(sha256.all { it.isDigit() })

        val sha512 = mapper.computeTotpCode(
            ParsedTotpConfig(secret = secretBytes(RFC_SECRET), algorithm = "SHA512")
        )
        assertEquals(6, sha512!!.length)
        assertTrue(sha512.all { it.isDigit() })
    }

    @Test
    fun `parseTotpConfig 走字节语义且不擦除库内受保护字段`() {
        val otpProtected = ProtectedString("JBSWY3DPEHPK3PXP", isProtected = true)
        val entry = KdbxEntry(fields = mapOf(KdbxConstants.Fields.OTP to otpProtected))

        val config = mapper.parseTotpConfig(entry)

        assertNotNull(config)
        assertEquals("JBSWY3DPEHPK3PXP", secretText(config!!))
        // 库内 ProtectedString 归会话树所有，解析路径只读取不得擦除
        assertEquals("JBSWY3DPEHPK3PXP", otpProtected.readString())
        config.secret.fill(0)
    }

    @Test
    fun `parseTotpConfig 无 TOTP 字段返回 null`() {
        assertNull(mapper.parseTotpConfig(KdbxEntry(fields = emptyMap())))
    }

    private companion object {
        const val RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    }
}
