package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutofillFieldFallback] 单元测试（ISSUE-P3-372 AC① / AC②）。
 *
 * 覆盖两段兜底的正反例：弱目标二次解析的采纳门、排除口径与尊重标记；
 * 聚焦字段补齐的输入闸门与「不凭空造目标」约束。
 */
class AutofillFieldFallbackTest {

    private companion object {
        /** `TYPE_CLASS_TEXT`（普通文本） */
        const val INPUT_TYPE_TEXT = 0x00000001

        /** `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD`（0x81，完整密码输入类型） */
        const val INPUT_TYPE_PASSWORD = 0x00000081

        /** `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_EMAIL_ADDRESS`（0x21，账号类输入类型） */
        const val INPUT_TYPE_EMAIL = 0x00000021
    }

    private fun node(
        id: String,
        htmlName: String? = null,
        label: String? = null,
        inputType: Int = 0,
        focused: Boolean = false,
        visible: Boolean = true,
        important: Boolean = true,
        hints: List<String> = emptyList()
    ): ScanNode = ScanNode(
        id = id,
        autofillHints = hints,
        inputType = inputType,
        isFocused = focused,
        htmlName = htmlName,
        label = label,
        isVisible = visible,
        importantForAutofill = important
    )

    /** 零目标的首轮结果（弱解析的入口前提） */
    private fun zeroPass1(): ScanResult = ScanResult(
        usernameId = null,
        passwordId = null,
        webDomain = "example.com",
        packageName = "com.example.app"
    )

    /** 结构化信号全部查不到、但干草堆含中文密码术语的首轮零目标形态 */
    private val weakNodes = listOf(
        node("0", htmlName = "密码框", label = null, inputType = INPUT_TYPE_TEXT),
        node("1", htmlName = "用户名输入", label = null, inputType = INPUT_TYPE_TEXT, focused = true)
    )

    // ===== AC① 弱目标二次解析 =====

    @Test
    fun `首轮零目标时干草堆术语可兜底识别中文命名字段`() {
        val result = AutofillFieldFallback.runWeakReparse(weakNodes, respectImportantForAutofill = true, pass1 = zeroPass1())

        assertTrue(result.usedWeakReparse)
        assertEquals("0", result.passwordId)
        assertEquals("1", result.usernameId)
        assertEquals(FieldConfidence.LOW, result.passwordConfidence)
        assertFalse(result.isPasswordOnlyLogin)
    }

    @Test
    fun `首轮已有目标时不进入二次解析`() {
        val pass1 = zeroPass1().copy(usernameId = "5")
        val result = AutofillFieldFallback.runWeakReparse(weakNodes, respectImportantForAutofill = true, pass1 = pass1)

        assertFalse(result.usedWeakReparse)
        assertEquals("5", result.usernameId)
        assertNull(result.passwordId)
    }

    @Test
    fun `全页无登录术语时二次解析维持零目标`() {
        val nodes = listOf(
            node("0", htmlName = "search_box", label = "查询"),
            node("1", htmlName = "note", label = "备注")
        )
        val result = AutofillFieldFallback.runWeakReparse(nodes, respectImportantForAutofill = true, pass1 = zeroPass1())

        assertFalse(result.usedWeakReparse)
        assertNull(result.usernameId)
        assertNull(result.passwordId)
    }

    @Test
    fun `仅弱账号且全页无密码术语时登录上下文门不采纳`() {
        // 「登录」术语在页面上（弱账号可识别），但任何节点都不含密码术语 ⇒ 不采纳
        val nodes = listOf(node("0", htmlName = "登录入口", inputType = INPUT_TYPE_TEXT, focused = true))
        val result = AutofillFieldFallback.runWeakReparse(nodes, respectImportantForAutofill = true, pass1 = zeroPass1())

        assertFalse(result.usedWeakReparse)
        assertNull(result.usernameId)
    }

    @Test
    fun `弱账号被采纳当且仅当全页含密码术语`() {
        // 密码术语出现在 importantForAutofill=false 的节点（其本身被尊重标记排除），
        // 但作为「页面登录上下文」的旁证仍使弱账号目标成立
        val nodes = listOf(
            node("0", htmlName = "登录入口", inputType = INPUT_TYPE_TEXT, focused = true),
            node("1", htmlName = "hidden_password_field", important = false)
        )
        val result = AutofillFieldFallback.runWeakReparse(nodes, respectImportantForAutofill = true, pass1 = zeroPass1())

        assertTrue(result.usedWeakReparse)
        assertEquals("0", result.usernameId)
        assertNull(result.passwordId)
        assertTrue(result.isPasswordOnlyLogin.not())
    }

    @Test
    fun `二次解析同样尊重 importantForAutofill 与排除词`() {
        // 密码字段被页面标记禁止填充 ⇒ 尊重模式下不兜底
        val blocked = listOf(node("0", htmlName = "密码框", important = false))
        val blockedResult = AutofillFieldFallback.runWeakReparse(blocked, respectImportantForAutofill = true, pass1 = zeroPass1())
        assertFalse(blockedResult.usedWeakReparse)

        // 用户显式选择覆盖（respect=false）⇒ 同一字段可兜底
        val overrideResult = AutofillFieldFallback.runWeakReparse(blocked, respectImportantForAutofill = false, pass1 = zeroPass1())
        assertTrue(overrideResult.usedWeakReparse)
        assertEquals("0", overrideResult.passwordId)

        // 搜索框即使含密码字样也不兜底（排除口径与首轮一致）
        val search = listOf(node("0", htmlName = "password_search", label = "搜索"))
        val searchResult = AutofillFieldFallback.runWeakReparse(search, respectImportantForAutofill = true, pass1 = zeroPass1())
        assertFalse(searchResult.usedWeakReparse)
    }

    @Test
    fun `不可见弱账号不兜底而不可见弱密码可兜底`() {
        val invisibleUsername = listOf(
            node("0", htmlName = "登录入口", visible = false),
            node("1", htmlName = "密码框")
        )
        val r1 = AutofillFieldFallback.runWeakReparse(invisibleUsername, respectImportantForAutofill = true, pass1 = zeroPass1())
        assertNull(r1.usernameId)
        assertEquals("1", r1.passwordId)

        val invisiblePassword = listOf(node("0", htmlName = "密码框", visible = false))
        val r2 = AutofillFieldFallback.runWeakReparse(invisiblePassword, respectImportantForAutofill = true, pass1 = zeroPass1())
        assertEquals("0", r2.passwordId)
        assertTrue(r2.isPasswordOnlyLogin)
    }

    @Test
    fun `弱解析跳过 OTP 框`() {
        val nodes = listOf(
            node("0", hints = listOf("smsOtpCode")),
            node("1", htmlName = "password_box")
        )
        val result = AutofillFieldFallback.runWeakReparse(nodes, respectImportantForAutofill = true, pass1 = zeroPass1())

        assertEquals("1", result.passwordId)
        // OTP 框绝不被兜底成账号 / 密码目标
        assertFalse("0" == result.usernameId || "0" == result.passwordId)
    }

    // ===== AC② 聚焦字段补齐 =====

    private fun passwordOnly(): ScanResult = ScanResult(
        usernameId = null,
        passwordId = "2",
        webDomain = "example.com",
        packageName = "com.example.app",
        isPasswordOnlyLogin = true
    )

    @Test
    fun `有密码缺账号时聚焦文本框被合成为账号目标`() {
        val nodes = listOf(
            node("2", inputType = INPUT_TYPE_PASSWORD),                 // 密码框（已被识别）
            node("3", inputType = INPUT_TYPE_EMAIL, focused = true)  // 聚焦邮箱输入
        )
        val result = AutofillFieldFallback.synthesizeFocusedUsername(passwordOnly(), nodes, respectImportantForAutofill = true)

        assertEquals("3", result.usernameId)
        assertEquals(FieldConfidence.MEDIUM, result.usernameConfidence)
        assertFalse(result.isPasswordOnlyLogin)
    }

    @Test
    fun `无聚焦字段时不凭空造账号目标`() {
        val nodes = listOf(node("2", inputType = INPUT_TYPE_PASSWORD))
        val result = AutofillFieldFallback.synthesizeFocusedUsername(passwordOnly(), nodes, respectImportantForAutofill = true)

        assertNull(result.usernameId)
        assertTrue(result.isPasswordOnlyLogin)
    }

    @Test
    fun `聚焦字段带密码信号或搜索信号时不合成`() {
        val passwordFocused = listOf(
            node("2", inputType = INPUT_TYPE_PASSWORD),
            node("4", htmlName = "pass_confirmation", inputType = INPUT_TYPE_TEXT, focused = true)
        )
        assertNull(
            AutofillFieldFallback.synthesizeFocusedUsername(passwordOnly(), passwordFocused, respectImportantForAutofill = true)
                .usernameId
        )

        val searchFocused = listOf(
            node("2", inputType = INPUT_TYPE_PASSWORD),
            node("5", htmlName = "search_input", label = "搜索", inputType = INPUT_TYPE_TEXT, focused = true)
        )
        assertNull(
            AutofillFieldFallback.synthesizeFocusedUsername(passwordOnly(), searchFocused, respectImportantForAutofill = true)
                .usernameId
        )
    }

    @Test
    fun `聚焦字段被禁止填充或不可见时不合成`() {
        val blocked = listOf(
            node("2", inputType = INPUT_TYPE_PASSWORD),
            node("3", inputType = INPUT_TYPE_EMAIL, focused = true, important = false)
        )
        assertNull(
            AutofillFieldFallback.synthesizeFocusedUsername(passwordOnly(), blocked, respectImportantForAutofill = true)
                .usernameId
        )
        // 覆盖模式下同一字段可合成
        assertEquals(
            "3",
            AutofillFieldFallback.synthesizeFocusedUsername(passwordOnly(), blocked, respectImportantForAutofill = false)
                .usernameId
        )

        val invisible = listOf(
            node("2", inputType = INPUT_TYPE_PASSWORD),
            node("3", inputType = INPUT_TYPE_EMAIL, focused = true, visible = false)
        )
        assertNull(
            AutofillFieldFallback.synthesizeFocusedUsername(passwordOnly(), invisible, respectImportantForAutofill = true)
                .usernameId
        )
    }

    @Test
    fun `账号已存在或无密码目标时合成不生效`() {
        val withUsername = passwordOnly().copy(usernameId = "9")
        val nodes = listOf(node("3", inputType = INPUT_TYPE_EMAIL, focused = true))
        assertEquals(
            "9",
            AutofillFieldFallback.synthesizeFocusedUsername(withUsername, nodes, respectImportantForAutofill = true).usernameId
        )

        val noPassword = passwordOnly().copy(passwordId = null, isPasswordOnlyLogin = false)
        assertNull(
            AutofillFieldFallback.synthesizeFocusedUsername(noPassword, nodes, respectImportantForAutofill = true).usernameId
        )
    }

    @Test
    fun `未知输入类型的聚焦文本框可合成`() {
        // WebView 常态：inputType=0 且无结构化信号
        val nodes = listOf(
            node("2", inputType = INPUT_TYPE_PASSWORD),
            node("7", inputType = 0, focused = true)
        )
        assertEquals(
            "7",
            AutofillFieldFallback.synthesizeFocusedUsername(passwordOnly(), nodes, respectImportantForAutofill = true)
                .usernameId
        )
    }
}
