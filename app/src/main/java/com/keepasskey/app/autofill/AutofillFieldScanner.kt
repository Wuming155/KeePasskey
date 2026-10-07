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
    val importantForAutofill: Boolean = true,
    /**
     * ISSUE-P3-375 AC①：HTML `autocomplete` 属性（WebView 表单的结构化字段源之二，
     * 如 `cc-number` / `postal-code`）。原生控件该值为 null。
     */
    val autocomplete: String? = null
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
    val isPasswordOnlyLogin: Boolean = false,
    /**
     * ISSUE-P3-298 ⑤：显式声明的 OTP 验证码输入框（W3C `one-time-code` / 平台
     * `smsOtpCode` / `2faAppOtpCode` hint，或 htmlName/idEntry 含 `otp`）。
     * 仅供数据集直填当前 TOTP 值，不参与账号 / 密码的任何匹配与放行判定。
     */
    val otpId: String? = null,
    /**
     * ISSUE-P3-372 AC①：本结果是否由「弱目标二次解析」产出（首轮严格扫描零登录目标后的
     * 第二轮干草堆术语扫描）。仅作诊断与单测断言，不参与任何放行判定。
     */
    val usedWeakReparse: Boolean = false,
    /**
     * ISSUE-P3-375 AC①：结构化数据目标（角色 → 节点索引 id；仅 hint / autocomplete
     * 两源可填充项，由 `StructuredFieldPolicy.detectFillableTargets` 产出并在解析编排中合入）。
     */
    val structuredTargets: Map<StructuredFieldRole, String> = emptyMap()
)

/**
 * 自动填充表单字段语义识别器 (对齐 P0-4 要求；ISSUE-P3-39 升级为多信号 + 置信度模型)。
 *
 * 纯算法实现，按 **autofillHints > inputType/htmlName > 邻近 label** 的信号强度识别
 * 用户名与密码输入框，并排除搜索框与验证码/评论等非凭据字段。
 * 多语言登录词表覆盖中/英/法/西/俄/乌常见界面文案。
 *
 * ISSUE-P3-529：账号侧新增**数字类兜底档**（`TYPE_CLASS_NUMBER` 普通变体 ⇒ 账号候选，`LOW`），
 * 并在选择账号目标时施加**登录上下文门**「该档单独成立时须同表单存在密码目标才保留」——
 * 口径吸收自 Monica `EnhancedAutofillStructureParserV2`（数字类 ⇒ `USERNAME`，
 * `AutofillDetectionPolicy.genericNumberFallbackAccuracy()`＝`LOW`）+ `shouldKeepTarget`
 * （账号类「精度 ≥ MEDIUM 或存在密码目标」须保留），见
 * `docs/references/自动填充关联记忆与字段识别的参考项目对照.md` §3.2。
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
    private const val TYPE_NUMBER_VARIATION_NORMAL = 0x00000000
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010

    /**
     * 扫描节点列表并提取用户名框、密码框与来源信息。
     *
     * ISSUE-P2-383：同一填充结构中出现**多个不同** webDomain（顶层页域 A + iframe 域 B）
     * 时，整结构 **fail-closed 拒绝**（`webDomain = null`，且不产出账号/密码/OTP 候选）——
     * 受信浏览器场景下若仍按「首个非空 domain 胜出」放行，凭据可被填进异域 iframe 字段，
     * 归属展示与实际落点背离。纯同域结构不受影响。
     *
     * @param respectImportantForAutofill ISSUE-P3-43：true 时跳过页面显式声明
     *   `importantForAutofill=no` 的字段（默认行为，对应 `overrideNoAutofill = false`）；
     *   调用方传 false 表示用户选择「覆盖应用的禁止填充标记」。
     */
    fun scan(nodes: List<ScanNode>, respectImportantForAutofill: Boolean = true): ScanResult {
        // ISSUE-P2-383：混域整结构拒绝（策略在 AutofillDomainConsistencyPolicy）
        val mixedDomains = AutofillDomainConsistencyPolicy.isMixedDomain(nodes)
        val resolvedWebDomain = AutofillDomainConsistencyPolicy.resolveSingleDomain(nodes)
        val resolvedPackageName = AutofillDomainConsistencyPolicy.resolvePackageName(nodes)

        // 混域：整结构拒绝，不产出任何填充候选（AC①「整结构拒绝」口径）
        if (mixedDomains) {
            return ScanResult(
                usernameId = null,
                passwordId = null,
                webDomain = null,
                packageName = resolvedPackageName,
                usernameConfidence = FieldConfidence.NONE,
                passwordConfidence = FieldConfidence.NONE,
                isPasswordOnlyLogin = false,
                otpId = null
            )
        }

        val usernameCandidates = mutableListOf<Candidate>()
        val passwordCandidates = mutableListOf<Candidate>()
        val otpCandidates = mutableListOf<Candidate>()

        for (node in nodes) {
            // ISSUE-P3-298 ⑤：OTP 通道优先于搜索 / 非凭据排除——`otp` 一词同时也在
            // 非凭据排除词表内（那是针对「别把验证码框当账号框」的旧语义），显式声明的
            // OTP 框如今要被捕获为直填目标，故必须先判 OTP 再走排除。OTP 框不可见时
            // 不参与（填充目标不可见无意义，与账号框同口径）。
            if (node.isVisible) {
                val otpConfidence = otpSignal(node)
                if (otpConfidence != FieldConfidence.NONE) {
                    otpCandidates.add(Candidate(node.id, rank(otpConfidence, node.isFocused)))
                    continue
                }
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
                usernameCandidates.add(
                    Candidate(
                        id = node.id,
                        score = rank(usernameConfidence, node.isFocused),
                        numericFallback = isNumericOnlyUsernameSource(node)
                    )
                )
            }
        }

        // ISSUE-P3-529：账号择优次序＝「有文本信号者优先于数字类兜底档」，同档内按既有 rank 降序
        // （原实现直接 maxByOrNull(score)，会让同为 LOW 的数字类框在条序靠前时**抢占**真正的账号框）
        val bestAnyUsername = usernameCandidates.maxWithOrNull(
            compareBy<Candidate> { if (it.numericFallback) 0 else 1 }.thenBy { it.score }
        )
        val bestPassword = passwordCandidates.maxByOrNull { it.score }
        val bestOtp = otpCandidates.maxByOrNull { it.score }
        // ISSUE-P3-529 登录上下文门：数字类兜底档**单独**成立时须有密码目标才保留
        // （对齐 Monica `shouldKeepTarget`：账号类「精度 ≥ MEDIUM 或存在密码目标」）——
        // 否则纯数字的数量 / 金额 / 搜索框会被当成账号目标
        val bestUsername = if (bestAnyUsername?.numericFallback == true) {
            bestAnyUsername.takeIf { bestPassword != null }
        } else {
            bestAnyUsername
        }

        return ScanResult(
            usernameId = bestUsername?.id,
            passwordId = bestPassword?.id,
            webDomain = resolvedWebDomain,
            packageName = resolvedPackageName,
            usernameConfidence = bestUsername?.let { confidenceOf(it.score) } ?: FieldConfidence.NONE,
            passwordConfidence = bestPassword?.let { confidenceOf(it.score) } ?: FieldConfidence.NONE,
            isPasswordOnlyLogin = bestPassword != null && bestUsername == null,
            otpId = bestOtp?.id
        )
    }

    /** OTP 验证码信号强度（ISSUE-P3-298 ⑤）：hint > htmlName；**不用 label**——「验证码」一词同时用于短信验证码与图形验证码，误填风险高（ISSUE-P3-372 起 internal：兜底解析须跳过 OTP 框） */
    internal fun otpSignal(node: ScanNode): FieldConfidence = when {
        node.autofillHints.any { isOtpHint(it) } -> FieldConfidence.HIGH
        isOtpHtmlName(node.htmlName) -> FieldConfidence.MEDIUM
        else -> FieldConfidence.NONE
    }

    /** 平台 / W3C 的 OTP hint（归一化精确匹配；不匹配用户名/密码 hint 的包含形态） */
    fun isOtpHint(hint: String): Boolean {
        val normalized = hint.lowercase().replace("-", "").replace("_", "").trim()
        return normalized in AutofillFieldLexicon.OTP_HINTS
    }

    /** htmlName / idEntry 含 `otp`（覆盖 `totp` / `hotp` / `otpcode` / `one_time_code` 等拼接形态） */
    fun isOtpHtmlName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return name.lowercase().trim().contains("otp")
    }

    /** 密码信号强度：hint > inputType/htmlName > label（ISSUE-P3-372 起 internal：兜底二次解析复用） */
    internal fun passwordSignal(node: ScanNode): FieldConfidence = when {
        node.autofillHints.any { isPasswordHint(it) } -> FieldConfidence.HIGH
        isPasswordInputType(node.inputType) -> FieldConfidence.MEDIUM
        isPasswordHtmlName(node.htmlName) -> FieldConfidence.MEDIUM
        isPasswordLabel(node.label) -> FieldConfidence.LOW
        else -> FieldConfidence.NONE
    }

    /** 账号信号强度：hint > inputType/htmlName > label > 数字类兜底（ISSUE-P3-529 新增末档） */
    private fun usernameSignal(node: ScanNode): FieldConfidence = when {
        node.autofillHints.any { isUsernameHint(it) } -> FieldConfidence.HIGH
        isAccountInputType(node.inputType) -> FieldConfidence.MEDIUM
        isUsernameHtmlName(node.htmlName) -> FieldConfidence.MEDIUM
        isUsernameLabel(node.label) -> FieldConfidence.LOW
        isNumericAccountInputType(node.inputType) -> FieldConfidence.LOW
        else -> FieldConfidence.NONE
    }

    /**
     * ISSUE-P3-529：数字类账号框信号（`TYPE_CLASS_NUMBER` + **普通**变体）。
     *
     * 对象是「QQ 号 / 工号 / 学号 / 会员号」这类**纯数字账号栏**（中文应用常见）：
     * 提示文案不含任何账号术语、`idEntry` 也无 `user/login/account` 词根，唯一可用的结构信号
     * 就是数字类输入类型。精度取 `LOW`（最弱档，对齐 Monica
     * `AutofillDetectionPolicy.genericNumberFallbackAccuracy()`）；是否保留另由
     * [scan] 的**登录上下文门**裁决（须同表单存在密码目标）。
     *
     * `TYPE_NUMBER_VARIATION_PASSWORD` 变体不走本档——它由 [passwordSignal] 按密码处理。
     */
    fun isNumericAccountInputType(inputType: Int): Boolean {
        val clazz = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        return clazz == TYPE_CLASS_NUMBER && variation == TYPE_NUMBER_VARIATION_NORMAL
    }

    /**
     * 该节点是否**仅因**数字类兜底档成为账号候选（更高档的文本信号一概未命中）。
     * 用于 [scan] 的登录上下文门与账号择优次序——更高档命中时本档不参与标记。
     */
    private fun isNumericOnlyUsernameSource(node: ScanNode): Boolean =
        isNumericAccountInputType(node.inputType) &&
            !isAccountInputType(node.inputType) &&
            !isUsernameHtmlName(node.htmlName) &&
            !isUsernameLabel(node.label) &&
            node.autofillHints.none { isUsernameHint(it) }

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
        if (AutofillFieldLexicon.USERNAME_SUBSTRING_TERMS.any { v.contains(it) }) return true
        return AutofillFieldLexicon.tokensOf(v).any { it in AutofillFieldLexicon.USERNAME_TOKEN_TERMS }
    }

    /** 邻近 label 是否为密码类（多语言；ISSUE-P3-39 新增） */
    fun isPasswordLabel(label: String?): Boolean {
        val v = label?.lowercase()?.trim().orEmpty()
        if (v.isEmpty()) return false
        if (AutofillFieldLexicon.PASSWORD_SUBSTRING_TERMS.any { v.contains(it) }) return true
        return AutofillFieldLexicon.tokensOf(v).any { it in AutofillFieldLexicon.PASSWORD_TOKEN_TERMS }
    }

    /** 搜索框判定（ISSUE-P3-39 新增）：命中即排除，绝不作为账号/密码候选 */
    fun isSearchField(node: ScanNode): Boolean {
        val haystack = listOfNotNull(node.htmlName, node.label)
            .joinToString(" ")
            .lowercase()
        if (haystack.isBlank()) return false
        if (node.autofillHints.any { it.lowercase().trim().contains("search") }) return true
        if (AutofillFieldLexicon.SEARCH_SUBSTRING_TERMS.any { haystack.contains(it) }) return true
        return AutofillFieldLexicon.tokensOf(haystack).any { it in AutofillFieldLexicon.SEARCH_TOKEN_TERMS }
    }

    /** 非凭据字段判定（ISSUE-P3-39 新增）：验证码 / 评论 / 反馈等（ISSUE-P3-372 起 internal：二次解析沿用同一排除口径） */
    internal fun isNonCredentialField(node: ScanNode): Boolean {
        val haystack = listOfNotNull(node.htmlName, node.label)
            .joinToString(" ")
            .lowercase()
        if (haystack.isBlank()) return false
        if (AutofillFieldLexicon.NON_CREDENTIAL_SUBSTRING_TERMS.any { haystack.contains(it) }) return true
        return AutofillFieldLexicon.tokensOf(haystack).any { it in AutofillFieldLexicon.NON_CREDENTIAL_TOKEN_TERMS }
    }

    private data class Candidate(
        val id: String,
        val score: Int,
        /** ISSUE-P3-529：是否仅由「数字类兜底档」成立（登录上下文门与择优次序用） */
        val numericFallback: Boolean = false
    )
}
