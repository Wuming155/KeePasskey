package com.keepasskey.app.autofill

/**
 * 字段识别置信度（ISSUE-P3-39）。
 *
 * 取代此前的「命中即布尔」判定：不同信号源（autofillHints / inputType / htmlName / 邻近 label）
 * 的可信度不同，统一为四档以支撑「多候选择优」——**仅用于字段角色择优排序**，
 * 不参与任何凭据放行判定（放行仍由域名归属校验与匹配器决定，见 [AutofillCandidateRanker]）。
 */
enum class FieldConfidence(val score: Int) {
    /** 未命中任何信号 */
    NONE(0),

    /** 邻近 label 文本命中（最弱，易受界面文案干扰） */
    LOW(10),

    /** inputType 掩码或 htmlName / idEntry 命中（中） */
    MEDIUM(20),

    /** autofillHints 显式声明（最强，由页面作者标注） */
    HIGH(30)
}

/**
 * 结构树抽象扫描节点。
 * 纯 Kotlin 模型，零 Android SDK 类依赖，便于直接在 JVM 上进行单元测试。
 */
data class ScanNode(
    val id: String,
    val autofillHints: List<String> = emptyList(),
    val inputType: Int = 0,
    val isFocused: Boolean = false,
    val htmlName: String? = null,
    val webDomain: String? = null,
    val packageName: String = "",
    /**
     * ISSUE-P3-39：邻近 label / 提示文本（AssistStructure 的 `ViewNode.hint` 等）。
     * 用于在无 autofillHints、无 htmlName 的表单中按界面文案（含中/英/西里尔文）兜底识别。
     */
    val label: String? = null,
    /**
     * ISSUE-P3-39：节点是否可见。
     * 不可见的**密码**框仍按密码准入（部分应用聚焦账号框时密码框尚未可见，若整体丢弃会导致填充失效）；
     * 不可见的**账号**框一律不参与（避免隐藏的搜索/备注被误判为登录账号）。
     */
    val isVisible: Boolean = true,
    /**
     * ISSUE-P3-43：页面是否允许对该字段自动填充（对应 AssistStructure 的 `importantForAutofill`）。
     * 仅当扫描方要求「尊重页面标记」（[scan] 的 `respectImportantForAutofill = true`）时，
     * false 才会导致该字段被跳过；对应设置项 `overrideNoAutofill`（默认 false=尊重）。
     */
    val importantForAutofill: Boolean = true
)

/**
 * 表单字段扫描分析结果
 */
data class ScanResult(
    val usernameId: String?,
    val passwordId: String?,
    val webDomain: String?,
    val packageName: String?,
    // ISSUE-P3-39：以下为新增的置信度与形态信息，默认值保证既有调用方零改动
    val usernameConfidence: FieldConfidence = FieldConfidence.NONE,
    val passwordConfidence: FieldConfidence = FieldConfidence.NONE,
    /** 仅识别到密码框、未识别到账号框（纯密码登录页） */
    val isPasswordOnlyLogin: Boolean = false
)

/**
 * 自动填充表单字段语义识别器 (对齐 P0-4 要求；ISSUE-P3-39 升级为多信号 + 置信度模型)。
 *
 * 纯算法实现，按 **autofillHints > inputType/htmlName > 邻近 label** 的信号强度识别
 * 用户名与密码输入框，并排除搜索框与验证码/评论等非凭据字段。
 * 多语言登录词表覆盖中/英/法/西/俄/乌常见界面文案。
 */
object AutofillFieldScanner {

    // 常用 Android InputType 掩码常量 (解耦 android.text.InputType)
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0

    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_PHONE = 0x00000003
    private const val TYPE_CLASS_NUMBER = 0x00000002

    private const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    private const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020
    private const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010

    /** 账号类 label 词（CJK / 西里尔按子串匹配） */
    private val USERNAME_SUBSTRING_TERMS = listOf(
        "用户名", "用戶名", "账号", "帐号", "帳號", "登录名", "登入名", "手機號", "手机号", "手机号码",
        "電話號碼", "电话号码", "登录", "登入",
        "логин", "логін", "пользовател", "користувач", "identifiant", "utilisateur", "usuario", "correo"
    )

    /** 账号类 label 词（拉丁文按 token 精确匹配，避免 "user" 命中 "userFeedback" 之类） */
    private val USERNAME_TOKEN_TERMS = setOf(
        "user", "username", "userid", "login", "loginid", "nickname", "account", "email", "mail",
        "phone", "mobile", "telephone", "tel"
    )

    /** 密码类 label 词（子串匹配） */
    private val PASSWORD_SUBSTRING_TERMS = listOf(
        "密码", "密碼", "口令", "пароль", "паролі", "motdepasse", "mot de passe", "contraseña", "contrasena"
    )

    /** 密码类 label 词（token 精确匹配） */
    private val PASSWORD_TOKEN_TERMS = setOf("password", "passwd", "pwd", "pass", "passphrase", "clave")

    /** 搜索框排除词（子串，含拼接形态如 searchInput） */
    private val SEARCH_SUBSTRING_TERMS = listOf(
        "搜索", "搜尋", "查询", "查詢", "筛选", "篩選", "search", "query", "keyword"
    )

    /** 搜索框排除词（token） */
    private val SEARCH_TOKEN_TERMS = setOf("search", "query", "find", "filter", "keyword")

    /** 非凭据字段排除词（token）：验证码 / 评论 / 反馈等，避免被误判为账号框 */
    private val NON_CREDENTIAL_TOKEN_TERMS = setOf(
        "captcha", "verification", "verify", "otp", "comment", "feedback", "message", "reply", "promo", "coupon"
    )

    /** 非凭据字段强排除子串（覆盖拼接形态如 userFeedback） */
    private val NON_CREDENTIAL_SUBSTRING_TERMS = listOf(
        "feedback", "comment", "captcha", "verification", "coupon", "promo", "reply"
    )

    /**
     * 扫描节点列表并提取用户名框、密码框与来源信息。
     *
     * @param respectImportantForAutofill ISSUE-P3-43：true 时跳过页面显式声明
     *   `importantForAutofill=no` 的字段（默认行为，对应 `overrideNoAutofill = false`）；
     *   调用方传 false 表示用户选择「覆盖应用的禁止填充标记」。
     */
    fun scan(nodes: List<ScanNode>, respectImportantForAutofill: Boolean = true): ScanResult {
        var resolvedWebDomain: String? = null
        var resolvedPackageName: String? = null

        val usernameCandidates = mutableListOf<Candidate>()
        val passwordCandidates = mutableListOf<Candidate>()

        for (node in nodes) {
            if (resolvedWebDomain == null && !node.webDomain.isNullOrBlank()) {
                resolvedWebDomain = node.webDomain.trim()
            }
            if (resolvedPackageName == null && node.packageName.isNotBlank()) {
                resolvedPackageName = node.packageName.trim()
            }

            // 搜索框与非凭据字段（验证码/评论等）一律不参与
            if (isSearchField(node) || isNonCredentialField(node)) continue
            // ISSUE-P3-43：页面显式声明 importantForAutofill=no 的字段，
            // 在「尊重页面标记」模式下跳过（设置项 overrideNoAutofill 可覆盖）
            if (respectImportantForAutofill && !node.importantForAutofill) continue

            val passwordConfidence = passwordSignal(node)
            if (passwordConfidence != FieldConfidence.NONE) {
                // 密码类为强登录信号：即便不可见也准入（见 ScanNode.isVisible 说明）
                passwordCandidates.add(Candidate(node.id, rank(passwordConfidence, node.isFocused)))
                continue
            }

            // 不可见的账号框不参与（避免隐藏的搜索/备注误判）
            if (!node.isVisible) continue

            val usernameConfidence = usernameSignal(node)
            if (usernameConfidence != FieldConfidence.NONE) {
                usernameCandidates.add(Candidate(node.id, rank(usernameConfidence, node.isFocused)))
            }
        }

        val bestUsername = usernameCandidates.maxByOrNull { it.score }
        val bestPassword = passwordCandidates.maxByOrNull { it.score }

        return ScanResult(
            usernameId = bestUsername?.id,
            passwordId = bestPassword?.id,
            webDomain = resolvedWebDomain,
            packageName = resolvedPackageName,
            usernameConfidence = bestUsername?.let { confidenceOf(it.score) } ?: FieldConfidence.NONE,
            passwordConfidence = bestPassword?.let { confidenceOf(it.score) } ?: FieldConfidence.NONE,
            isPasswordOnlyLogin = bestPassword != null && bestUsername == null
        )
    }

    /** 密码信号强度：hint > inputType/htmlName > label */
    private fun passwordSignal(node: ScanNode): FieldConfidence = when {
        node.autofillHints.any { isPasswordHint(it) } -> FieldConfidence.HIGH
        isPasswordInputType(node.inputType) -> FieldConfidence.MEDIUM
        isPasswordHtmlName(node.htmlName) -> FieldConfidence.MEDIUM
        isPasswordLabel(node.label) -> FieldConfidence.LOW
        else -> FieldConfidence.NONE
    }

    /** 账号信号强度：hint > inputType/htmlName > label */
    private fun usernameSignal(node: ScanNode): FieldConfidence = when {
        node.autofillHints.any { isUsernameHint(it) } -> FieldConfidence.HIGH
        isAccountInputType(node.inputType) -> FieldConfidence.MEDIUM
        isUsernameHtmlName(node.htmlName) -> FieldConfidence.MEDIUM
        isUsernameLabel(node.label) -> FieldConfidence.LOW
        else -> FieldConfidence.NONE
    }

    private fun rank(confidence: FieldConfidence, isFocused: Boolean): Int =
        confidence.score * 10 + if (isFocused) 1 else 0

    private fun confidenceOf(rankedScore: Int): FieldConfidence {
        val base = rankedScore / 10
        return FieldConfidence.entries.firstOrNull { it.score == base } ?: FieldConfidence.NONE
    }

    fun isPasswordInputType(inputType: Int): Boolean {
        val clazz = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        val isTextPassword = (clazz == TYPE_CLASS_TEXT) &&
                (variation == TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                        variation == TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val isNumberPassword = (clazz == TYPE_CLASS_NUMBER) && (variation == TYPE_NUMBER_VARIATION_PASSWORD)
        return isTextPassword || isNumberPassword
    }

    /** 账号类 inputType：邮箱 / 电话（ISSUE-P3-39 新增信号） */
    fun isAccountInputType(inputType: Int): Boolean {
        val clazz = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        if (clazz == TYPE_CLASS_PHONE) return true
        if (clazz != TYPE_CLASS_TEXT) return false
        return variation == TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    fun isUsernameHint(hint: String): Boolean {
        val h = hint.lowercase().trim()
        return h == "username" || h == "user" || h == "email" ||
                h == "emailaddress" || h == "login" || h == "account" ||
                h == "phonenumber" || h == "phone" ||
                h.contains("username") || h.contains("email")
    }

    fun isPasswordHint(hint: String): Boolean {
        val h = hint.lowercase().trim()
        return h == "password" || h == "current-password" || h == "new-password" ||
                h == "pwd" || h.contains("password")
    }

    fun isUsernameHtmlName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase().trim()
        return n.contains("user") || n.contains("login") || n.contains("email") || n.contains("account") ||
                n.contains("phone") || n.contains("mobile")
    }

    fun isPasswordHtmlName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase().trim()
        return n.contains("pass") || n.contains("pwd") || n.contains("secret")
    }

    /** 邻近 label 是否为账号类（多语言；ISSUE-P3-39 新增） */
    fun isUsernameLabel(label: String?): Boolean {
        val v = label?.lowercase()?.trim().orEmpty()
        if (v.isEmpty()) return false
        if (USERNAME_SUBSTRING_TERMS.any { v.contains(it) }) return true
        return tokensOf(v).any { it in USERNAME_TOKEN_TERMS }
    }

    /** 邻近 label 是否为密码类（多语言；ISSUE-P3-39 新增） */
    fun isPasswordLabel(label: String?): Boolean {
        val v = label?.lowercase()?.trim().orEmpty()
        if (v.isEmpty()) return false
        if (PASSWORD_SUBSTRING_TERMS.any { v.contains(it) }) return true
        return tokensOf(v).any { it in PASSWORD_TOKEN_TERMS }
    }

    /** 搜索框判定（ISSUE-P3-39 新增）：命中即排除，绝不作为账号/密码候选 */
    fun isSearchField(node: ScanNode): Boolean {
        val haystack = listOfNotNull(node.htmlName, node.label)
            .joinToString(" ")
            .lowercase()
        if (haystack.isBlank()) return false
        if (node.autofillHints.any { it.lowercase().trim().contains("search") }) return true
        if (SEARCH_SUBSTRING_TERMS.any { haystack.contains(it) }) return true
        return tokensOf(haystack).any { it in SEARCH_TOKEN_TERMS }
    }

    /** 非凭据字段判定（ISSUE-P3-39 新增）：验证码 / 评论 / 反馈等 */
    private fun isNonCredentialField(node: ScanNode): Boolean {
        val haystack = listOfNotNull(node.htmlName, node.label)
            .joinToString(" ")
            .lowercase()
        if (haystack.isBlank()) return false
        if (NON_CREDENTIAL_SUBSTRING_TERMS.any { haystack.contains(it) }) return true
        return tokensOf(haystack).any { it in NON_CREDENTIAL_TOKEN_TERMS }
    }

    private fun tokensOf(value: String): List<String> =
        value.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    private data class Candidate(val id: String, val score: Int)
}
