package com.keepasskey.app.ui.screens.database

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
import com.keepasskey.app.ui.components.MasterPasswordPolicy
import com.keepasskey.app.ui.components.MasterPasswordWeakConfirmDialog
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.components.rememberMasterPasswordStrengthBits

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
    // ISSUE-P2-229：新建库的落地位置三选一；选「自选位置」时必须已挑定文档
    var storageLocation by mutableStateOf(VaultStorageLocation.INTERNAL)
    var selectedVaultUri by mutableStateOf("")
    var selectedVaultFileName by mutableStateOf("")

    val isLocationValid get() = storageLocation != VaultStorageLocation.EXTERNAL || selectedVaultUri.isNotBlank()
    val isKeyFileValid get() = !useKeyFile || keyFileChoice == KeyFileSourceChoice.GENERATE ||
        selectedKeyFilePath.isNotBlank()
    // ISSUE-P2-288 AC①：主口令长度下限硬阻断（单一判据 MasterPasswordPolicy，与改密共用）
    val isFormValid get() = vaultName.isNotBlank() &&
        passwordChars.size >= MasterPasswordPolicy.MIN_LENGTH &&
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
        targetUri: String?,
        storageLocation: VaultStorageLocation
    ) -> Unit,
    /** ISSUE-P2-288：用户显式确认弱主口令时的留痕回调（不落明文） */
    onWeakPasswordConfirmed: () -> Unit = {},
    /**
     * ISSUE-P2-354 AC①：建库进行中（busy 时提交按钮禁用 + 内嵌进度、取消与点按外部均不可关闭）。
     * 真相源是 `DatabasePickerUiState.isLoading`（ViewModel 同步守卫的投影），不是对话框本地态。
     */
    isBusy: Boolean = false,
    /** ISSUE-P3-425：云同步配置快照（「云端」位置的可用性与目标提示；默认 IDLE 供预览） */
    cloudSnapshot: CloudSyncSnapshot = CloudSyncSnapshot.IDLE
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
        isBusy = isBusy,
        cloudSnapshot = cloudSnapshot,
        onDismiss = onDismiss,
        // H2 整改：直接移交组件持有的 CharArray（ViewModel 复制私有副本并自行擦除）
        // ISSUE-P3-21：SELECT_EXISTING 时上行选中的密钥文件 Uri，其字节真实参与复合密钥
        // ISSUE-P2-229：仅「自选位置」时上行已挑定的文档 uri；内部存储与云端直建传 null
        onConfirm = {
            onConfirm(
                state.vaultName,
                state.passwordChars,
                state.useKeyFile,
                state.selectedPreset,
                if (state.keyFileChoice == KeyFileSourceChoice.SELECT_EXISTING) state.selectedKeyFilePath else null,
                state.selectedVaultUri.ifBlank { null },
                state.storageLocation
            )
        },
        onWeakPasswordConfirmed = onWeakPasswordConfirmed
    )
}

/** 向导对话框壳（§280）：两处 `AlertDialog` 与 `SecureOn` 要求均落在本文件。 */
@Composable
private fun CreateVaultWizardAlertDialog(
    state: CreateVaultWizardState,
    launchers: CreateVaultWizardLaunchers,
    /** ISSUE-P2-354 AC①：建库进行中（见 [CreateVaultWizardDialog.isBusy]） */
    isBusy: Boolean,
    /** ISSUE-P3-425：云同步配置快照（透传给存储位置区） */
    cloudSnapshot: CloudSyncSnapshot,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onWeakPasswordConfirmed: () -> Unit
) {
    // ISSUE-P2-288 AC①／AC②：主口令强度评估（内核，Dispatchers.Default）与
    // 弱口令显式二次确认闸（单一判据 MasterPasswordPolicy，与改密共用）
    val strengthBits = rememberMasterPasswordStrengthBits(state.passwordChars)
    var showWeakConfirm by remember { mutableStateOf(false) }

    if (showWeakConfirm) {
        MasterPasswordWeakConfirmDialog(
            strengthBits = strengthBits,
            onConfirm = {
                showWeakConfirm = false
                onWeakPasswordConfirmed()
                onConfirm()
            },
            onCancel = { showWeakConfirm = false }
        )
    }

    AlertDialog(
        // ISSUE-P2-354 AC①：建库进行中不可关闭（Argon2 派生秒级；关框会留下「看不见的并发建库」入口）
        onDismissRequest = { if (!isBusy) onDismiss() },
        // 同 KeyFileOneTimeSaveDialog：本窗含主密码与确认主密码，须显式要求对话框窗口遮罩
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = {
            Text(
                text = stringResource(R.string.db_picker_create_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            CreateVaultWizardForm(state = state, launchers = launchers, cloudSnapshot = cloudSnapshot)
        },
        confirmButton = {
            CreateVaultConfirmButton(
                enabled = state.isFormValid && !isBusy,
                showProgress = isBusy,
                onCreate = {
                    if (MasterPasswordPolicy.verdictOf(state.passwordChars.size, strengthBits) ==
                        MasterPasswordPolicy.Verdict.WEAK_REQUIRES_CONFIRM
                    ) {
                        showWeakConfirm = true
                    } else {
                        onConfirm()
                    }
                }
            )
        },
        dismissButton = {
            // ISSUE-P2-354 AC①：busy 时「取消」同样禁用——任务在跑，放行取消会留下孤儿协程观感
            TextButton(onClick = onDismiss, enabled = !isBusy) {
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
    launchers: CreateVaultWizardLaunchers,
    cloudSnapshot: CloudSyncSnapshot
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

        // ISSUE-P2-229：存储位置选择（内部存储 / 经系统文件选择器自选 / ISSUE-P3-425 云端直建）
        VaultStorageLocationSection(
            location = state.storageLocation,
            pickedFileName = state.selectedVaultFileName,
            cloudSnapshot = cloudSnapshot,
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
            },
            onSelectCloud = {
                state.storageLocation = VaultStorageLocation.CLOUD
                state.selectedVaultUri = ""
                state.selectedVaultFileName = ""
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
        // ISSUE-P2-288：短口令如实提示（硬阻断的用户可辨反馈）
        supportingText = {
            if (state.passwordChars.isNotEmpty() &&
                state.passwordChars.size < MasterPasswordPolicy.MIN_LENGTH
            ) {
                Text(
                    stringResource(R.string.master_pwd_too_short, MasterPasswordPolicy.MIN_LENGTH),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
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
