package com.keepasskey.app.ui.screens.database

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 新建库向导中密钥文件来源的选择态（ISSUE-P3-21：替换原裸字符串 `"GENERATE"` / `"SELECT_EXISTING"`）。
 */
private enum class KeyFileSourceChoice {
    /** 生成全新密钥文件（由 database 模块唯一生成器产出 KeePass 2.x XML v2.0） */
    GENERATE,

    /** 使用用户从设备选取的既有密钥文件 */
    SELECT_EXISTING
}

/**
 * 生成型密钥文件的一次性保存提示（ISSUE-P3-21 验收 2）。
 *
 * 该密钥文件是复合密钥的第二因子：**不保存即永久无法解锁**（会话锁定后内存副本立即清零，
 * 且该文件不会被再次生成）。因此：
 * - 主按钮直达 SAF 另存为（写盘复用既有导出通道 `exportKeyFileBytes`）；
 * - 次按钮文案如实写出后果，不提供「假装已保存」的第三条路径；
 * - 点击弹窗外部不关闭（`onDismissRequest` 不做任何事），杜绝误触导致第二因子静默丢失。
 */
@Composable
internal fun KeyFileOneTimeSaveDialog(
    suggestedFileName: String,
    onSaveClick: () -> Unit,
    onSkipClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            // 必须显式选择：误触外部若静默关闭，用户将永久失去该密码库的第二因子
        },
        title = {
            Text(
                text = stringResource(R.string.db_picker_keyfile_backup_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.db_picker_keyfile_backup_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = suggestedFileName,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(onClick = onSaveClick, shape = CapsuleShape) {
                Text(stringResource(R.string.db_picker_keyfile_backup_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkipClick) {
                Text(stringResource(R.string.db_picker_keyfile_backup_skip))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

/**
 * 新建密码库向导（ISSUE-P3-31 批次 B 自 `DatabasePickerScreen.kt` 拆出，纯结构性改动）。
 *
 * 主密码全程以 [CharArray] 承载（[SecurePasswordField] 桥接），不进入 String / UiState / StateFlow；
 * 弹窗离场（确认 / 取消 / 进程回收）经 [DisposableEffect] 擦除组件内持有的全部密码副本。
 */
@Composable
internal fun CreateVaultWizardDialog(
    onDismiss: () -> Unit,
    // 形参顺序与 DatabasePickerViewModel.createDatabase 严格一致，便于直接方法引用接线
    onConfirm: (
        name: String,
        pwd: CharArray,
        keyFile: Boolean,
        preset: String,
        keyFileSourceUri: String?
    ) -> Unit
) {
    val context = LocalContext.current
    var vaultName by remember { mutableStateOf("passwords.kdbx") }
    // H2 整改：主密码以 CharArray 承载（SecurePasswordField 桥接），不进入 String / UiState / StateFlow
    var passwordChars by remember { mutableStateOf(CharArray(0)) }
    var confirmChars by remember { mutableStateOf(CharArray(0)) }
    var passwordVisible by remember { mutableStateOf(false) }
    var useKeyFile by remember { mutableStateOf(false) }
    var keyFileChoice by remember { mutableStateOf(KeyFileSourceChoice.GENERATE) }
    var selectedKeyFilePath by remember { mutableStateOf("") }
    var selectedKeyFileName by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf("ChaCha20 + Argon2id") }
    val presets = listOf("ChaCha20 + Argon2id", "AES-256 + Argon2id", "Twofish + AES-KDF")

    val keyPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = queryDocumentDisplayName(context, uri)
            selectedKeyFileName = displayName
            selectedKeyFilePath = uri.toString()
        }
    }

    val isKeyFileValid = !useKeyFile || keyFileChoice == KeyFileSourceChoice.GENERATE ||
        selectedKeyFilePath.isNotBlank()
    val isFormValid = vaultName.isNotBlank() && passwordChars.isNotEmpty() && passwordChars.contentEquals(confirmChars) && isKeyFileValid

    // 弹窗离场（确认 / 取消 / 进程回收）时擦除组件内持有的全部密码副本
    DisposableEffect(Unit) {
        onDispose {
            passwordChars.fill('0')
            confirmChars.fill('0')
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.db_picker_create_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = vaultName,
                    onValueChange = { vaultName = it },
                    label = { Text(stringResource(R.string.db_picker_vault_name)) },
                    placeholder = { Text(stringResource(R.string.db_picker_vault_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                SecurePasswordField(
                    label = stringResource(R.string.db_picker_new_pwd),
                    onPasswordChanged = { chars ->
                        passwordChars.fill('0')
                        passwordChars = chars.copyOf()
                    },
                    isPasswordVisible = passwordVisible,
                    onToggleVisibility = { passwordVisible = !passwordVisible },
                    modifier = Modifier.fillMaxWidth()
                )

                SecurePasswordField(
                    label = stringResource(R.string.db_picker_confirm_pwd),
                    onPasswordChanged = { chars ->
                        confirmChars.fill('0')
                        confirmChars = chars.copyOf()
                    },
                    isError = confirmChars.isNotEmpty() && !confirmChars.contentEquals(passwordChars),
                    supportingText = {
                        if (confirmChars.isNotEmpty() && !confirmChars.contentEquals(passwordChars)) {
                            Text(stringResource(R.string.db_picker_pwd_mismatch), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    isPasswordVisible = passwordVisible,
                    onToggleVisibility = { passwordVisible = !passwordVisible },
                    modifier = Modifier.fillMaxWidth()
                )

                // 文件密钥选择区域 (可选项：可生成新密钥，或选择已有密钥文件)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { useKeyFile = !useKeyFile }
                    ) {
                        Checkbox(checked = useKeyFile, onCheckedChange = { useKeyFile = it })
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.picker_keyfile_toggle),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = stringResource(R.string.picker_keyfile_toggle_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (useKeyFile) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = keyFileChoice == KeyFileSourceChoice.GENERATE,
                                onClick = { keyFileChoice = KeyFileSourceChoice.GENERATE },
                                label = { Text(stringResource(R.string.picker_keyfile_generate), fontSize = 11.sp) },
                                shape = CapsuleShape
                            )
                            FilterChip(
                                selected = keyFileChoice == KeyFileSourceChoice.SELECT_EXISTING,
                                onClick = { keyFileChoice = KeyFileSourceChoice.SELECT_EXISTING },
                                label = { Text(stringResource(R.string.picker_keyfile_select_existing), fontSize = 11.sp) },
                                shape = CapsuleShape
                            )
                        }

                        if (keyFileChoice == KeyFileSourceChoice.GENERATE) {
                            // ISSUE-P3-21：原文案声称「自动保存至安全存储」，实际并无自动保存——
                            // 现改为如实描述「生成后强制一次性交付」
                            Text(
                                text = stringResource(R.string.db_picker_keyfile_generate_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { keyPickerLauncher.launch(arrayOf("*/*")) },
                                    shape = CapsuleShape,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.picker_keyfile_pick), fontSize = 12.sp)
                                }

                                if (selectedKeyFileName.isNotBlank()) {
                                    Text(
                                        text = stringResource(R.string.picker_file_selected, selectedKeyFileName),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                OutlinedTextField(
                                    value = selectedKeyFilePath,
                                    onValueChange = { selectedKeyFilePath = it },
                                    label = { Text(stringResource(R.string.picker_keyfile_path_label)) },
                                    placeholder = { Text("content://... 或 /path/to/keyfile.key") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.db_picker_encryption_preset),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = selectedPreset == preset,
                            onClick = { selectedPreset = preset },
                            label = { Text(preset.split(" ")[0], fontSize = 11.sp) },
                            shape = CapsuleShape
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                // H2 整改：直接移交组件持有的 CharArray（ViewModel 复制私有副本并自行擦除）
                // ISSUE-P3-21：SELECT_EXISTING 时上行选中的密钥文件 Uri，其字节真实参与复合密钥
                onClick = {
                    onConfirm(
                        vaultName,
                        passwordChars,
                        useKeyFile,
                        selectedPreset,
                        if (keyFileChoice == KeyFileSourceChoice.SELECT_EXISTING) selectedKeyFilePath else null
                    )
                },
                enabled = isFormValid,
                shape = CapsuleShape
            ) {
                Text(stringResource(R.string.btn_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
