package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.otp.ParsedTotpConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-46：`VaultEntryMapper.computeTotpCode` 计算侧回归。
 * 种子经 Base32 解码为 ByteArray 后全程字节态参与计算，成功 / 失败路径均在
 * `finally` 中显式擦除（fail-clean）；本测试锁定外显行为：
 * - 有效配置出码（6 位全数字）
 * - 无效 / 空种子 fail-clean 返回 null（不抛异常、不落假码）
 * - 各哈希算法配置正常消费
 */
class VaultEntryMapperTotpTest {

    private val mapper = VaultEntryMapper(StringsProvider { id, _ -> "s$id" })

    @Test
    fun `有效 TOTP 配置返回 6 位全数字验证码`() {
        // RFC 6238 官方测试密钥 Base32 编码（SHA-1 路径，当前时间出码）
        val code = mapper.computeTotpCode(
            ParsedTotpConfig(secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        )
        assertEquals(6, code!!.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun `无效种子 fail-clean 返回 null 不抛异常`() {
        assertNull(mapper.computeTotpCode(ParsedTotpConfig(secret = "")))
        assertNull(mapper.computeTotpCode(ParsedTotpConfig(secret = "!!!!")))
        // 解码得空字节流 → SecretKeySpec 构造失败 → fail-clean 返回 null
        assertNull(mapper.computeTotpCode(ParsedTotpConfig(secret = "====")))
    }

    @Test
    fun `SHA-256 与 SHA-512 算法配置正常出码`() {
        val sha256 = mapper.computeTotpCode(
            ParsedTotpConfig(
                secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
                algorithm = "SHA256"
            )
        )
        assertEquals(6, sha256!!.length)
        assertTrue(sha256.all { it.isDigit() })

        val sha512 = mapper.computeTotpCode(
            ParsedTotpConfig(
                secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
                algorithm = "SHA512"
            )
        )
        assertEquals(6, sha512!!.length)
        assertTrue(sha512.all { it.isDigit() })
    }
}
