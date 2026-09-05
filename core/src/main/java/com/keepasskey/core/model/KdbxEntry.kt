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
}
