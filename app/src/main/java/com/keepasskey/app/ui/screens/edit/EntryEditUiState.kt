package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.ui.model.UiMessage

import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.VaultGroup

/**
 * 凭据编辑/添加页面的不可变 UI 状态
 *
 * M1 整改（加解密审查 2026-09）：本状态类不再承载密码明文（String 不可变驻留 StateFlow 堆内存，
 * 且每次 update 都会产生新副本）。密码经 EntryEditViewModel 的 CharArray 私有链路承载，
 * UI 仅感知 [passwordLength] 用于强度条渲染。
 *
 * TASK-10 整改（加解密审查 B9）：TOTP 种子与受保护自定义字段明文同样退出本状态类——
 * 前者由 ViewModel 的 CharArray 私有链路承载，后者在 UI 投影中恒为空串（与详情页读路径
 * 的掩码投影语义一致），编辑明文经 ViewModel 的 CharArray 私有链路承载与显式提交。
 */
data class EntryEditUiState(
    val entryId: String? = null,
    val groupId: String? = null,
    val availableGroups: List<VaultGroup> = emptyList(),
    val iconName: String = "key",
    val title: String = "",
    val username: String = "",
    /** 密码长度（非敏感元数据，用于强度条）；密码明文本身经 ViewModel CharArray 链路 */
    val passwordLength: Int = 0,
    val url: String = "",
    val notes: String = "",
    val isPasskey: Boolean = false,
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
