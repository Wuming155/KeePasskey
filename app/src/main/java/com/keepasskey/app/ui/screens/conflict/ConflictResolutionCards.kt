package com.keepasskey.app.ui.screens.conflict

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.theme.passwordFieldStyle

/**
 * 冲突页的卡片组件（自 `ConflictResolutionScreen.kt` 下沉，控行数门禁）。
 *
 * 三类卡片对应三类冲突对象：条目（可字段级 / 整条 / 双方保留）、分组（整组 / 双方保留）、
 * 库级 Meta（标量字段级，无双方保留）。页面只负责编排，差异键 → 界面行的映射在
 * `ConflictResolutionDiffMapper`。
 */

@Composable
internal fun ConflictedEntryCard(
    entry: ConflictedEntryItem,
    onFieldChoiceChange: (String, FieldChoice) -> Unit,
    onModeChange: (EntryResolutionMode) -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = entry.groupPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ISSUE-P2-281 AC②／AC③：条目级裁决方式（整条取本地 / 云端兜底与双方保留入口）
            EntryResolutionModeSelector(
                mode = entry.mode,
                onModeChange = onModeChange
            )

            if (entry.mode == EntryResolutionMode.FIELD_BY_FIELD) {
                entry.fields.forEach { field ->
                    FieldDiffRow(
                        field = field,
                        onChoiceSelected = { choice -> onFieldChoiceChange(field.fieldKey, choice) }
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.conflict_entry_mode_whole_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * `ISSUE-P2-659`：库级 Meta 冲突卡片。
 *
 * 复用条目侧的 [FieldDiffRow]（本地 / 云端单选取值）：标量字段没有「双方保留」，
 * 也不存在「云端删除该字段」，因此只呈现两档。
 */
@Composable
internal fun ConflictedMetaCard(
    meta: ConflictedMetaItem,
    onFieldChoiceChange: (String, FieldChoice) -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.conflict_meta_card_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            meta.fields.forEach { field ->
                FieldDiffRow(
                    field = field,
                    onChoiceSelected = { choice -> onFieldChoiceChange(field.fieldKey, choice) }
                )
            }
        }
    }
}

/**
 * `ISSUE-P2-660`：分组冲突卡片——整组裁决（不做字段级混选）。
 *
 * 差异字段只作只读对照：分组的「组名来源」与「子树归属」若分开选，用户很难预期最终结构，
 * 因此裁决粒度固定为整组。[GroupResolutionMode.DUPLICATE_BOTH] 的复制语义由落树侧承担
 * （复制整棵子树、全部新 UUID）。
 */
@Composable
internal fun ConflictedGroupCard(
    group: ConflictedGroupItem,
    onModeChange: (GroupResolutionMode) -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(
                    text = group.name.ifBlank { stringResource(R.string.conflict_value_none) },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.conflict_group_badge),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            GroupResolutionModeSelector(mode = group.mode, onModeChange = onModeChange)

            Text(
                text = stringResource(R.string.conflict_group_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            group.fields.forEach { field -> GroupFieldDiffRow(field) }
        }
    }
}

/** 分组级裁决方式选择行（整组本地 / 整组云端 / 双方保留）。 */
@Composable
private fun GroupResolutionModeSelector(
    mode: GroupResolutionMode,
    onModeChange: (GroupResolutionMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GroupResolutionMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { onModeChange(option) },
                label = {
                    Text(text = stringResource(groupModeLabelRes(option)), fontSize = 11.sp)
                }
            )
        }
    }
}

private fun groupModeLabelRes(mode: GroupResolutionMode): Int = when (mode) {
    GroupResolutionMode.KEEP_LOCAL -> R.string.conflict_group_mode_keep_local
    GroupResolutionMode.KEEP_REMOTE -> R.string.conflict_group_mode_keep_remote
    GroupResolutionMode.DUPLICATE_BOTH -> R.string.conflict_group_mode_duplicate_both
}

/** 分组差异的只读对照行：字段名 + 本地值 + 云端值（分组元数据非敏感，不做掩码）。 */
@Composable
private fun GroupFieldDiffRow(field: ConflictedField) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = field.fieldName,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "${stringResource(R.string.conflict_local_plain)}：${field.localValue}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "${stringResource(R.string.conflict_remote_plain)}：${field.remoteValue}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** ISSUE-P2-281：条目级裁决方式选择行（逐字段 / 整条本地 / 整条云端 / 双方保留）。 */@Composable
private fun EntryResolutionModeSelector(
    mode: EntryResolutionMode,
    onModeChange: (EntryResolutionMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        EntryResolutionMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { onModeChange(option) },
                label = {
                    Text(
                        text = stringResource(entryModeLabelRes(option)),
                        fontSize = 11.sp
                    )
                }
            )
        }
    }
}

private fun entryModeLabelRes(mode: EntryResolutionMode): Int = when (mode) {
    EntryResolutionMode.FIELD_BY_FIELD -> R.string.conflict_entry_mode_field
    EntryResolutionMode.KEEP_LOCAL -> R.string.conflict_entry_keep_local
    EntryResolutionMode.KEEP_REMOTE -> R.string.conflict_entry_keep_remote
    EntryResolutionMode.DUPLICATE_BOTH -> R.string.conflict_entry_duplicate_both
}

@Composable
private fun FieldDiffRow(
    field: ConflictedField,
    onChoiceSelected: (FieldChoice) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = field.fieldName,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )

        // 本地选项
        ConflictFieldChoiceRow(
            choice = FieldChoice.LOCAL,
            labelRes = if (field.localMissing) R.string.conflict_local_field_missing else R.string.conflict_local_version,
            labelColor = MaterialTheme.colorScheme.primary,
            value = field.localValue,
            selectedChoice = field.selectedChoice,
            isSensitive = field.isSensitive && !field.localMissing,
            onChoiceSelected = onChoiceSelected
        )

        if (field.remoteMissing) {
            ConflictFieldChoiceRow(
                choice = FieldChoice.DELETE_REMOTE,
                labelRes = R.string.conflict_remote_delete_action,
                labelColor = MaterialTheme.colorScheme.secondary,
                value = field.remoteValue,
                selectedChoice = field.selectedChoice,
                isSensitive = false,
                onChoiceSelected = onChoiceSelected
            )
        } else {
            ConflictFieldChoiceRow(
                choice = FieldChoice.REMOTE,
                labelRes = R.string.conflict_remote_version,
                labelColor = MaterialTheme.colorScheme.secondary,
                value = field.remoteValue,
                selectedChoice = field.selectedChoice,
                isSensitive = field.isSensitive,
                onChoiceSelected = onChoiceSelected
            )
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "冲突条目对比卡片 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "冲突条目对比卡片 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ConflictedEntryCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        ConflictedEntryCard(
            entry = com.keepasskey.app.ui.screens.conflict.ConflictedEntryItem(
                id = "preview-conflict-1",
                title = "预览冲突条目",
                groupPath = "预览根目录 / 网站登录",
                fields = listOf(
                    com.keepasskey.app.ui.screens.conflict.ConflictedField(
                        fieldKey = "Title",
                        fieldName = "标题",
                        localValue = "预览标题（本地）",
                        remoteValue = "预览标题（云端）"
                    ),
                    com.keepasskey.app.ui.screens.conflict.ConflictedField(
                        fieldKey = "Password",
                        fieldName = "密码",
                        localValue = "预览掩码值（本地）",
                        remoteValue = "预览掩码值（云端）",
                        selectedChoice = com.keepasskey.app.ui.screens.conflict.FieldChoice.REMOTE,
                        isSensitive = true
                    )
                )
            ),
            onFieldChoiceChange = { _, _ -> },
            onModeChange = {}
        )
    }
}
