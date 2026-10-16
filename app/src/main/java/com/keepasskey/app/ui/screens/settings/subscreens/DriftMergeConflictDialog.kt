package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileDriftMergeConflict

/**
 * `ISSUE-P1-631`：漂移合并的**冲突裁决**对话框。
 *
 * 出现条件：用户选择「合并后保存」后，合并以真实共同基线执行，发现条目在**本地与磁盘
 * 上都被修改**（同字段分叉，含自定义字段 / 图标 / 到期 / 颜色等语义键）。此时不静默取
 * 一侧、也不落盘——由用户在三选之间裁决：
 * - 以本地版本为准：冲突条目保留内存树版本；
 * - 以磁盘版本为准：冲突条目采用磁盘版本；
 * - 双方保留：内存树版本与「磁盘冲突副本」（新 UUID）同时保留；
 * - 稍后处理：关闭本提示并回到漂移三选（本地改动与磁盘版本均原样保留）。
 *
 * 裁决入口与三选共用 [ExternalModificationChoice]（`MERGE_KEEP_*`），由调用方统一提交。
 *
 * `ISSUE-P1-631`：[enabled] = false 时全部按钮不可用——挂点在处置**在途期间**置位，
 * 防「连点 / 组合重建后重复提交」对同一冲突触发多次提交。
 */
@Composable
fun DriftMergeConflictDialog(
    conflict: VaultFileDriftMergeConflict,
    onChoice: (ExternalModificationChoice) -> Unit,
    onCancel: () -> Unit,
    enabled: Boolean = true,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = modifier,
        title = { Text(stringResource(R.string.ext_mod_merge_conflict_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.ext_mod_merge_conflict_body, conflict.conflictCount),
                    style = MaterialTheme.typography.bodyMedium
                )
                conflict.entryTitles.forEach { title ->
                    Text(
                        text = "• " + title.ifBlank {
                            stringResource(R.string.ext_mod_merge_conflict_untitled)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { onChoice(ExternalModificationChoice.MERGE_KEEP_LOCAL) },
                    enabled = enabled
                ) {
                    Text(stringResource(R.string.ext_mod_merge_conflict_keep_local))
                }
                OutlinedButton(
                    onClick = { onChoice(ExternalModificationChoice.MERGE_KEEP_DISK) },
                    enabled = enabled
                ) {
                    Text(stringResource(R.string.ext_mod_merge_conflict_keep_disk))
                }
                OutlinedButton(
                    onClick = { onChoice(ExternalModificationChoice.MERGE_KEEP_BOTH) },
                    enabled = enabled
                ) {
                    Text(stringResource(R.string.ext_mod_merge_conflict_keep_both))
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel, enabled = enabled) {
                Text(stringResource(R.string.ext_mod_merge_conflict_cancel))
            }
        }
    )
}
