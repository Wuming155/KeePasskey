package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.ui.model.UiMessage

import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.VaultGroup

/**
 * 凭据编辑/添加页面的不可变 UI 状态
 */
data class EntryEditUiState(
    val entryId: String? = null,
    val groupId: String? = null,
    val availableGroups: List<VaultGroup> = emptyList(),
    val iconName: String = "key",
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val isPasskey: Boolean = false,
    val totpSecret: String = "",
    val customFields: List<UiCustomField> = emptyList(),
    val attachments: List<UiAttachment> = emptyList(),
    // KP2A 能力补齐：标签（逗号/空格分隔输入）与 AutoType 默认序列、Override URL
    val tagsInput: String = "",
    val autoTypeSequence: String = "",
    val overrideUrl: String = "",
    val isPasswordVisible: Boolean = false,
    val showGenerator: Boolean = false,
    val passLength: Float = 20f,
    val useUpper: Boolean = true,
    val useLower: Boolean = true,
    val useDigits: Boolean = true,
    val useSymbols: Boolean = true,
    val excludeConfusing: Boolean = true,
    val userMessage: UiMessage? = null,
    val isSaved: Boolean = false,
    // H4-只读整改：数据库以只读模式打开时禁用保存
    val isReadOnly: Boolean = false,
    // 表单脏标记：发生任何未保存修改后为 true，驱动返回前的丢弃确认
    val isDirty: Boolean = false
)
