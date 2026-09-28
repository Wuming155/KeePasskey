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
     */
    fun valuesFor(
        entry: KdbxEntry,
        roles: Collection<StructuredFieldRole>
    ): Map<StructuredFieldRole, String> {
        val out = LinkedHashMap<StructuredFieldRole, String>()
        for (role in roles) {
            val raw = entry.customFields.firstOrNull { it.key == fieldNameFor(role) }
                ?.value?.readString()
                .orEmpty()
            if (raw.isNotBlank()) out[role] = raw
        }
        return out
    }

    /** 条目是否携带任一结构化字段（选择器「明确标注」用，AC④） */
    fun hasAnyStructuredData(entry: KdbxEntry): Boolean =
        entry.customFields.any { field -> FIELD_NAMES.values.contains(field.key) }

    /** 条目是否具备全部所需字段（结构化模式的列表过滤，AC④ 交付安全面） */
    fun hasAllFields(entry: KdbxEntry, roles: Collection<StructuredFieldRole>): Boolean =
        roles.isNotEmpty() && roles.all { hasField(entry, it) }

    private fun hasField(entry: KdbxEntry, role: StructuredFieldRole): Boolean {
        val name = fieldNameFor(role)
        return entry.customFields.any { it.key == name && it.value.readString().isNotBlank() }
    }

    private fun normalize(raw: String): String =
        raw.lowercase().trim().replace("-", "").replace("_", "").replace(" ", "")
}
