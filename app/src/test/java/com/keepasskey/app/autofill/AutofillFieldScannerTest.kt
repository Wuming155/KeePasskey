package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutofillFieldScanner] 单元测试，覆盖 hint 优先、htmlName 兜底、未识别场景，
 * 以及 ISSUE-P3-39 新增的多语言 label、搜索/非凭据排除、可见性与置信度模型。
 */
class AutofillFieldScannerTest {

    @Test
    fun scan_detectsByAutofillHints() {
        val nodes = listOf(
            ScanNode(
                id = "node_user",
                autofillHints = listOf("username"),
                inputType = 1,
                isFocused = true,
                webDomain = "github.com",
                packageName = "com.github.android"
            ),
            ScanNode(
                id = "node_pass",
                autofillHints = listOf("password"),
                inputType = 129,
                isFocused = false,
                webDomain = "github.com",
                packageName = "com.github.android"
            )
        )

        val result = AutofillFieldScanner.scan(nodes)
        assertEquals("node_user", result.usernameId)
        assertEquals("node_pass", result.passwordId)
        assertEquals("github.com", result.webDomain)
        assertEquals("com.github.android", result.packageName)
        assertEquals(FieldConfidence.HIGH, result.usernameConfidence)
        assertEquals(FieldConfidence.HIGH, result.passwordConfidence)
    }

    @Test
    fun scan_fallsBackToHtmlNameWhenNoHints() {
        val nodes = listOf(
            ScanNode(
                id = "input_1",
                autofillHints = emptyList(),
                inputType = 1,
                htmlName = "txtUserLogin",
                webDomain = "login.example.com",
                packageName = "org.example.app"
            ),
            ScanNode(
                id = "input_2",
                autofillHints = emptyList(),
                inputType = 1,
                htmlName = "txtUserPassword",
                webDomain = "login.example.com",
                packageName = "org.example.app"
            )
        )

        val result = AutofillFieldScanner.scan(nodes)
        assertEquals("input_1", result.usernameId)
        assertEquals("input_2", result.passwordId)
        assertEquals("login.example.com", result.webDomain)
        assertEquals("org.example.app", result.packageName)
    }

    @Test
    fun scan_returnsNullWhenNoMatch() {
        val nodes = listOf(
            ScanNode(
                id = "input_search",
                autofillHints = listOf("search"),
                inputType = 1,
                htmlName = "searchQuery",
                webDomain = "search.com",
                packageName = "com.search.app"
            ),
            ScanNode(
                id = "input_comment",
                autofillHints = emptyList(),
                inputType = 1,
                htmlName = "userFeedback",
                webDomain = "search.com",
                packageName = "com.search.app"
            )
        )

        val result = AutofillFieldScanner.scan(nodes)
        assertNull(result.passwordId)
        // ISSUE-P3-39：搜索框与反馈框均被显式排除，账号框亦不得误判
        assertNull(result.usernameId)
    }

    // ---- ISSUE-P3-39 新增覆盖 ----

    @Test
    fun `无 hint 与 htmlName 时按中文 label 识别`() {
        val nodes = listOf(
            ScanNode(id = "u", label = "用户名", isFocused = true),
            ScanNode(id = "p", label = "密码")
        )

        val result = AutofillFieldScanner.scan(nodes)
        assertEquals("u", result.usernameId)
        assertEquals("p", result.passwordId)
        assertEquals(FieldConfidence.LOW, result.usernameConfidence)
        assertEquals(FieldConfidence.LOW, result.passwordConfidence)
    }

    @Test
    fun `按西里尔文 label 识别`() {
        val nodes = listOf(
            ScanNode(id = "u", label = "Логин"),
            ScanNode(id = "p", label = "Пароль")
        )

        val result = AutofillFieldScanner.scan(nodes)
        assertEquals("u", result.usernameId)
        assertEquals("p", result.passwordId)
    }

    @Test
    fun `搜索框与评论框被排除`() {
        val nodes = listOf(
            ScanNode(id = "s", htmlName = "searchInput", label = "搜索"),
            ScanNode(id = "c", htmlName = "commentInput", label = "评论")
        )

        val result = AutofillFieldScanner.scan(nodes)
        assertNull(result.usernameId)
        assertNull(result.passwordId)
    }

    @Test
    fun `不可见账号框不参与但不可见密码框仍准入`() {
        val nodes = listOf(
            ScanNode(id = "u", htmlName = "usernameField", isVisible = false),
            ScanNode(id = "p", htmlName = "passwordField", isVisible = false)
        )

        val result = AutofillFieldScanner.scan(nodes)
        assertNull(result.usernameId)
        assertEquals("p", result.passwordId)
        assertTrue(result.isPasswordOnlyLogin)
    }

    @Test
    fun `autofillHint 置信度高于 label`() {
        val nodes = listOf(
            ScanNode(id = "low", label = "用户名"),
            ScanNode(id = "high", autofillHints = listOf("username"))
        )

        val result = AutofillFieldScanner.scan(nodes)
        assertEquals("high", result.usernameId)
        assertEquals(FieldConfidence.HIGH, result.usernameConfidence)
    }

    @Test
    fun `邮箱 inputType 识别为账号`() {
        val nodes = listOf(ScanNode(id = "e", inputType = 0x21))

        val result = AutofillFieldScanner.scan(nodes)
        assertEquals("e", result.usernameId)
        assertEquals(FieldConfidence.MEDIUM, result.usernameConfidence)
    }

    @Test
    fun `尊重 importantForAutofill 时跳过页面禁用字段`() {
        val nodes = listOf(
            ScanNode(id = "u", autofillHints = listOf("username"), importantForAutofill = false),
            ScanNode(id = "p", autofillHints = listOf("password"), importantForAutofill = false)
        )

        val result = AutofillFieldScanner.scan(nodes)

        assertNull(result.usernameId)
        assertNull(result.passwordId)
    }

    @Test
    fun `覆盖开关开启时忽略 importantForAutofill 禁用标记`() {
        val nodes = listOf(
            ScanNode(id = "u", autofillHints = listOf("username"), importantForAutofill = false),
            ScanNode(id = "p", autofillHints = listOf("password"), importantForAutofill = false)
        )

        val result = AutofillFieldScanner.scan(nodes, respectImportantForAutofill = false)

        assertEquals("u", result.usernameId)
        assertEquals("p", result.passwordId)
    }

    // ===== ISSUE-P3-298 ⑤：OTP 验证码框识别（直填通道） =====

    @Test
    fun `显式 OTP hint 识别为验证码框`() {
        val nodes = listOf(
            ScanNode(id = "u", autofillHints = listOf("username"), inputType = 1, packageName = "app"),
            ScanNode(id = "otp", autofillHints = listOf("one-time-code"), inputType = 1, packageName = "app")
        )
        val result = AutofillFieldScanner.scan(nodes)
        assertEquals("otp", result.otpId)
        // OTP 框不得同时被误判为账号框
        assertEquals("u", result.usernameId)
    }

    @Test
    fun `平台 OTP hint 形态与 htmlName 兜底`() {
        val hintCases = mapOf(
            "sms" to listOf("smsOtpCode"),
            "mfa" to listOf("2faAppOtpCode"),
            "w3c" to listOf("one-time-code"),
            "norm" to listOf("One_Time_Code")
        )
        for ((expectedId, hints) in hintCases) {
            val nodes = listOf(
                ScanNode(id = expectedId, autofillHints = hints, inputType = 1, packageName = "app")
            )
            assertEquals(expectedId, AutofillFieldScanner.scan(nodes).otpId)
        }

        val byHtmlName = listOf(
            ScanNode(id = "t", inputType = 1, htmlName = "totpCode", packageName = "app")
        )
        assertEquals("t", AutofillFieldScanner.scan(byHtmlName).otpId)
    }

    @Test
    fun `仅 label 含验证码不识别为 OTP 框（防图形验证码误填）`() {
        val nodes = listOf(
            ScanNode(id = "c", inputType = 1, label = "请输入验证码", packageName = "app")
        )
        val result = AutofillFieldScanner.scan(nodes)
        assertNull(result.otpId)
        // label 兜底通道同时不得把验证码框当账号框（既有非凭据排除语义不变）
        assertNull(result.usernameId)
    }

    @Test
    fun `不可见 OTP 框不参与识别`() {
        val nodes = listOf(
            ScanNode(id = "hidden", autofillHints = listOf("oneTimeCode"), inputType = 1, isVisible = false, packageName = "app")
        )
        assertNull(AutofillFieldScanner.scan(nodes).otpId)
    }

    @Test
    fun `OTP 框不被误判为账号或密码`() {
        val nodes = listOf(
            ScanNode(id = "only_otp", inputType = 1, htmlName = "totp_code", packageName = "app")
        )
        val result = AutofillFieldScanner.scan(nodes)
        assertEquals("only_otp", result.otpId)
        assertNull(result.usernameId)
        assertNull(result.passwordId)
    }
}
