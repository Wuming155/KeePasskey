package com.keepasskey.app.ui.screens.conflict

import com.keepasskey.app.ui.model.UiMessage

enum class FieldChoice {
    LOCAL,
    REMOTE
}

/**
 * ISSUE-P2-281 AC②／AC③：条目级裁决方式（整条兜底入口）。
 * [FIELD_BY_FIELD] 逐字段裁决（默认）；其余三档整条裁决，
 * [DUPLICATE_BOTH] 使 `KdbxMerger.resolveConflict` 的已实现分支真实可达。
 */
enum class EntryResolutionMode {
    FIELD_BY_FIELD,
    KEEP_LOCAL,
    KEEP_REMOTE,
    DUPLICATE_BOTH
}

data class ConflictedField(
    // TASK-30 / ISSUE-P2-281：差异键（与 ConflictedEntryPair.modifiedFields 同一词汇表：
    // 标准字段键 / `custom:` 前缀自定义字段键 / 四个标量键），applyMerge 据此生成字段级合并决策
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
    val fields: List<ConflictedField>,
    /** ISSUE-P2-281：条目级裁决方式（整条兜底入口），默认逐字段 */
    val mode: EntryResolutionMode = EntryResolutionMode.FIELD_BY_FIELD
)

data class ConflictResolutionUiState(
    // H1 整改：两侧修改时间由真实冲突条目的 lastModificationTime 填充，空串表示尚未取得
    val localModifiedTime: String = "",
    val remoteModifiedTime: String = "",
    val entries: List<ConflictedEntryItem> = emptyList(),
    val isResolving: Boolean = false,
    val userMessage: UiMessage? = null
)
