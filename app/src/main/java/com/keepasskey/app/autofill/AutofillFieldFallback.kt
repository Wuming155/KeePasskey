package com.keepasskey.app.autofill

/**
 * 解析层兜底（ISSUE-P3-372 AC① / AC②，对齐 Monica `EnhancedAutofillStructureParserV2`
 * 的弱目标二次解析与 `buildFocusedSyntheticItems` 聚焦合成，行为面按本仓词表重写）。
 *
 * 全部为纯函数，JVM 可测；**只在识别层做加性召回**，不触碰任何放行判定
 * （域归属 / 包名绑定 / 字段级屏蔽仍由既有闸门裁决）。
 *
 * 两段兜底的执行次序（由 `resolveTargetFields` 编排）：
 * 1. [runWeakReparse] —— 首轮严格扫描零登录目标时的第二轮「干草堆术语」扫描；
 * 2. [synthesizeFocusedUsername] —— 识别到密码框但缺账号框时，从聚焦字段合成账号目标。
 */
internal object AutofillFieldFallback {

    /**
     * AC① 弱目标二次解析：首轮（[AutofillFieldScanner.scan]）零账号且零密码目标时，
     * 把「htmlName + label + autofillHints」拼成干草堆，直接按既有中英/西里尔术语词表
     * 再扫一轮——覆盖结构化信号查不到的形态（如 htmlName 为「密码框」「用户名输入」的
     * 中文命名、登录术语只出现在 hint 列表里等）。
     *
     * 与首轮**同口径**的部分（一律不放宽）：
     * - `importantForAutofill` 尊重（[respectImportantForAutofill]）；
     * - 搜索框 / 非凭据字段排除、OTP 框排除；
     * - 不可见账号框不参与、不可见密码框仍准入。
     *
     * **登录上下文门**（防误弹）：二次解析仅产出密码目标，或全页任一节点的干草堆含
     * 密码术语时才采纳；否则原样返回首轮结果（仍为零目标）。
     *
     * @param pass1 首轮扫描结果（调用方仅在零登录目标时进入本函数；本函数内部再守一道）
     */
    fun runWeakReparse(
        nodes: List<ScanNode>,
        respectImportantForAutofill: Boolean,
        pass1: ScanResult
    ): ScanResult {
        if (pass1.usernameId != null || pass1.passwordId != null) return pass1

        var usernameHit: String? = null
        var usernameRank = Int.MIN_VALUE
        var passwordHit: String? = null
        var passwordRank = Int.MIN_VALUE

        for (node in nodes) {
            if (respectImportantForAutofill && !node.importantForAutofill) continue
            if (AutofillFieldScanner.isSearchField(node)) continue
            if (AutofillFieldScanner.isNonCredentialField(node)) continue
            if (AutofillFieldScanner.otpSignal(node) != FieldConfidence.NONE) continue

            val haystack = haystackOf(node)
            if (haystack.isEmpty()) continue

            if (AutofillFieldScanner.isPasswordLabel(haystack)) {
                // 密码类与首轮同口径：不可见也准入
                val rank = rank(FieldConfidence.LOW, node.isFocused)
                if (rank > passwordRank) {
                    passwordRank = rank
                    passwordHit = node.id
                }
                continue
            }
            if (!node.isVisible) continue
            if (AutofillFieldScanner.isUsernameLabel(haystack)) {
                val rank = rank(FieldConfidence.LOW, node.isFocused)
                if (rank > usernameRank) {
                    usernameRank = rank
                    usernameHit = node.id
                }
            }
        }

        if (passwordHit == null && usernameHit == null) return pass1
        // 登录上下文门：无密码目标时，全页须含密码术语才采纳弱账号目标
        if (passwordHit == null && nodes.none { AutofillFieldScanner.isPasswordLabel(haystackOf(it)) }) {
            return pass1
        }

        return pass1.copy(
            usernameId = usernameHit,
            passwordId = passwordHit,
            usernameConfidence = usernameHit?.let { FieldConfidence.LOW } ?: FieldConfidence.NONE,
            passwordConfidence = passwordHit?.let { FieldConfidence.LOW } ?: FieldConfidence.NONE,
            isPasswordOnlyLogin = passwordHit != null && usernameHit == null,
            usedWeakReparse = true
        )
    }

    /**
     * AC② 聚焦字段补齐：识别到密码框但缺账号框时，把「当前聚焦且未被选中」的可编辑
     * 文本字段合成为账号目标（对齐 Monica `buildFocusedSyntheticItems` 的
     * 「有密码上下文才合成非标准聚焦文本框」口径），置信度按 MEDIUM（聚焦即页面主交互面）。
     *
     * 安全约束：搜索框 / 非凭据字段 / OTP 框 / 带密码信号的字段一律不合成；
     * 输入类型须为未知（0，WebView 常态）或纯文本/账号类（非密码变体）；
     * `importantForAutofill` 与可见性同首轮口径。找不到即如实不合成。
     */
    fun synthesizeFocusedUsername(
        result: ScanResult,
        nodes: List<ScanNode>,
        respectImportantForAutofill: Boolean
    ): ScanResult {
        if (result.usernameId != null || result.passwordId == null) return result

        val chosen = nodes.firstOrNull { node ->
            node.isFocused &&
                node.isVisible &&
                node.id != result.passwordId &&
                node.id != result.otpId &&
                (!respectImportantForAutofill || node.importantForAutofill) &&
                !AutofillFieldScanner.isSearchField(node) &&
                !AutofillFieldScanner.isNonCredentialField(node) &&
                AutofillFieldScanner.passwordSignal(node) == FieldConfidence.NONE &&
                AutofillFieldScanner.otpSignal(node) == FieldConfidence.NONE &&
                isSynthesizableInput(node.inputType)
        } ?: return result

        return result.copy(
            usernameId = chosen.id,
            usernameConfidence = FieldConfidence.MEDIUM,
            isPasswordOnlyLogin = false
        )
    }

    /** 干草堆 = htmlName + label + 全部 autofillHints（小写交由词表函数处理）；空白项剔除 */
    private fun haystackOf(node: ScanNode): String = buildString {
        node.htmlName?.takeIf { it.isNotBlank() }?.let { append(it).append(' ') }
        node.label?.takeIf { it.isNotBlank() }?.let { append(it).append(' ') }
        node.autofillHints.forEach { hint -> if (hint.isNotBlank()) append(hint).append(' ') }
    }.trim()

    /** 合成目标的输入类型闸门：未知（0）/ 纯文本（非密码变体）/ 账号类（邮箱、电话） */
    private fun isSynthesizableInput(inputType: Int): Boolean = when {
        inputType == 0 -> true
        AutofillFieldScanner.isPasswordInputType(inputType) -> false
        AutofillFieldScanner.isAccountInputType(inputType) -> true
        else -> (inputType and INPUT_TYPE_CLASS_MASK) == INPUT_TYPE_CLASS_TEXT
    }

    /** 与 [AutofillFieldScanner] 内部 rank 同式：置信度主序、聚焦加成 */
    private fun rank(confidence: FieldConfidence, isFocused: Boolean): Int =
        confidence.score * 10 + if (isFocused) 1 else 0

    /** `InputType.TYPE_MASK_CLASS`（0x0000000f）——仅本文件合成闸门使用 */
    private const val INPUT_TYPE_CLASS_MASK = 0x0000000f

    /** `InputType.TYPE_CLASS_TEXT`（0x00000001） */
    private const val INPUT_TYPE_CLASS_TEXT = 0x00000001
}
