package com.keepasskey.app.ui.screens.recovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.security.VaultRecoveryCoordinator
import com.keepasskey.app.ui.components.SecurePasswordField
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ISSUE-P1-592：恢复副本处置对话框宿主（冷启动 / 常驻挂点）。
 *
 * 挂点只呈现：待处置副本清单 → 选中后用主密码解锁比较差异 → 恢复 / 另存 / 暂不处理 / 删除。
 * 主密码以最短生命周期持有于本组件内（输入变更即复制，离开组合即清零），不进入 ViewModel 状态。
 */
@Composable
fun VaultRecoveryDialogHost(viewModel: RecoveryViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val preservationAlert by viewModel.preservationAlert.collectAsStateWithLifecycle()

    if (preservationAlert) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPreservationAlert,
            title = { Text(stringResource(R.string.recovery_alert_title)) },
            text = { Text(stringResource(R.string.recovery_alert_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPreservationAlert) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
        return
    }

    if (state.awaitingOverwriteConfirm) {
        val target = state.pending.firstOrNull { RecoveryViewModel.keyOf(it) == state.selectedKey }
        AlertDialog(
            onDismissRequest = viewModel::cancelOverwriteConfirm,
            title = { Text(stringResource(R.string.recovery_confirm_overwrite_title)) },
            text = { Text(stringResource(R.string.recovery_confirm_overwrite_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelOverwriteConfirm()
                    target?.let { viewModel.restore(it, forceOverwrite = true) }
                }) { Text(stringResource(R.string.recovery_confirm_overwrite_action)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelOverwriteConfirm) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
        return
    }

    if (state.pending.isEmpty()) return

    RecoveryDialog(viewModel = viewModel, state = state)
}

@Composable
private fun RecoveryDialog(viewModel: RecoveryViewModel, state: RecoveryViewModel.UiState) {
    val selected = state.pending.firstOrNull { RecoveryViewModel.keyOf(it) == state.selectedKey }

    // 主密码：最短生命周期持有——输入变更即复制，离开组合即清零；绝不上行到 ViewModel 状态。
    val passwordHolder = remember { CharArrayHolder() }
    DisposableEffect(Unit) { onDispose { passwordHolder.clear() } }

    AlertDialog(
        onDismissRequest = viewModel::keep,
        title = { Text(stringResource(R.string.recovery_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.recovery_dialog_body), style = MaterialTheme.typography.bodyMedium)
                state.pending.forEach { pending ->
                    val key = RecoveryViewModel.keyOf(pending)
                    Text(
                        text = stringResource(
                            R.string.recovery_entry_summary,
                            pending.entry.vaultName,
                            formatTime(pending.entry.createdAtEpochMs)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (key == state.selectedKey) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.select(if (key == state.selectedKey) null else key) }
                    )
                }
                if (selected != null) {
                    SecurePasswordField(
                        label = stringResource(R.string.unlock_master_password),
                        onPasswordChanged = { passwordHolder.set(it) },
                        enabled = !state.busy
                    )
                    state.previewText?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(
                        onClick = { viewModel.preview(selected, passwordHolder.copy()) },
                        enabled = !state.busy
                    ) { Text(stringResource(R.string.recovery_preview_action)) }
                }
                state.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (selected != null) {
                Button(
                    onClick = { viewModel.restore(selected, forceOverwrite = false) },
                    enabled = !state.busy && state.preview != null
                ) { Text(stringResource(R.string.recovery_restore_action)) }
            } else {
                TextButton(onClick = viewModel::keep) { Text(stringResource(R.string.btn_close)) }
            }
        },
        dismissButton = {
            if (selected != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.saveAs(selected) },
                        enabled = !state.busy
                    ) { Text(stringResource(R.string.recovery_save_as_action)) }
                    OutlinedButton(
                        onClick = { viewModel.discard(selected) },
                        enabled = !state.busy
                    ) { Text(stringResource(R.string.recovery_discard_action)) }
                    TextButton(onClick = viewModel::keep) { Text(stringResource(R.string.recovery_keep_action)) }
                }
            }
        }
    )
}

/** 主密码短生命周期持有器：复制一份独立数组并负责清零。 */
private class CharArrayHolder {
    private var chars: CharArray = CharArray(0)

    fun set(source: CharArray) {
        chars.fill('0')
        chars = source.copyOf()
    }

    fun copy(): CharArray = chars.copyOf()

    fun clear() {
        chars.fill('0')
        chars = CharArray(0)
    }
}

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)
