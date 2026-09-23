package com.keepasskey.core.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P2-289` AC①／AC②／AC③／AC④：otpauth URI 百分号解码与解析诊断回归。
 *
 * ## 锁定的缺陷
 *
 * 整改前参数值不做百分号解码：`secret=...%3D%3D` 经「去 `=` 归一」把 `%3D` 的
 * `3`、`D` 当字母表字符拼进种子 ⇒ 静默解成**错误密钥**且无任何报错；label / issuer
 * 的 `%20` / `%40` 原样入库；`digits` 越界与未知 `algorithm` 静默回落无诊断。
 *
 * `digits=7` 的口径经开工前复核更正：它**在** 6..8 钳制范围内（被保留）——
 * 条目正文「digits=7 回落为 6」的表述不成立，本类按真实语义锁定（见 §296 批次正文）。
 */
class TotpKeyUriParserDecodingTest {

    @Test
    fun `百分号编码的种子：解码后归一，不再把编码字节当字母表拼入（AC① AC④）`() {
        val parsed = TotpKeyUriParser.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP%3D%3D")
        assertNotNull(parsed)
        // %3D%3D → "==" → 归一去填充后恰为原种子（错误形态 "…3D3D" 必须不再出现）
        assertEquals("JBSWY3DPEHPK3PXP", parsed!!.secret.toString(Charsets.US_ASCII))
        parsed.secret.fill(0)
    }

    @Test
    fun `label 与 issuer 的百分号解码：%20 与 %40 正确入库（AC① AC④）`() {
        val parsed = TotpKeyUriParser.parse(
            "otpauth://totp/Example%20Inc%3Auser%40mail.com?secret=JBSWY3DPEHPK3PXP&issuer=Example%20Inc"
        )
        assertNotNull(parsed)
        assertEquals("user@mail.com", parsed!!.account)
        assertEquals("Example Inc", parsed.issuer)
        parsed.secret.fill(0)
    }

    @Test
    fun `digits 越界回落带诊断，digits=7 在钳制范围内保留（AC③ AC④）`() {
        val outOfRange = TotpKeyUriParser.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=9")
        assertNotNull(outOfRange)
        assertEquals("digits=9 必须回落 6 位", 6, outOfRange!!.digits)
        assertTrue(
            "回落必须带诊断（禁静默改写）: ${outOfRange.warnings}",
            outOfRange.warnings.any { it.contains("digits=9") }
        )
        outOfRange.secret.fill(0)

        val seven = TotpKeyUriParser.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=7")
        assertNotNull(seven)
        assertEquals("digits=7 在 6..8 内必须原样保留（钳制语义边界）", 7, seven!!.digits)
        assertTrue("合法 digits 不得产生诊断", seven.warnings.isEmpty())
        seven.secret.fill(0)
    }

    @Test
    fun `未知 algorithm 回落 SHA1 带诊断（AC③ AC④）`() {
        val parsed = TotpKeyUriParser.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=md5")
        assertNotNull(parsed)
        assertEquals("algorithm=md5 必须回落 SHA1", "SHA1", parsed!!.algorithm)
        assertTrue(
            "回落必须带诊断（禁静默改写）: ${parsed.warnings}",
            parsed.warnings.any { it.contains("algorithm=md5") }
        )
        parsed.secret.fill(0)
    }

    @Test
    fun `种子含字母表外字符：解析即可辨识地失败（AC②）`() {
        assertNull(
            "含非法字符的种子必须解析失败（禁宽容解出错误密钥）",
            TotpKeyUriParser.parse("otpauth://totp/x?secret=JBSW***Y3D")
        )
    }

    @Test
    fun `既有路径不回归：纯 Base32 与标准 URI 形态照常（AC④）`() {
        // 非 otpauth 的纯种子（小写 + 填充 + 空白归一）
        val plain = TotpKeyUriParser.parse("  jbswy3dpehpk3pxp== ")
        assertNotNull(plain)
        assertEquals("JBSWY3DPEHPK3PXP", plain!!.secret.toString(Charsets.US_ASCII))
        plain.secret.fill(0)

        // 标准 URI（无编码字符）参数照常
        val standard = TotpKeyUriParser.parse(
            "otpauth://totp/Issuer:acct?secret=JBSWY3DPEHPK3PXP&period=45&digits=8&algorithm=SHA256"
        )
        assertNotNull(standard)
        assertEquals(45, standard!!.period)
        assertEquals(8, standard.digits)
        assertEquals("SHA256", standard.algorithm)
        assertTrue(standard.warnings.isEmpty())
        standard.secret.fill(0)
    }
}
