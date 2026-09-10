package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.screens.settings.ChildDatabaseMountUiState
import com.keepasskey.app.ui.screens.settings.ChildDatabaseStatus
import com.keepasskey.app.ui.screens.settings.ChildDatabaseUiState
import com.keepasskey.app.ui.screens.settings.childDatabaseSourceDisplayName

/**
 * 子库挂载 / 解锁相关对话框（ISSUE-P3-29：自 `DatabaseSettingsDialogs.kt` 拆出，纯结构性拆分）。
 */

// 对话框 5：子数据库挂载（ISSUE-P3-20：核心层 ChildDatabaseSessionManager 已落地，本对话框是真实入口）
//
// 诚实性约定：
// 1. 原 `dbset_child_db_reserved_note`（「子库挂载预留，暂未生效：选择文件后不会挂载任何子库」）
//    渲染已**整体删除**——挂载现已真实生效，保留该提示会反向失真；
// 2. 「已挂载」与「已解锁」严格区分：状态文案来自 ChildDatabaseMountState 的真实流转，
//    Opening 以不确定进度指示器呈现，既不伪造成「未解锁」，也不提前报「已解锁」；
// 3. 凭据仅存内存、锁库即清零是**有意的安全语义**（由 `dbset_child_db_credential_dialog_desc` 如实说明），
//    不表现为缺陷，也不提供「记住子库密码」一类弱化安全的便利。
@Composable
internal fun ChildDatabaseDialog(
    state: ChildDatabaseUiState,
    selectedSourceUri: String?,
    selectedKeyFileUri: String?,
    onPickSource: () -> Unit,
    onPickKeyFile: () -> Unit,
    onMount: (alias: String, sourceUri: String, passwordChars: CharArray, keyFileUri: String?) -> Unit,
    onUnlockRequest: (mountId: String) -> Unit,
    onUnmount: (mountId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var alias by remember { mutableStateOf("") }
    val password = remember { mutableStateOf(CharArray(0)) }
    // 提交后递增：通知 SecurePasswordField 擦除显示态与桥接数组（与解锁页 wipeToken 同一语义）
    var credentialEpoch by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { password.value.fill('0') }
    }

    // 子库可由「主密码」或「密钥文件」单独承载，故二者其一即可提交（与核心层复合密钥语义一致）
    val canMount = state.available && alias.isNotBlank() && selectedSourceUri != null &&
        (password.value.isNotEmpty() || selectedKeyFileUri != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbset_child_db_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.dbset_child_db_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.dbset_child_db_credential_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.dbset_child_db_alias_label)) },
                    singleLine = true,
                    enabled = state.available,
                    modifier = Modifier.fillMaxWidth()
                )

                SecurePasswordField(
                    label = stringResource(R.string.dbset_child_db_password_label),
                    onPasswordChanged = { bridge ->
                        password.value.fill('0')
                        password.value = bridge.copyOf()
                    },
                    wipeToken = credentialEpoch,
                    enabled = state.available,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = onPickSource,
                    enabled = state.available,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dbset_child_db_select_file))
                }
                // 已选来源回显：仅文件名（非敏感元数据），避免用户对「选了哪个文件」无据可依
                selectedSourceUri?.let { source ->
                    Text(
                        text = childDatabaseSourceDisplayName(source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = onPickKeyFile,
                    enabled = state.available,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = selectedKeyFileUri?.let {
                            stringResource(
                                R.string.unlock_keyfile_selected,
                                childDatabaseSourceDisplayName(it)
                            )
                        } ?: stringResource(R.string.unlock_keyfile_none)
                    )
                }

                Button(
                    onClick = {
                        val source = selectedSourceUri ?: return@Button
                        // 借用语义：控制器在回调内同步复制，返回后本层立即擦除并清空输入框
                        onMount(alias.trim(), source, password.value, selectedKeyFileUri)
                        password.value.fill('0')
                        password.value = CharArray(0)
                        credentialEpoch++
                    },
                    enabled = canMount,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.dbset_child_db_btn_mount))
                }

                // 控制器缺失（仅单测/异常装配）时上方全部控件已被禁用，即事实本身；
                // 此处不再补文案（无对应资源），生产装配下 available 恒为 true。
                if (state.available) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    if (state.mounts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.dbset_child_db_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.mounts.forEach { mount ->
                            ChildDatabaseMountRow(
                                mount = mount,
                                onUnlock = { onUnlockRequest(mount.mountId) },
                                onUnmount = { onUnmount(mount.mountId) }
                            )
                        }
                        Text(
                            text = stringResource(R.string.dbset_child_db_unmount_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    state.feedback?.let { feedback ->
                        Text(
                            text = feedback.message.resolveText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (feedback.isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dbset_btn_done))
            }
        }
    )
}

/** 单条已挂载子库：别名 + 真实状态（或「正在打开」进度）+ 解锁/卸载入口 */
@Composable
private fun ChildDatabaseMountRow(
    mount: ChildDatabaseMountUiState,
    onUnlock: () -> Unit,
    onUnmount: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mount.alias,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            when (val status = mount.status) {
                is ChildDatabaseStatus.Text -> Text(
                    text = status.message.resolveText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ChildDatabaseStatus.Opening -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        // 「已挂载」不等于「已解锁」：未解锁状态下必须给出真实的重试入口
        if (mount.canRetryWithCredentials) {
            TextButton(onClick = onUnlock) {
                Text(stringResource(R.string.dbset_child_db_btn_unlock))
            }
        }
        TextButton(onClick = onUnmount) {
            Text(stringResource(R.string.dbset_child_db_btn_unmount))
        }
    }
}

// 对话框 5b：子库凭据补录（重新解锁已挂载子库）
//
// 触发场景全部是真实语义：进程重启 / 根库锁定后独立凭据通道已被清零、凭据被拒后重试、
// 来源恢复后重试。凭据再次提交后仍只驻留内存，绝无「记住子库密码」的旁路。
@Composable
internal fun ChildDatabaseCredentialDialog(
    alias: String,
    selectedKeyFileUri: String?,
    onPickKeyFile: () -> Unit,
    onConfirm: (passwordChars: CharArray, keyFileUri: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val password = remember { mutableStateOf(CharArray(0)) }
    var credentialEpoch by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { password.value.fill('0') }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbset_child_db_btn_unlock)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 目标子库别名（非敏感展示字段），避免「解锁哪一个」无据可依
                Text(
                    text = alias,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.dbset_child_db_credential_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SecurePasswordField(
                    label = stringResource(R.string.dbset_child_db_password_label),
                    onPasswordChanged = { bridge ->
                        password.value.fill('0')
                        password.value = bridge.copyOf()
                    },
                    wipeToken = credentialEpoch,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = onPickKeyFile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = selectedKeyFileUri?.let {
                            stringResource(
                                R.string.unlock_keyfile_selected,
                                childDatabaseSourceDisplayName(it)
                            )
                        } ?: stringResource(R.string.unlock_keyfile_none)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val submitted = password.value
                    onConfirm(submitted, selectedKeyFileUri)
                    // 提交后立即擦除本层副本与输入框显示态（失败重试须重新输入，与解锁页一致）
                    submitted.fill('0')
                    password.value = CharArray(0)
                    credentialEpoch++
                },
                enabled = password.value.isNotEmpty() || selectedKeyFileUri != null
            ) {
                Text(stringResource(R.string.dbset_child_db_btn_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
