package com.keepasskey.app.ui.screens.database

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.CreateVaultPreset
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.theme.CapsuleShape

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
                // 对话框由 Compose 创建**独立窗口**，Activity 的 FLAG_SECURE 不会传播过来
                // （官方："You must set FLAG_SECURE explicitly for every window created by the
                // activity, including dialogs."）。本窗展示一次性密钥文件保存提示，属敏感面。
                com.keepasskey.app.security.SecureDialogWindowEffect()
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
        }
    )
}

/**
 * 新建密码库向导（ISSUE-P3-31 批次 B 自 `DatabasePickerScreen.kt` 拆出，纯结构性改动）。
 *
 * ISSUE-P3-188 §179：密钥文件区、预设芯片与确认按钮下沉至同包 `CreateVaultWizardDialogSections.kt`；
 * **主密码 / 确认密码的 `CharArray` 管线与离场擦除的 `DisposableEffect` 刻意留在本体**（见该文件 KDoc）。
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
        preset: CreateVaultPreset,
        keyFileSourceUri: String?,
        targetUri: String?
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
    // ISSUE-P2-85：预设改为类型化枚举——芯片与落盘共用 `CreateVaultPreset` 单一真相源，
    // 消除「裸字符串标签 + 落盘侧只按 contains("AES-KDF") 反推」导致的算法静默丢失。
    var selectedPreset by remember { mutableStateOf(CreateVaultPreset.DEFAULT) }
    // ISSUE-P2-229：新建库的落地位置此前**无任何入口**——一律静默写入应用私有目录。
    // 现由用户二选一；选「自选位置」时必须已在系统面板挑定文档，否则确认按钮保持禁用
    // （绝不静默回退到内部存储，那会让用户以为库在自己选的位置）。
    var storageLocation by remember { mutableStateOf(VaultStorageLocation.INTERNAL) }
    var selectedVaultUri by remember { mutableStateOf("") }
    var selectedVaultFileName by remember { mutableStateOf("") }

    val keyPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = queryDocumentDisplayName(context, uri)
            selectedKeyFileName = displayName
            selectedKeyFilePath = uri.toString()
        }
    }

    // ISSUE-P2-229：ACTION_CREATE_DOCUMENT 通道（与密钥文件另存为同一 contract 口径）；
    // 用户在系统面板取消即回落到「应用私有目录」并**即时反映在单选项上**（不留隐式选择）
    val vaultCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            selectedVaultFileName = queryDocumentDisplayName(context, uri)
            selectedVaultUri = uri.toString()
        } else {
            storageLocation = VaultStorageLocation.INTERNAL
        }
    }

    val isLocationValid = storageLocation == VaultStorageLocation.INTERNAL || selectedVaultUri.isNotBlank()
    val isKeyFileValid = !useKeyFile || keyFileChoice == KeyFileSourceChoice.GENERATE ||
        selectedKeyFilePath.isNotBlank()
    val isFormValid = vaultName.isNotBlank() && passwordChars.isNotEmpty() &&
        passwordChars.contentEquals(confirmChars) && isKeyFileValid && isLocationValid

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
                // 本窗内含**主密码 + 确认主密码**两个 SecurePasswordField：对话框是独立窗口，
                // 必须在本窗内显式施加 FLAG_SECURE（同 SecureDialog KDoc 的官方依据）
                com.keepasskey.app.security.SecureDialogWindowEffect()
                OutlinedTextField(
                    value = vaultName,
                    onValueChange = { vaultName = it },
                    label = { Text(stringResource(R.string.db_picker_vault_name)) },
                    placeholder = { Text(stringResource(R.string.db_picker_vault_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // ISSUE-P2-229：存储位置二选一（内部存储 / 经系统文件选择器自选）
                VaultStorageLocationSection(
                    location = storageLocation,
                    pickedFileName = selectedVaultFileName,
                    onSelectInternal = {
                        storageLocation = VaultStorageLocation.INTERNAL
                        selectedVaultUri = ""
                        selectedVaultFileName = ""
                    },
                    onSelectExternal = {
                        storageLocation = VaultStorageLocation.EXTERNAL
                        vaultCreateLauncher.launch(
                            if (vaultName.endsWith(".kdbx", ignoreCase = true)) vaultName else "$vaultName.kdbx"
                        )
                    }
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
                    KeyFileToggleRow(
                        useKeyFile = useKeyFile,
                        onUseKeyFileChange = { useKeyFile = it }
                    )

                    if (useKeyFile) {
                        KeyFileSourceSection(
                            keyFileChoice = keyFileChoice,
                            onKeyFileChoiceChange = { keyFileChoice = it },
                            selectedKeyFileName = selectedKeyFileName,
                            selectedKeyFilePath = selectedKeyFilePath,
                            onSelectedKeyFilePathChange = { selectedKeyFilePath = it },
                            onBrowse = { keyPickerLauncher.launch(arrayOf("*/*")) }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.db_picker_encryption_preset),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                CreateVaultPresetChips(
                    selected = selectedPreset,
                    onSelect = { selectedPreset = it }
                )
            }
        },
        confirmButton = {
            CreateVaultConfirmButton(
                enabled = isFormValid,
                onCreate = {
                    // H2 整改：直接移交组件持有的 CharArray（ViewModel 复制私有副本并自行擦除）
                    // ISSUE-P3-21：SELECT_EXISTING 时上行选中的密钥文件 Uri，其字节真实参与复合密钥
                    onConfirm(
                        vaultName,
                        passwordChars,
                        useKeyFile,
                        selectedPreset,
                        if (keyFileChoice == KeyFileSourceChoice.SELECT_EXISTING) selectedKeyFilePath else null,
                        // ISSUE-P2-229：仅「自选位置」时上行已挑定的文档 uri；内部存储传 null
                        selectedVaultUri.ifBlank { null }
                    )
                }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview(name = "新建密码库向导 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "新建密码库向导 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun CreateVaultWizardDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        CreateVaultWizardDialog(
            onDismiss = {},
            onConfirm = { _, _, _, _, _, _ -> }
        )
    }
}

/**
 * 新建密码库的落地位置（ISSUE-P2-229）。
 *
 * - [INTERNAL]：应用私有目录（`filesDir`）——具备原子写盘（`.tmp` + rename + `.bak`）
 *   与 WebDAV / S3 同步能力，为默认项；
 * - [EXTERNAL]：经系统文件选择器（`ACTION_CREATE_DOCUMENT`）由用户自选位置——
 *   便于自行备份与跨应用查看，但写回为非原子的 `"rwt"` 截断式写、且不参与同步
 *   （两条降级在向导内如实告知，并登记于 `docs/architecture/已知工程限界.md`）。
 */
internal enum class VaultStorageLocation {
    INTERNAL,
    EXTERNAL
}
