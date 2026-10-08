package com.keepasskey.app.autofill

import com.keepasskey.core.model.KdbxEntry

/**
 * 结构化数据填充的字段角色（ISSUE-P3-375，吸收 Monica `AutofillStructuredDataSupport`）。
 *
 * 存储口径（条目立规时确定）：**不新增条目类型**——结构化数据落在 KDBX 条目**自定义字符串
 * 字段**，字段名采用 Android autofill hint 惯例（见 `StructuredFieldPolicy.fieldNameFor`），
 * 与「字符串字段承载结构化数据」的生态形态一致；**不碰** passkey schema（`KPEX_PASSKEY_*`）。
 */
enum class StructuredFieldRole {
    /** 卡号（`creditCardNumber`） */
    CREDIT_CARD_NUMBER,

    /** 卡背面安全码（`creditCardSecurityCode`） */
    CREDIT_CARD_SECURITY_CODE,

    /** 卡有效期月（`creditCardExpirationMonth`） */
    CREDIT_CARD_EXPIRATION_MONTH,

    /** 卡有效期年（`creditCardExpirationYear`） */
    CREDIT_CARD_EXPIRATION_YEAR,

    /** 街道地址（`streetAddress`） */
    POSTAL_STREET_ADDRESS,

    /** 城市 / 地方（`addressLocality`） */
    POSTAL_ADDRESS_LOCALITY,

    /** 省 / 州 / 地区（`addressRegion`） */
    POSTAL_ADDRESS_REGION,

    /** 邮政编码（`postalCode`） */
    POSTAL_CODE
}

/**
 * 结构化数据填充的识别 / 候选 / 取值策略（ISSUE-P3-375 AC①②③，纯函数，JVM 可测）。
 *
 * 三源识别（AC①，置信分层）：
 * 1. **autofillHints**（平台显式 hint，如 `creditCardNumber`）⇒ 可填充；
 * 2. **html autocomplete**（如 `cc-number` / `postal-code`）⇒ 可填充；
 * 3. **label 术语**（卡号 / CVV / 邮编 …）⇒ **识别但不填充**——界面文案易受
 *    「地址」「有效期」等日常词误触（收货表单 ≠ 支付表单），置信不足按 AC① 不填；
 *    由 [labelRoleOf] 承载并以用例锁定「识别 ≠ 放行」。
 *
 * 候选供给（AC②）：**含全部所需字段**的条目才入选（缺字段的卡不入选，AC③），按最后修改
 * 时间降序、上限 [SELECT_LIMIT]；不走域名匹配——卡 / 地址是跨站点上下文（与 Monica 同型），
 * 安全面由「确认页二次认证 + 调用方归属展示」承担（见批次 §355.2）。
 *
 * ISSUE-P2-380：识别与取值**同时**接受内置信用卡模板中文键（「卡号/持卡人/有效期」等，
 * 见 `VaultTemplateFactory` / `VaultEntryMapper.CARD_FIELD_*`）与 Android hint 键
 * （`creditCardNumber` 等）；存量中文名录入的条目无需迁移即可被结构化填充。
 * 取值时 hint 键优先，其次中文模板键（避免双写冲突时静默取错）。
 */
object StructuredFieldPolicy {

    /** 候选上限（结构化数据集条数；同卡重复展示无意义） */
    const val SELECT_LIMIT = 4

    /** 角色 → KDBX 自定义字段名（Android autofill hint 惯例，单点收敛） */
    private val FIELD_NAMES = mapOf(
        StructuredFieldRole.CREDIT_CARD_NUMBER to "creditCardNumber",
        StructuredFieldRole.CREDIT_CARD_SECURITY_CODE to "creditCardSecurityCode",
        StructuredFieldRole.CREDIT_CARD_EXPIRATION_MONTH to "creditCardExpirationMonth",
        StructuredFieldRole.CREDIT_CARD_EXPIRATION_YEAR to "creditCardExpirationYear",
        StructuredFieldRole.POSTAL_STREET_ADDRESS to "streetAddress",
        StructuredFieldRole.POSTAL_ADDRESS_LOCALITY to "addressLocality",
        StructuredFieldRole.POSTAL_ADDRESS_REGION to "addressRegion",
        StructuredFieldRole.POSTAL_CODE to "postalCode"
    )

    /**
     * ISSUE-P2-380：角色 → 内置信用卡模板中文键（与 `VaultEntryMapper.CARD_FIELD_*` 一致）。
     * 「有效期」为复合字段（月/年同键）：取值时若能解析 `MM/YY` 或 `MMYYYY` 再拆到月/年角色。
     */
    private val TEMPLATE_ZH_FIELD_NAMES = mapOf(
        StructuredFieldRole.CREDIT_CARD_NUMBER to listOf("卡号", "Card Number"),
        StructuredFieldRole.CREDIT_CARD_SECURITY_CODE to listOf("CVV", "CVC"),
        StructuredFieldRole.CREDIT_CARD_EXPIRATION_MONTH to listOf("有效期", "Expiry", "Expiry Date"),
        StructuredFieldRole.CREDIT_CARD_EXPIRATION_YEAR to listOf("有效期", "Expiry", "Expiry Date"),
        StructuredFieldRole.POSTAL_STREET_ADDRESS to listOf("街道地址", "地址"),
        StructuredFieldRole.POSTAL_ADDRESS_LOCALITY to listOf("城市"),
        StructuredFieldRole.POSTAL_ADDRESS_REGION to listOf("省份", "州"),
        StructuredFieldRole.POSTAL_CODE to listOf("邮编", "邮政编码")
    )

    /**
     * hint / autocomplete 归一化值 → 角色。
     * 键 = 去 `-`/`_`/空格 + 小写；值 = Android autofill hint 名与 HTML autocomplete
     * 标准 token（`cc-number` / `cc-csc` / `cc-exp-month` / `address-level1|2` 等）两族合一。
     */
    private val SOURCE_TOKENS = mapOf(
        "creditcardnumber" to StructuredFieldRole.CREDIT_CARD_NUMBER,
        "ccnumber" to StructuredFieldRole.CREDIT_CARD_NUMBER,
        "creditcardsecuritycode" to StructuredFieldRole.CREDIT_CARD_SECURITY_CODE,
        "cccsc" to StructuredFieldRole.CREDIT_CARD_SECURITY_CODE,
        "cccvc" to StructuredFieldRole.CREDIT_CARD_SECURITY_CODE,
        "cccvv" to StructuredFieldRole.CREDIT_CARD_SECURITY_CODE,
        "creditcardexpirationmonth" to StructuredFieldRole.CREDIT_CARD_EXPIRATION_MONTH,
        "ccexpmonth" to StructuredFieldRole.CREDIT_CARD_EXPIRATION_MONTH,
        "creditcardexpirationyear" to StructuredFieldRole.CREDIT_CARD_EXPIRATION_YEAR,
        "ccexpyear" to StructuredFieldRole.CREDIT_CARD_EXPIRATION_YEAR,
        "streetaddress" to StructuredFieldRole.POSTAL_STREET_ADDRESS,
        "addresslocality" to StructuredFieldRole.POSTAL_ADDRESS_LOCALITY,
        "addresslevel2" to StructuredFieldRole.POSTAL_ADDRESS_LOCALITY,
        "addressregion" to StructuredFieldRole.POSTAL_ADDRESS_REGION,
        "addresslevel1" to StructuredFieldRole.POSTAL_ADDRESS_REGION,
        "postalcode" to StructuredFieldRole.POSTAL_CODE
    )

    /**
     * label 术语 → 角色（子串、大小写不敏感；**只识别、不放行**——见类 KDoc 三源 3）。
     * 迭代顺序即判定优先级：卡类先于地址类（「信用卡有效期」先归卡年 / 卡月）。
     */
    private val LABEL_TERMS = listOf(
        StructuredFieldRole.CREDIT_CARD_NUMBER to listOf("卡号", "card number", "cardnumber"),
        StructuredFieldRole.CREDIT_CARD_SECURITY_CODE to listOf("cvv", "cvc", "安全码"),
        StructuredFieldRole.CREDIT_CARD_EXPIRATION_YEAR to listOf("有效期年", "exp year", "expiry year"),
        StructuredFieldRole.CREDIT_CARD_EXPIRATION_MONTH to listOf("有效期月", "exp month", "expiry month"),
        StructuredFieldRole.POSTAL_CODE to listOf("邮编", "邮政编码", "zip code", "postcode", "postal code"),
        StructuredFieldRole.POSTAL_STREET_ADDRESS to listOf("街道地址", "street address"),
        StructuredFieldRole.POSTAL_ADDRESS_REGION to listOf("省份", "address region"),
        StructuredFieldRole.POSTAL_ADDRESS_LOCALITY to listOf("所在城市", "city")
    )

    /** 角色 → 自定义字段名（存储口径单点；对外只读） */
    fun fieldNameFor(role: StructuredFieldRole): String =
        FIELD_NAMES.getValue(role)

    /**
     * ISSUE-P2-380：角色 → 可接受的字段名候选（hint 键在前、模板中文键在后）。
     * 供 `hasField` / `valuesFor` 多键探测；对外只读。
     */
    fun acceptedFieldNamesFor(role: StructuredFieldRole): List<String> {
        val hint = FIELD_NAMES.getValue(role)
        val zh = TEMPLATE_ZH_FIELD_NAMES[role].orEmpty()
        return (listOf(hint) + zh).distinct()
    }

    /** 角色 → 模板英文/中文别名（展示与编辑面提示用） */
    fun templateAliasNamesFor(role: StructuredFieldRole): List<String> =
        TEMPLATE_ZH_FIELD_NAMES[role].orEmpty()

    /**
     * AC① 识别（hint / autocomplete 两源 ⇒ 可填充；label 源见 [labelRoleOf]）。
     *
     * @param respectImportantForAutofill 页面 `importantForAutofill` 尊重口径（与登录扫描同参）
     * @return 角色 → 节点索引 id；同角色多节点取**首个**（页面声明顺序，确定性）
     */
    fun detectFillableTargets(
        nodes: List<ScanNode>,
        respectImportantForAutofill: Boolean = true
    ): Map<StructuredFieldRole, String> {
        val targets = LinkedHashMap<StructuredFieldRole, String>()
        for (node in nodes) {
            if (!node.isVisible) continue
            if (respectImportantForAutofill && !node.importantForAutofill) continue
            val role = roleFromHint(node.autofillHints) ?: roleFromAutocomplete(node.autocomplete)
                ?: continue
            if (role !in targets) targets[role] = node.id
        }
        return targets
    }

    /** hint 源：归一化（去 `-`/`_`/空格、小写）后查表 */
    fun roleFromHint(hints: List<String>): StructuredFieldRole? {
        for (hint in hints) {
            SOURCE_TOKENS[normalize(hint)]?.let { return it }
        }
        return null
    }

    /** autocomplete 源：多值形如 `cc-number cc-exp` 按空白 / 逗号 / 分号切分后查表 */
    fun roleFromAutocomplete(autocomplete: String?): StructuredFieldRole? {
        if (autocomplete.isNullOrBlank()) return null
        for (token in autocomplete.split(' ', ',', ';')) {
            val normalized = normalize(token)
            if (normalized.isEmpty()) continue
            SOURCE_TOKENS[normalized]?.let { return it }
        }
        return null
    }

    /**
     * label 源（AC① 三源之三）：**只识别、不放行**——[detectFillableTargets] 不采信本源；
     * 独立暴露供单测锁定「识别存在且置信不足不进填充」。
     */
    fun labelRoleOf(label: String?): StructuredFieldRole? {
        val v = label?.lowercase()?.trim().orEmpty()
        if (v.isEmpty()) return null
        for ((role, terms) in LABEL_TERMS) {
            if (terms.any { v.contains(it.lowercase()) }) return role
        }
        return null
    }

    /**
     * AC②③ 候选供给：含**全部**所需字段的条目（缺字段的卡不入选），
     * 按最后修改时间降序、上限 [limit]。
     */
    fun selectCandidates(
        entries: List<KdbxEntry>,
        requiredRoles: Set<StructuredFieldRole>,
        limit: Int = SELECT_LIMIT
    ): List<KdbxEntry> {
        if (requiredRoles.isEmpty()) return emptyList()
        return entries
            .filter { entry -> requiredRoles.all { hasField(entry, it) } }
            .sortedByDescending { it.times.lastModificationTime }
            .take(limit.coerceAtLeast(1))
    }

    /**
     * AC③ 取值：按角色读自定义字段；空白值 / 缺字段**缺席**（填充侧只写非空值）。
     * 明文物化只允许发生在**用户确认之后**的交付路径（确认页 / 选择器）。
     *
     * ISSUE-P2-380：hint 键优先；命中中文模板键时，有效期类角色若读到
     * `MM/YY` / `MM/YYYY` / `YYYY-MM` 等复合形态再拆月/年。
     */
    fun valuesFor(
        entry: KdbxEntry,
        roles: Collection<StructuredFieldRole>
    ): Map<StructuredFieldRole, String> {
        val out = LinkedHashMap<StructuredFieldRole, String>()
        val byKey = entry.customFields.associateBy { it.key }
        for (role in roles) {
            val raw = readFieldByAnyKey(byKey, role)?.orEmpty().orEmpty()
            val resolved = resolveRoleValue(role, raw, byKey, out)
            if (resolved.isNotBlank()) out[role] = resolved
        }
        return out
    }

    private fun resolveRoleValue(
        role: StructuredFieldRole,
        raw: String,
        byKey: Map<String, com.keepasskey.core.model.KdbxCustomField>,
        already: Map<StructuredFieldRole, String>
    ): String {
        if (role !in setOf(
                StructuredFieldRole.CREDIT_CARD_EXPIRATION_MONTH,
                StructuredFieldRole.CREDIT_CARD_EXPIRATION_YEAR
            )
        ) {
            return raw
        }
        // 复合有效期已由另一角色解析出月/年时，不再二次解析（避免重复占用同一源）
        val counterpart = when (role) {
            StructuredFieldRole.CREDIT_CARD_EXPIRATION_MONTH -> StructuredFieldRole.CREDIT_CARD_EXPIRATION_YEAR
            else -> StructuredFieldRole.CREDIT_CARD_EXPIRATION_MONTH
        }
        val counterpartResolved = already[counterpart]
        if (counterpartResolved != null) {
            // 对方角色已给出结果；本角色仍可从同一复合串解析自己的半边
            return parseExpiryHalf(role, raw) ?: counterpartResolved
        }
        // hint 键优先（本角色独立字段名）
        val hintRaw = byKey[fieldNameFor(role)]?.value?.readString()?.orEmpty().orEmpty()
        if (hintRaw.isNotBlank()) return hintRaw
        // 中文/模板键：复合串拆半
        return parseExpiryHalf(role, raw) ?: raw
    }

    private fun parseExpiryHalf(role: StructuredFieldRole, raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        // MM/YY | MM/YYYY | MM-YY | MMYYYY(4/6位) | YYYY-MM
        val slash = v.split('/', '-', '.')
        if (slash.size >= 2) {
            val a = slash[0].trim()
            val b = slash[1].trim()
            return when (role) {
                StructuredFieldRole.CREDIT_CARD_EXPIRATION_MONTH -> a.takeIf { it.isNotEmpty() }
                StructuredFieldRole.CREDIT_CARD_EXPIRATION_YEAR -> b.takeIf { it.isNotEmpty() }
                else -> null
            }
        }
        val digits = v.filter { it.isDigit() }
        return when (role) {
            StructuredFieldRole.CREDIT_CARD_EXPIRATION_MONTH -> when (digits.length) {
                4 -> digits.take(2)
                6 -> digits.take(2)
                else -> null
            }

            StructuredFieldRole.CREDIT_CARD_EXPIRATION_YEAR -> when (digits.length) {
                4 -> digits
                6 -> digits.takeLast(2)
                else -> null
            }

            else -> null
        }
    }

    private fun readFieldByAnyKey(
        byKey: Map<String, com.keepasskey.core.model.KdbxCustomField>,
        role: StructuredFieldRole
    ): String? {
        for (key in acceptedFieldNamesFor(role)) {
            val v = byKey[key]?.value?.readString()?.orEmpty().orEmpty()
            if (v.isNotBlank()) return v
        }
        return null
    }

    /** 条目是否携带任一结构化字段（选择器「明确标注」用，AC④；含模板中文键） */
    fun hasAnyStructuredData(entry: KdbxEntry): Boolean {
        val accepted = FIELD_NAMES.values + TEMPLATE_ZH_FIELD_NAMES.values.flatten()
        return entry.customFields.any { field -> field.key in accepted }
    }

    /** 条目是否具备全部所需字段（结构化模式的列表过滤，AC④ 交付安全面；含模板中文键） */
    fun hasAllFields(entry: KdbxEntry, roles: Collection<StructuredFieldRole>): Boolean =
        roles.isNotEmpty() && roles.all { hasField(entry, it) }

    private fun hasField(entry: KdbxEntry, role: StructuredFieldRole): Boolean {
        val byKey = entry.customFields.associateBy { it.key }
        return acceptedFieldNamesFor(role).any { name ->
            byKey[name]?.value?.readString()?.isNotBlank() == true
        }
    }

    private fun normalize(raw: String): String =
        raw.lowercase().trim().replace("-", "").replace("_", "").replace(" ", "")
}
