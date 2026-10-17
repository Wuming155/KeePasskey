package com.keepasskey.app.ui.screens.conflict

import com.keepasskey.app.ui.model.UiMessage

enum class FieldChoice {
    LOCAL,
    REMOTE,
    /** Only offered when the remote snapshot is missing this field. */
    DELETE_REMOTE
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

/**
 * ISSUE-P3-360 AC①：两侧修改时间的先后标注（仅用于「较新」徽标，非字段选择语义）。
 */
enum class ModifiedTimeSide {
    LOCAL,
    REMOTE
}

data class ConflictedField(
    // TASK-30 / ISSUE-P2-281：差异键（与 ConflictedEntryPair.modifiedFields 同一词汇表：
    // 标准字段键 / `custom:` 前缀自定义字段键 / 标量 / 联合到期 / AutoType / 颜色键），applyMerge 据此生成字段级合并决策
    val fieldKey: String,
    val fieldName: String,
    val localValue: String,
    val remoteValue: String,
    val selectedChoice: FieldChoice = FieldChoice.LOCAL,
    val isSensitive: Boolean = false,
    /** Presence is independent of an empty string value. */
    val localMissing: Boolean = false,
    val remoteMissing: Boolean = false
)

data class ConflictedEntryItem(
    val id: String,
    val title: String,
    val groupPath: String,
    val fields: List<ConflictedField>,
    /** ISSUE-P2-281：条目级裁决方式（整条兜底入口），默认逐字段 */
    val mode: EntryResolutionMode = EntryResolutionMode.FIELD_BY_FIELD
)

/**
 * `ISSUE-P2-660`：分组级裁决方式。
 *
 * 分组不提供「按字段裁决」：分组差异键虽有字段词汇（`KdbxGroupConflictKeys`），但分组是
 * 容器节点，字段级混选会让「子树归属」与「组名来源」分离，用户难以预期。三档与条目级整条
 * 裁决同构——[DUPLICATE_BOTH] 走 `KdbxGroupDuplicator` 复制整棵子树。
 */
enum class GroupResolutionMode {
    KEEP_LOCAL,
    KEEP_REMOTE,
    DUPLICATE_BOTH
}

/**
 * `ISSUE-P2-660`：界面上的一个**分组冲突**项。
 *
 * [fields] 只作只读差异展示（两侧取值），裁决粒度是整个分组（[mode]）。
 */
data class ConflictedGroupItem(
    val id: String,
    val name: String,
    val fields: List<ConflictedField>,
    val mode: GroupResolutionMode = GroupResolutionMode.KEEP_LOCAL
)

/**
 * `ISSUE-P2-659`：界面上的库级 Meta 冲突项（库名 / 描述 / 默认用户名 / 颜色 /
 * 历史上限 / 库级自定义数据）。
 *
 * 与条目共用 [ConflictedField] 与逐字段选择 UI；不提供「整条双方保留」——标量没有并集语义，
 * 也不提供 `DELETE_REMOTE`（库级设置不存在「云端删了该字段」这一状态）。
 */
data class ConflictedMetaItem(
    val fields: List<ConflictedField>
)

data class ConflictResolutionUiState(
    // H1 整改：两侧修改时间由真实冲突条目的 lastModificationTime 填充，空串表示尚未取得
    val localModifiedTime: String = "",
    val remoteModifiedTime: String = "",
    // ISSUE-P3-360 AC①：哪一侧更新（VM 按 Instant 现场比较，不比格式化文案）；null = 无法判定
    val newerSide: ModifiedTimeSide? = null,
    val entries: List<ConflictedEntryItem> = emptyList(),
    /** `ISSUE-P2-660`：分组冲突（云端同步面必须与条目一并呈现，不得静默按 LWW 定案） */
    val groups: List<ConflictedGroupItem> = emptyList(),
    /** `ISSUE-P2-659`：库级 Meta 冲突；null 表示无此类冲突 */
    val meta: ConflictedMetaItem? = null,
    val isResolving: Boolean = false,
    val userMessage: UiMessage? = null
)
