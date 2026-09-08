package com.keepasskey.app.ui.screens.conflict

import com.keepasskey.app.ui.model.UiMessage

enum class FieldChoice {
    LOCAL,
    REMOTE
}

data class ConflictedField(
    // TASK-30：字段标准键（KdbxConstants.Fields.*），applyMerge 据此生成字段级合并决策
    val fieldKey: String,
    val fieldName: String,
    val localValue: String,
    val remoteValue: String,
    val selectedChoice: FieldChoice = FieldChoice.LOCAL,
    val isSensitive: Boolean = false
)

data class ConflictedEntryItem(
    val id: String,
    val title: String,
    val groupPath: String,
    val fields: List<ConflictedField>
)

data class ConflictResolutionUiState(
    // H1 整改：两侧修改时间由真实冲突条目的 lastModificationTime 填充，空串表示尚未取得
    val localModifiedTime: String = "",
    val remoteModifiedTime: String = "",
    val entries: List<ConflictedEntryItem> = emptyList(),
    val isResolving: Boolean = false,
    val userMessage: UiMessage? = null
)
