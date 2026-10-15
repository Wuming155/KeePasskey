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
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.keepasskey.app.data.repository.NewVaultDocumentClaims
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
    // （§396：存储位置只有本地两档，「云端直建」整条退役，不再上行 storageLocation）
    onConfirm: (
        name: String,
        pwd: CharArray,
        keyFile: Boolean,
        preset: CreateVaultPreset,
        keyFileSourceUri: String?,
        targetUri: String?
    ) -> Unit,
    /** ISSUE-P2-288：用户显式确认弱主口令时的留痕回调（不落明文） */
    onWeakPasswordConfirmed: () -> Unit = {},
    /**
     * ISSUE-P2-354 AC①：建库进行中（busy 时提交按钮禁用 + 内嵌进度、取消与点按外部均不可关闭）。
     * 真相源是 `DatabasePickerUiState.isLoading`（ViewModel 同步守卫的投影），不是对话框本地态。
     */
    isBusy: Boolean = false
) {
    val state = remember { CreateVaultWizardState() }
    val context = LocalContext.current
    ObserveNewVaultName(state)
    LaunchedEffect(state.selectedVaultUri) {
        val ownedPath = state.selectedVaultUri
        try { awaitCancellation() }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                NewVaultDocumentClaims.discard(context, ownedPath)
            }
        }
    }
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
        onDispose {
            state.wipePasswords()
        }
    }

    CreateVaultWizardAlertDialog(
        state = state,
        launchers = launchers,
        isBusy = isBusy,
        onDismiss = onDismiss,
        // H2 整改：直接移交组件持有的 CharArray（ViewModel 复制私有副本并自行擦除）
        // ISSUE-P3-21：SELECT_EXISTING 时上行选中的密钥文件 Uri，其字节真实参与复合密钥
        // ISSUE-P2-229：「自选位置」上行已挑定的文档 uri；应用私有目录传 null
        onConfirm = {
            onConfirm(
                state.vaultName,
                state.passwordChars,
                state.useKeyFile,
                state.selectedPreset,
                if (state.keyFileChoice == KeyFileSourceChoice.SELECT_EXISTING) state.selectedKeyFilePath else null,
                state.selectedVaultUri.ifBlank { null }
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
            CreateVaultWizardForm(state = state, launchers = launchers)
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
    // ISSUE-P3-616：展示名查询（外部 Provider 调用）经组合作用域协程落 IO 调度器；
    // 向导关闭即取消，查询结果随之作废
    val scope = rememberCoroutineScope()
    val keyPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                onKeyFilePicked(queryDocumentDisplayName(context, uri), uri.toString())
            }
        }
    }
    // ISSUE-P2-229：ACTION_CREATE_DOCUMENT 通道（与密钥文件另存为同一 contract 口径）；
    // 用户在系统面板取消即回落到「应用私有目录」并**即时反映在单选项上**（不留隐式选择）
    val vaultCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                if (NewVaultDocumentClaims.record(context, uri)) {
                    val name = try {
                        queryDocumentDisplayName(context, uri)
                    } catch (cancellation: CancellationException) {
                        // 声明已登记而向导在查询期间关闭：撤销声明并按既有口径回收空文档，
                        // 防止「既不交付也不清理」的中间态
                        withContext(NonCancellable + Dispatchers.IO) {
                            NewVaultDocumentClaims.discard(context, uri.toString())
                        }
                        throw cancellation
                    }
                    onVaultDocumentPicked(name, uri.toString())
                } else {
                    onVaultDocumentCancelled()
                }
            }
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
    // §395：纵向间距 10 → 6dp（用户反馈弹窗过大；段落组件内部间距同批收敛）
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 本窗内含**主密码 + 确认主密码**两个 SecurePasswordField：对话框是独立窗口，
        // 必须在本窗内显式施加 FLAG_SECURE（同 SecureDialog KDoc 的官方依据）
        com.keepasskey.app.security.SecureDialogWindowEffect()
        OutlinedTextField(
            value = state.vaultName,
            onValueChange = {
                state.checkingName = true
                state.vaultName = it
            },
            isError = state.nameError != null,
            supportingText = {
                state.nameError?.let { Text(stringResource(it)) }
            },
            label = { Text(stringResource(R.string.db_picker_vault_name)) },
            placeholder = { Text(stringResource(R.string.db_picker_vault_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        state.suggestedName?.let { suggestion ->
            TextButton(onClick = { state.vaultName = suggestion }) { Text(suggestion) }
        }

        // ISSUE-P2-229：存储位置选择（§396：仅本地两档——应用私有目录 / 经系统文件选择器自选）
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
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
