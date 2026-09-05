package com.keepasskey.app.autofill

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
    val packageName: String = ""
)

/**
 * 表单字段扫描分析结果
 */
data class ScanResult(
    val usernameId: String?,
    val passwordId: String?,
    val webDomain: String?,
    val packageName: String?
)

/**
 * 自动填充表单字段语义识别器 (对齐 P0-4 要求)。
 * 纯算法实现，根据 AutofillHints、InputType 掩码与 HTML/视图属性兜底识别目标用户名与密码输入框。
 */
object AutofillFieldScanner {

    // 常用 Android InputType 掩码常量 (解耦 android.text.InputType)
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0

    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002

    private const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010

    /**
     * 扫描节点列表并提取用户名框、密码框与来源信息
     */
    fun scan(nodes: List<ScanNode>): ScanResult {
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

            val hasPasswordHint = node.autofillHints.any { isPasswordHint(it) }
            val hasUsernameHint = node.autofillHints.any { isUsernameHint(it) }
            val isPasswordType = isPasswordInputType(node.inputType)

            // 1. Password 识别（Hint 或 InputType 为密码优先）
            if (hasPasswordHint || isPasswordType) {
                val score = (if (hasPasswordHint) 100 else 0) + (if (isPasswordType) 50 else 0) + (if (node.isFocused) 10 else 0)
                passwordCandidates.add(Candidate(node.id, score, node.isFocused))
                continue
            }

            // 2. Username 识别（Hint 优先）
            if (hasUsernameHint) {
                val score = 100 + (if (node.isFocused) 10 else 0)
                usernameCandidates.add(Candidate(node.id, score, node.isFocused))
                continue
            }

            // 3. 兜底策略：分析 htmlName / 视图名称
            if (isPasswordHtmlName(node.htmlName)) {
                val score = 30 + (if (node.isFocused) 10 else 0)
                passwordCandidates.add(Candidate(node.id, score, node.isFocused))
            } else if (isUsernameHtmlName(node.htmlName)) {
                val score = 30 + (if (node.isFocused) 10 else 0)
                usernameCandidates.add(Candidate(node.id, score, node.isFocused))
            }
        }

        val bestUsername = usernameCandidates.maxByOrNull { it.score }?.id
        val bestPassword = passwordCandidates.maxByOrNull { it.score }?.id

        return ScanResult(
            usernameId = bestUsername,
            passwordId = bestPassword,
            webDomain = resolvedWebDomain,
            packageName = resolvedPackageName
        )
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

    fun isUsernameHint(hint: String): Boolean {
        val h = hint.lowercase().trim()
        return h == "username" || h == "user" || h == "email" ||
                h == "emailaddress" || h == "login" || h == "account" ||
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
        return n.contains("user") || n.contains("login") || n.contains("email") || n.contains("account")
    }

    fun isPasswordHtmlName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase().trim()
        return n.contains("pass") || n.contains("pwd") || n.contains("secret")
    }

    private data class Candidate(val id: String, val score: Int, val isFocused: Boolean)
}
