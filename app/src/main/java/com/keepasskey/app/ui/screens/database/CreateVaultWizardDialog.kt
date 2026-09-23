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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
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
        // 对话框窗口的 FLAG_SECURE 由 Compose 的 SecureFlagPolicy 决定（默认 Inherit ← **宿主窗口**），
        // 宿主不带该 flag 时 Inherit 会清掉本窗的 flag（ISSUE-P2-246 真机实测）；故显式要求 SecureOn
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
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

/** 建库向导的 SAF 启动器对（§280 自 [CreateVaultWizardDialog] 拆出）。 */
private class CreateVaultWizardLaunchers(
    val pickKeyFile: (Array<String>) -> Unit,
    val createVaultDocument: (String) -> Unit
)

/**
 * 向导表单状态（§280）：名称 / 密码对 / 密钥文件 / 预设 / 存储位置。
 * 主密码以 [CharArray] 承载；离场由 [CreateVaultWizardDialog] 擦除。
 */
private class CreateVaultWizardState {
    var vaultName by mutableStateOf("passwords.kdbx")
    var passwordChars by mutableStateOf(CharArray(0))
    var confirmChars by mutableStateOf(CharArray(0))
    var passwordVisible by mutableStateOf(false)
    var useKeyFile by mutableStateOf(false)
    var keyFileChoice by mutableStateOf(KeyFileSourceChoice.GENERATE)
    var selectedKeyFilePath by mutableStateOf("")
    var selectedKeyFileName by mutableStateOf("")
    // ISSUE-P2-85：预设改为类型化枚举——芯片与落盘共用 `CreateVaultPreset` 单一真相源
    var selectedPreset by mutableStateOf(CreateVaultPreset.DEFAULT)
    // ISSUE-P2-229：新建库的落地位置二选一；选「自选位置」时必须已挑定文档
    var storageLocation by mutableStateOf(VaultStorageLocation.INTERNAL)
    var selectedVaultUri by mutableStateOf("")
    var selectedVaultFileName by mutableStateOf("")

    val isLocationValid get() = storageLocation == VaultStorageLocation.INTERNAL || selectedVaultUri.isNotBlank()
    val isKeyFileValid get() = !useKeyFile || keyFileChoice == KeyFileSourceChoice.GENERATE ||
        selectedKeyFilePath.isNotBlank()
    val isFormValid get() = vaultName.isNotBlank() && passwordChars.isNotEmpty() &&
        passwordChars.contentEquals(confirmChars) && isKeyFileValid && isLocationValid

    fun wipePasswords() {
        passwordChars.fill('0')
        confirmChars.fill('0')
    }
}

/**
 * 新建密码库向导（ISSUE-P3-31 批次 B 自 `DatabasePickerScreen.kt` 拆出）。
 * ISSUE-P3-188 §179：段落组件下沉 `CreateVaultWizardDialogSections.kt`；
 * 主密码 / 确认密码的 [CharArray] 管线与离场擦除的 [DisposableEffect] 留在本体。
 * §280：表单体、SAF 启动器、对话框壳与状态对象下沉本文件私有组件。
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
    val state = remember { CreateVaultWizardState() }
    val launchers = rememberCreateVaultWizardLaunchers(
        onKeyFilePicked = { name, path ->
            state.selectedKeyFileName = name
            state.selectedKeyFilePath = path
        },
        onVaultDocumentPicked = { name, path ->
            state.selectedVaultFileName = name
            state.selectedVaultUri = path
        },
        onVaultDocumentCancelled = {
            state.storageLocation = VaultStorageLocation.INTERNAL
        }
    )

    // 弹窗离场（确认 / 取消 / 进程回收）时擦除组件内持有的全部密码副本
    DisposableEffect(Unit) {
        onDispose { state.wipePasswords() }
    }

    CreateVaultWizardAlertDialog(
        state = state,
        launchers = launchers,
        onDismiss = onDismiss,
        // H2 整改：直接移交组件持有的 CharArray（ViewModel 复制私有副本并自行擦除）
        // ISSUE-P3-21：SELECT_EXISTING 时上行选中的密钥文件 Uri，其字节真实参与复合密钥
        // ISSUE-P2-229：仅「自选位置」时上行已挑定的文档 uri；内部存储传 null
        onConfirm = {
            onConfirm(
                state.vaultName,
                state.passwordChars,
                state.useKeyFile,
                state.selectedPreset,
                if (state.keyFileChoice == KeyFileSourceChoice.SELECT_EXISTING) state.selectedKeyFilePath else null,
                state.selectedVaultUri.ifBlank { null }
            )
        }
    )
}

/** 向导对话框壳（§280）：两处 `AlertDialog` 与 `SecureOn` 要求均落在本文件。 */
@Composable
private fun CreateVaultWizardAlertDialog(
    state: CreateVaultWizardState,
    launchers: CreateVaultWizardLaunchers,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // 同 KeyFileOneTimeSaveDialog：本窗含主密码与确认主密码，须显式要求对话框窗口遮罩
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = {
            Text(
                text = stringResource(R.string.db_picker_create_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            CreateVaultWizardForm(state = state, launchers = launchers)
        },
        confirmButton = {
            CreateVaultConfirmButton(enabled = state.isFormValid, onCreate = onConfirm)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
private fun rememberCreateVaultWizardLaunchers(
    onKeyFilePicked: (displayName: String, path: String) -> Unit,
    onVaultDocumentPicked: (displayName: String, path: String) -> Unit,
    onVaultDocumentCancelled: () -> Unit
): CreateVaultWizardLaunchers {
    val context = LocalContext.current
    val keyPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onKeyFilePicked(queryDocumentDisplayName(context, uri), uri.toString())
        }
    }
    // ISSUE-P2-229：ACTION_CREATE_DOCUMENT 通道（与密钥文件另存为同一 contract 口径）；
    // 用户在系统面板取消即回落到「应用私有目录」并**即时反映在单选项上**（不留隐式选择）
    val vaultCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            onVaultDocumentPicked(queryDocumentDisplayName(context, uri), uri.toString())
        } else {
            onVaultDocumentCancelled()
        }
    }
    return remember(keyPickerLauncher, vaultCreateLauncher) {
        CreateVaultWizardLaunchers(
            pickKeyFile = { keyPickerLauncher.launch(it) },
            createVaultDocument = { vaultCreateLauncher.launch(it) }
        )
    }
}

/**
 * 向导表单体（§280）：名称 / 存储位置 / 主密码对 / 密钥文件区 / 预设芯片。
 * 不持有状态；主密码 [CharArray] 管线与离场擦除仍由 [CreateVaultWizardDialog] 持有。
 */
@Composable
private fun CreateVaultWizardForm(
    state: CreateVaultWizardState,
    launchers: CreateVaultWizardLaunchers
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 本窗内含**主密码 + 确认主密码**两个 SecurePasswordField：对话框是独立窗口，
        // 必须在本窗内显式施加 FLAG_SECURE（同 SecureDialog KDoc 的官方依据）
        com.keepasskey.app.security.SecureDialogWindowEffect()
        OutlinedTextField(
            value = state.vaultName,
            onValueChange = { state.vaultName = it },
            label = { Text(stringResource(R.string.db_picker_vault_name)) },
            placeholder = { Text(stringResource(R.string.db_picker_vault_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // ISSUE-P2-229：存储位置二选一（内部存储 / 经系统文件选择器自选）
        VaultStorageLocationSection(
            location = state.storageLocation,
            pickedFileName = state.selectedVaultFileName,
            onSelectInternal = {
                state.storageLocation = VaultStorageLocation.INTERNAL
                state.selectedVaultUri = ""
                state.selectedVaultFileName = ""
            },
            onSelectExternal = {
                state.storageLocation = VaultStorageLocation.EXTERNAL
                launchers.createVaultDocument(
                    if (state.vaultName.endsWith(".kdbx", ignoreCase = true)) state.vaultName else "${state.vaultName}.kdbx"
                )
            }
        )

        CreateVaultPasswordFields(state = state)

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
                useKeyFile = state.useKeyFile,
                onUseKeyFileChange = { state.useKeyFile = it }
            )

            if (state.useKeyFile) {
                KeyFileSourceSection(
                    keyFileChoice = state.keyFileChoice,
                    onKeyFileChoiceChange = { state.keyFileChoice = it },
                    selectedKeyFileName = state.selectedKeyFileName,
                    selectedKeyFilePath = state.selectedKeyFilePath,
                    onSelectedKeyFilePathChange = { state.selectedKeyFilePath = it },
                    onBrowse = { launchers.pickKeyFile(arrayOf("*/*")) }
                )
            }
        }

        Text(
            text = stringResource(R.string.db_picker_encryption_preset),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        CreateVaultPresetChips(
            selected = state.selectedPreset,
            onSelect = { state.selectedPreset = it }
        )
    }
}

/** 主密码 + 确认密码（CharArray 管线由 [CreateVaultWizardState] 持有）。 */
@Composable
private fun CreateVaultPasswordFields(state: CreateVaultWizardState) {
    SecurePasswordField(
        label = stringResource(R.string.db_picker_new_pwd),
        onPasswordChanged = { chars ->
            state.passwordChars.fill('0')
            state.passwordChars = chars.copyOf()
        },
        isPasswordVisible = state.passwordVisible,
        onToggleVisibility = { state.passwordVisible = !state.passwordVisible },
        modifier = Modifier.fillMaxWidth()
    )

    SecurePasswordField(
        label = stringResource(R.string.db_picker_confirm_pwd),
        onPasswordChanged = { chars ->
            state.confirmChars.fill('0')
            state.confirmChars = chars.copyOf()
        },
        isError = state.confirmChars.isNotEmpty() && !state.confirmChars.contentEquals(state.passwordChars),
        supportingText = {
            if (state.confirmChars.isNotEmpty() && !state.confirmChars.contentEquals(state.passwordChars)) {
                Text(stringResource(R.string.db_picker_pwd_mismatch), color = MaterialTheme.colorScheme.error)
            }
        },
        isPasswordVisible = state.passwordVisible,
        onToggleVisibility = { state.passwordVisible = !state.passwordVisible },
        modifier = Modifier.fillMaxWidth()
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
