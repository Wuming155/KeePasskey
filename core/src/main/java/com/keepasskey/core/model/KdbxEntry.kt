package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString

/**
 * 不可变条目领域模型。
 * 遵循敏感数据铁律：敏感密码采用 [ProtectedString] 封装。
 */
data class KdbxEntry(
    val id: KdbxUuid = KdbxUuid.random(),
    val parentGroupId: KdbxUuid? = null,
    val iconId: Int = 0,
    val customIconId: KdbxUuid? = null,
    val fields: Map<String, ProtectedString> = emptyMap(),
    val customFields: List<KdbxCustomField> = emptyList(),
    val times: KdbxTimes = KdbxTimes(),
    val history: List<KdbxEntry> = emptyList(),
    val tags: List<String> = emptyList(),
    val attachments: List<KdbxAttachment> = emptyList(),
    val autoType: KdbxAutoType? = null,
    val backgroundColor: String? = null,
    val foregroundColor: String? = null,
    val overrideUrl: String? = null,
    val qualityCheck: Boolean = true,
    val previousParentGroup: KdbxUuid? = null,
    val customData: Map<String, String> = emptyMap()
) {
    val title: String
        get() = fields[KdbxConstants.Fields.TITLE]?.readString().orEmpty()

    val userName: String
        get() = fields[KdbxConstants.Fields.USER_NAME]?.readString().orEmpty()

    val password: ProtectedString?
        get() = fields[KdbxConstants.Fields.PASSWORD]

    val url: String
        get() = fields[KdbxConstants.Fields.URL]?.readString().orEmpty()

    val notes: String
        get() = fields[KdbxConstants.Fields.NOTES]?.readString().orEmpty()

    fun withField(key: String, value: ProtectedString): KdbxEntry {
        val newFields = fields.toMutableMap()
        newFields[key] = value
        return copy(
            fields = newFields,
            times = times.withModified()
        )
    }

    fun withField(key: String, value: String, isProtected: Boolean = false): KdbxEntry {
        return withField(key, ProtectedString(value, isProtected))
    }

    fun clearSensitiveData() {
        fields.values.forEach { it.clear() }
        customFields.forEach { it.value.clear() }
        attachments.forEach { it.clear() }
        history.forEach { it.clearSensitiveData() }
    }

    /**
     * ISSUE-P2-06 定点擦除：只清本节点**直接持有**的受保护实例
     * （fields / customFields / attachments），不递归。
     *
     * copy-on-write 会让新树与旧树共享未被修改的 [ProtectedString] / [KdbxAttachment]
     * 实例（如 [withField] 仅在 fields 中替换目标键、copy() 保留其余引用），
     * 因此**严禁**改用 [clearSensitiveData] 递归擦除下线旧树——那会连带清掉存活树
     * 仍在引用的共享实例，造成数据丢失。跨树的下线擦除请使用
     * [KdbxGroup.clearSupersededSensitiveData]，由身份集合判定真正的“下线”实例。
     *
     * history 不在本方法的无条件擦除范围内：copy() 会让新旧节点共享同一 history 列表，
     * 无条件清会破坏存活条目；下线 history 项由 [clearSensitiveIdentitiesNotIn] 按身份处理。
     */
    fun clearOwnSensitiveData() {
        fields.values.forEach { it.clear() }
        customFields.forEach { it.value.clear() }
        attachments.forEach { it.clear() }
    }

    /** 收集本节点（含 history）可达的全部敏感实例身份（配合身份集合使用，按引用相等判定） */
    internal fun collectSensitiveIdentities(into: MutableSet<Any>) {
        fields.values.forEach { into.add(it) }
        customFields.forEach { into.add(it.value) }
        attachments.forEach { into.add(it) }
        history.forEach { it.collectSensitiveIdentities(into) }
    }

    /**
     * 擦除本节点（含 history）中**未被身份集合 [live] 引用**的敏感实例。
     * [live] 必须是身份集合（如 Collections.newSetFromMap(IdentityHashMap())），
     * 其 contains 走引用相等，避免把共享同一对象的存活实例一并擦除。
     */
    internal fun clearSensitiveIdentitiesNotIn(live: Set<Any>) {
        fields.values.forEach { if (it !in live) it.clear() }
        customFields.forEach { if (it.value !in live) it.value.clear() }
        attachments.forEach { if (it !in live) it.clear() }
        history.forEach { it.clearSensitiveIdentitiesNotIn(live) }
    }

    override fun toString(): String {
        // P2-4 整改：数据类默认 toString 会展开 fields/customFields/history 等集合，
        // 任何隐式字符串化（日志、调试、异常消息）都不该物化字段内容——
        // 此处仅呈现结构摘要；字段明文一律经 ProtectedString 显式 readChars()/readString() 按需读取
        return "KdbxEntry(id=$id, parentGroupId=$parentGroupId, iconId=$iconId, " +
            "fields=${fields.size}, customFields=${customFields.size}, " +
            "attachments=${attachments.size}, history=${history.size}, tags=$tags)"
    }
}
