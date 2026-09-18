package com.keepasskey.app.ui.screens.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.IconPickerDialog
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.theme.CapsuleShape
/**
 * 群组生命周期对话框集（§161 自 `VaultListDialogs.kt` 纯结构性搬家，组件体逐字未改）。
 *
 * 收录新建 / 重命名 / 换图标 / 删除群组与清空回收站五个对话框——它们共同构成「分组树的结构变更入口」，
 * 参数一律是「目标分组 + `onDismiss` + 确认回调」形态、不读列表页状态，故可独立成文件。
 * 母文件当时 491 行仍高于 `ISSUE-P3-188` 的 400 行阈值。
 */

/**
 * 新建群组对话框
 */
@Composable
internal fun CreateGroupDialog(
    parentGroupName: String?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, icon: String) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var selectedIcon by remember { mutableStateOf("folder") }
    var showIconPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.vault_new_folder),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = if (parentGroupName != null) stringResource(R.string.vault_create_location_with, parentGroupName)
                    else stringResource(R.string.vault_create_location_root),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.vault_folder_name)) },
                    placeholder = { Text(stringResource(R.string.vault_folder_name_hint)) },
                    leadingIcon = {
                        IconButton(onClick = { showIconPicker = true }) {
                            Icon(
                                imageVector = getVaultIcon(selectedIcon),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (groupName.isNotBlank()) {
                        onConfirm(groupName.trim(), selectedIcon)
                    }
                },
                enabled = groupName.isNotBlank(),
                shape = CapsuleShape
            ) { Text(stringResource(R.string.btn_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )

    if (showIconPicker) {
        IconPickerDialog(
            selectedIconName = selectedIcon,
            onSelectIcon = {
                selectedIcon = it
                showIconPicker = false
            },
            onDismiss = { showIconPicker = false }
        )
    }
}

/**
 * 重命名文件夹对话框
 */
@Composable
internal fun VaultRenameGroupDialog(
    group: VaultGroup,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf(group.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_folder_rename)) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.vault_folder_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newName.isNotBlank()) {
                        onConfirm(newName)
                    }
                },
                shape = CapsuleShape
            ) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

/**
 * 更换文件夹图标对话框
 */
@Composable
internal fun VaultChangeGroupIconDialog(
    group: VaultGroup,
    onSelectIcon: (String) -> Unit,
    onDismiss: () -> Unit
) {
    IconPickerDialog(
        selectedIconName = group.iconName,
        onSelectIcon = onSelectIcon,
        onDismiss = onDismiss
    )
}

/**
 * 删除文件夹确认对话框
 */
@Composable
internal fun VaultDeleteGroupDialog(
    group: VaultGroup,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.vault_folder_delete)) },
        text = { Text(stringResource(R.string.vault_folder_delete_desc)) },
        confirmButton = {
            Button(
                onClick = {
                    // 危险操作确认：Reject 触感强化「不可逆」心智
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text(stringResource(R.string.btn_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

/**
 * 清空回收站确认对话框
 */
@Composable
internal fun VaultEmptyRecycleBinDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.vault_empty_recycle_bin)) },
        text = { Text(stringResource(R.string.vault_empty_recycle_bin_confirm)) },
        confirmButton = {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text(stringResource(R.string.btn_empty)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}
