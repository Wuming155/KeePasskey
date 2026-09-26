package com.keepasskey.app.passkey

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ISSUE-P3-337` 口径 1 / AC①：扫码载荷分流纯函数的表驱动用例。
 *
 * 锁定的判据（三条路径互斥、无回退猜测）：
 * - `otpauth:` 起始（忽略大小写、允许前导空白）→ `Totp`；
 * - `{` / `[` 起始 → `Passkey`；
 * - 其余（含空载荷、纯空白、纯字母文本）→ `Unknown`。
 *
 * 另锁两项容易被「顺手优化」破坏的契约：
 * ① 判定**只看首部形态**，不做内容语义推断（否则就退化成「先按 TOTP 解、失败再按通行密钥」
 *    的回退猜测，`ISSUE-P3-332` 的前缀强校验教训同源）；
 * ② **只读不改**入参数组——擦除义务归调用方（顶栏 TOTP 链与 CXF 链各自承担），
 *    分流函数若写入或清零就会破坏上行种子。
 */
class ScanPayloadClassifierTest {

    /** 表驱动穷举：每行 `(标签, 载荷, 期望类别)`，期望值一律取自被测返回值（非重言形态）。 */
    @Test
    fun `一 表驱动穷举三条路径的判定边界`() {
        val cases = listOf(
            // ---- Totp ----
            Triple(
                "标准 otpauth URI（现状顶栏主形态）",
                "otpauth://totp/Example:Alice?secret=JBSWY3DPEHPK3PXP&issuer=Example",
                ScanPayloadKind.Totp
            ),
            Triple("大写方案名 OTPAUTH://", "OTPAUTH://totp/x?secret=AAA", ScanPayloadKind.Totp),
            Triple("混合大小写 OtpAuth://", "OtpAuth://totp/x?secret=AAA", ScanPayloadKind.Totp),
            Triple("仅到 otpauth: 为止（畸形，仍归 TOTP 链裁决）", "otpauth:", ScanPayloadKind.Totp),
            Triple("缺斜杠的 otpauth:totp/…", "otpauth:totp/x", ScanPayloadKind.Totp),
            Triple("前导一个空格", " otpauth://totp/x?secret=AAA", ScanPayloadKind.Totp),
            Triple("前导制表 + 换行", "\t\r\notpauth://totp/x?secret=AAA", ScanPayloadKind.Totp),
            // ---- Passkey ----
            Triple("裸 Passkey 对象（CXF §3.3.12 形态）", "{\"type\":\"passkey\"}", ScanPayloadKind.Passkey),
            Triple("凭据数组形态", "[{\"type\":\"passkey\"}]", ScanPayloadKind.Passkey),
            Triple(
                "完整文档形态（含 Header.accounts）",
                "{\"version\":{\"major\":1,\"minor\":0},\"accounts\":[]}",
                ScanPayloadKind.Passkey
            ),
            Triple("前导空白 + 左花括号", "   \n{\"type\":\"passkey\"}", ScanPayloadKind.Passkey),
            Triple("前导空白 + 左方括号", "\n[", ScanPayloadKind.Passkey),
            Triple("紧贴的左花括号（无任何空白）", "{", ScanPayloadKind.Passkey),
            Triple(
                "花括号开头的非 JSON 文本（形态优先，由解析器 fail-closed 拒收）",
                " { not json }",
                ScanPayloadKind.Passkey
            ),
            // ---- Unknown ----
            Triple("空载荷", "", ScanPayloadKind.Unknown),
            Triple("纯空白载荷", "   \t\n ", ScanPayloadKind.Unknown),
            Triple("纯字母文本（Base32 字母表内单词，不得当种子）", "HELLO", ScanPayloadKind.Unknown),
            Triple("裸 Base32 种子（无方案名）", "JBSWY3DPEHPK3PXP", ScanPayloadKind.Unknown),
            Triple("方案名拼错 otpauth（缺冒号）", "otpauth/totp/x", ScanPayloadKind.Unknown),
            Triple("方案名截断 otpaut:", "otpaut://totp/x", ScanPayloadKind.Unknown),
            Triple("otpauth 出现在载荷中部而非首部", "note: otpauth://totp/x", ScanPayloadKind.Unknown),
            Triple("URL 类其它方案", "https://example.com/otpauth:passkey", ScanPayloadKind.Unknown),
            Triple("XML 形态文本", "<entry><key>x</key></entry>", ScanPayloadKind.Unknown)
        )
        for ((label, payload, expected) in cases) {
            val actual = ScanPayloadClassifier.classify(payload.toCharArray())
            assertEquals("$label ⇒ 分流判定不符", expected, actual)
        }
    }

    /** 判据只看首部形态：内容里出现另一种形态的标记，不得改变归属。 */
    @Test
    fun `二 判定只依赖首部形态不做内容推断`() {
        val totpWithBraceInside = "otpauth://totp/{x}?secret=JBSWY3DPEHPK3PXP".toCharArray()
        assertEquals(
            "TOTP 载荷内含左花括号仍须判 Totp（否则会把种子交给 CXF 链）",
            ScanPayloadKind.Totp,
            ScanPayloadClassifier.classify(totpWithBraceInside)
        )
        val jsonWithOtpauthInside =
            "{\"type\":\"passkey\",\"username\":\"otpauth://totp/x\"}".toCharArray()
        assertEquals(
            "CXF 载荷的字段值里含 otpauth: 仍须判 Passkey（否则凭据被当 URI 拒收）",
            ScanPayloadKind.Passkey,
            ScanPayloadClassifier.classify(jsonWithOtpauthInside)
        )
    }

    /** 擦除义务归调用方：分流是只读的，既不写入也不清零上行数组。 */
    @Test
    fun `三 分流只读入参不清零不写入`() {
        val totp = "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP".toCharArray()
        val snapshot = totp.copyOf()
        ScanPayloadClassifier.classify(totp)
        assertArrayEquals("分流函数不得改动上行 CharArray（擦除义务归调用链）", snapshot, totp)

        val passkey = "{\"type\":\"passkey\"}".toCharArray()
        val passkeySnapshot = passkey.copyOf()
        ScanPayloadClassifier.classify(passkey)
        assertArrayEquals("CXF 载荷同样须原样保留供解析器取用", passkeySnapshot, passkey)
    }

    /** 边界：越界空白与单字符载荷不得抛异常（扫码面接受任意二维码，异常会击穿取景对话框）。 */
    @Test
    fun `四 畸形与边界载荷不得抛异常`() {
        val payloads = listOf(
            CharArray(0),
            CharArray(1),
            " ".toCharArray(),
            "\u0000".toCharArray(),
            "otpauth".toCharArray(),
            "otpauth:".toCharArray(),
            "[".toCharArray(),
            "{".toCharArray()
        )
        val expected = listOf(
            ScanPayloadKind.Unknown,
            ScanPayloadKind.Unknown,
            ScanPayloadKind.Unknown,
            ScanPayloadKind.Unknown,
            ScanPayloadKind.Unknown,
            ScanPayloadKind.Totp,
            ScanPayloadKind.Passkey,
            ScanPayloadKind.Passkey
        )
        payloads.forEachIndexed { index, payload ->
            assertEquals("第 ${index + 1} 个边界载荷判定不符", expected[index], ScanPayloadClassifier.classify(payload))
        }
    }
}
