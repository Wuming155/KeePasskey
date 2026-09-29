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

/**
 * ISSUE-P2-378：库文件被外部修改后的三选对话框。
 *
 * 口径（批次留痕）：
 * - 漂移即 fail-closed 中止覆盖，不得静默整树覆盖；
 * - 重载：丢弃内存未落盘改动，重新打开磁盘版本；
 * - 合并：走既有 KdbxMerger 三方合并后保存；
 * - 放弃：本次不写盘，内存改动保留（调用方已中止 save）。
 */
@Composable
fun ExternalModificationDialog(
    onChoice: (ExternalModificationChoice) -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    AlertDialog(
        onDismissRequest = { /* 漂移提示不允许点外部关闭——必须显式三选 */ },
        modifier = modifier,
        title = { Text(stringResource(R.string.ext_mod_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.ext_mod_dialog_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.ext_mod_merge_hint_fallback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onChoice(ExternalModificationChoice.MERGE_AND_SAVE) }) {
                Text(stringResource(R.string.ext_mod_choice_merge))
            }
        },
        dismissButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onChoice(ExternalModificationChoice.RELOAD_FROM_DISK) }) {
                    Text(stringResource(R.string.ext_mod_choice_reload))
                }
                OutlinedButton(onClick = { onChoice(ExternalModificationChoice.ABANDON_SAVE) }) {
                    Text(stringResource(R.string.ext_mod_choice_abandon))
                }
            }
        }
    )
}
