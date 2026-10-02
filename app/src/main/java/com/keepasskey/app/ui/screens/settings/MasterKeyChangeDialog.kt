package com.keepasskey.app.ui.screens.settings

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ChangeKeyFileIntent
import com.keepasskey.app.security.SecureDialog
import com.keepasskey.app.ui.components.MasterPasswordPolicy
import com.keepasskey.app.ui.components.MasterPasswordWeakConfirmDialog
import com.keepasskey.app.ui.components.rememberMasterPasswordStrengthBits
import com.keepasskey.app.ui.components.disabledPrimaryButtonBorder
import com.keepasskey.app.ui.components.disabledPrimaryButtonColors
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult
import com.keepasskey.app.ui.theme.CapsuleShape
import kotlinx.coroutines.launch

/**
 * 现代主密钥更改对话框（ISSUE-P2-354 AC③ 重写提交链路）。
 *
 * 状态分工（勿把任务态搬回本地）：
 * - [isBusy]（真相源 = `SettingsUiState.isChangingMasterKey`，由 `SettingsViewModel` 的
 *   `viewModelScope` 承载）：提交后对话框**保持打开并呈现进度**，忙时不可重复提交、不可关闭，
 *   直到全库 Argon2 重派生 + 重加密完成——任务不跑在任何 UI scope 上，切 Tab 不取消；
 * - 本对话框只持有表单本地态（开关、两个 CharArray、可见性、弱口令确认闸与 `submitted` 防抖）。
 *
 * ISSUE-P3-428：密钥文件第二因子三态（[KeyFileChoice] → [ChangeKeyFileIntent]）——
 * 保持现状 / 绑定或更换（SAF 选文件，经 [onReadKeyFile] 全仓唯一读取通道）/ 解绑。
 * 选定文件的字节为借用语义：换选与关闭路径就地清零；提交时所有权随 [ChangeKeyFileIntent.Use]
 * 移交控制器（其 `finally` 统一清零），本对话框只丢引用、不再补刀。
 * ISSUE-P3-433（ISSUE-P3-430 语义改造）：「修改主密码」改为**显式开关**（KeePassDX
 * `SetMainCredentialDialogFragment` 卡片开关口径）——默认关＝保持当前主密码，密码框不渲染，
 * 取代「两框整体留空＝保持」的暗示语义；开关关仍传空数组走控制器既有 `changeKeyFileOnly`
 * 通道，提交管线零改动。至少一处改动的空提交防护保留（判据从「留空」改为「开关」）。
 *
 * 结果反馈不再由本对话框发 Snackbar：结果经 UiState 回执，由宿主 `SettingsContent`
 * 的既有 Snackbar 路径展示（这样切 Tab 错过时反馈也不丢）；busy 回落时对话框自行关闭。
 */
@Composable
internal fun MasterKeyChangeDialog(
    kdfAlgorithm: String,
    /** 任务进行中（`SettingsUiState.isChangingMasterKey` 下行；非本地态） */
    isBusy: Boolean,
    /**
     * 提交新主口令与密钥文件意图：数组**所有权移交** ViewModel（忙 / 闲、成败、异常路径都由其清零）；
     * `Use` 字节的清零责任同移交（对话框在此调用后只持有已清引用）
     */
    onChangeMasterPassword: (CharArray, ChangeKeyFileIntent) -> Unit,
    /** ISSUE-P3-428：读取用户选定密钥文件（全仓唯一 SAF 通道，生产= `SettingsViewModel.readKeyFileBytes`） */
    onReadKeyFile: suspend (String) -> KeyFileReadResult,
    onDismiss: () -> Unit,
    /** ISSUE-P2-288：用户显式确认弱主口令时的留痕回调（不落明文） */
    onWeakPasswordConfirmed: () -> Unit = {}
) {
    // M3 整改（加解密审查 2026-09）：新主密码以 CharArray 承载（SecurePasswordField 桥接），
    // 不进入 String 状态——String 副本不可擦除且驻留堆内存
    var newPasswordChars by remember { mutableStateOf(CharArray(0)) }
    var confirmPasswordChars by remember { mutableStateOf(CharArray(0)) }
    var passwordVisible by remember { mutableStateOf(false) }
    // ISSUE-P3-433：「修改主密码」显式开关——关闭即就地擦除两框字节（不留悬空副本）
    var passwordChangeEnabled by remember { mutableStateOf(false) }
    // ISSUE-P3-428：密钥文件三态本地选择与已读取文件（字节借用语义，见类 KDoc）
    var keyFileChoice by remember { mutableStateOf(KeyFileChoice.KEEP) }
    var pickedKeyFile by remember { mutableStateOf<PickedKeyFile?>(null) }
    var keyFileReadFailed by remember { mutableStateOf(false) }
    // ISSUE-P2-354 AC③：本地「已提交」标记——覆盖「点提交 → uiState.busy 尚未重组」的间隙，
    // 防止同帧双击把同一份表单交出去两次；busy 由 uiState 承载（切 Tab 后依然成立）
    var submitted by remember { mutableStateOf(false) }

    val keyFileScope = rememberCoroutineScope()
    val keyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keyFileScope.launch {
            when (val outcome = onReadKeyFile(uri.toString())) {
                is KeyFileReadResult.Success -> {
                    pickedKeyFile?.erase()
                    pickedKeyFile = PickedKeyFile(outcome.bytes, outcome.displayName, uri.toString())
                    keyFileReadFailed = false
                }
                // 「读不到」分型一律显式反馈，绝不静默当成功（fail-closed，ISSUE-P3-04 口径）
                KeyFileReadResult.Empty,
                KeyFileReadResult.TooLarge,
                KeyFileReadResult.Unreadable -> keyFileReadFailed = true
            }
        }
    }

    // 对话框关闭（确认/取消/点按外部）即擦除；提交副本的擦除责任移交 ViewModel 后，
    // 本地两份仍在关闭路径上按原契约清零；ISSUE-P3-428：未随提交移交的密钥文件字节同窗口清零
    fun wipeDialogState() {
        newPasswordChars.fill('0')
        newPasswordChars = CharArray(0)
        confirmPasswordChars.fill('0')
        confirmPasswordChars = CharArray(0)
        passwordChangeEnabled = false
        pickedKeyFile?.erase()
        pickedKeyFile = null
        keyFileReadFailed = false
    }

    // ISSUE-P3-428：本地三态 → 提交意图。USE 时字节所有权随意图移交（丢引用、不清零）；
    // 其余两态不该残留已选文件，就地擦除兜底
    fun intentOfChoice(): ChangeKeyFileIntent = when (keyFileChoice) {
        KeyFileChoice.KEEP -> ChangeKeyFileIntent.Keep
        // ISSUE-P3-434：来源 Uri + 显示名随意图上行（非密钥元数据），供成功后同步记忆记录
        KeyFileChoice.USE -> pickedKeyFile?.let {
            ChangeKeyFileIntent.Use(it.bytes, it.sourceUri, it.displayName)
        } ?: ChangeKeyFileIntent.Keep
        KeyFileChoice.REMOVE -> ChangeKeyFileIntent.Remove
    }

    // ISSUE-P2-288 AC①／AC②：长度下限硬阻断 + 弱口令显式二次确认
    // （单一判据 MasterPasswordPolicy，与建库向导共用；内核评估在 Dispatchers.Default）
    val strengthBits = rememberMasterPasswordStrengthBits(newPasswordChars)
    var showWeakConfirm by remember { mutableStateOf(false) }

    val passwordsMatch = newPasswordChars.contentEquals(confirmPasswordChars)
    // ISSUE-P3-433：开关关＝保持当前主密码（字段已随关闭就地清零）；开＝按原改密校验
    val keepPassword = !passwordChangeEnabled
    val passwordValid = keepPassword ||
        (passwordsMatch && newPasswordChars.size >= MasterPasswordPolicy.MIN_LENGTH)
    // ISSUE-P3-430→P3-433：至少要有一处改动（密码开关开且有输入，或密钥文件三态非 Keep），
    // 避免「无任何改动」的空提交
    val hasAnyChange = (!keepPassword && newPasswordChars.isNotEmpty()) ||
        keyFileChoice != KeyFileChoice.KEEP
    // ISSUE-P3-428：「绑定或更换」必须已成功读到文件才可提交（fail-closed）
    val keyFileChoiceSatisfied = keyFileChoice != KeyFileChoice.USE || pickedKeyFile != null

    // ISSUE-P2-354 AC③：任务完成（busy true→false 边沿）后由本对话框关闭——
    // 提交时保持打开并显示进度，完成才 dismiss（反馈经宿主 Snackbar 展示）
    LaunchedEffect(isBusy) {
        if (submitted && !isBusy) onDismiss()
    }

    /** 门槛闸后的真实提交（原 onClick 内联逻辑；提交动作只移交数组，不再自持协程） */
    fun submitNewPassword() {
        if (isBusy || submitted || !passwordValid || !hasAnyChange || !keyFileChoiceSatisfied) return
        submitted = true
        val pwdChars = newPasswordChars.copyOf()
        val intent = intentOfChoice()
        if (intent is ChangeKeyFileIntent.Use) pickedKeyFile = null
        wipeDialogState()
        onChangeMasterPassword(pwdChars, intent)
    }

    if (showWeakConfirm) {
        MasterPasswordWeakConfirmDialog(
            strengthBits = strengthBits,
            onConfirm = {
                showWeakConfirm = false
                onWeakPasswordConfirmed()
                submitNewPassword()
            },
            onCancel = { showWeakConfirm = false }
        )
    }

    AlertDialog(
        onDismissRequest = {
            // ISSUE-P2-354 AC③：忙时不可关闭（任务在跑，关框只会留下「看不见的重加密」）
            if (!isBusy) {
                wipeDialogState()
                onDismiss()
            }
        },
        // SecureFlagPolicy 默认 Inherit 会按宿主窗口清掉本窗 FLAG_SECURE（ISSUE-P2-246），故须 SecureOn
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = {
            Text(
                text = stringResource(R.string.settings_change_master_key),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            // FLAG_SECURE 是窗口级属性：AlertDialog 由 Compose 创建独立窗口，Activity 窗口的
            // flag 不会传播，必须在本对话框自身的内容里施加（否则主密码输入可被截图 / 录屏 /
            // Recents 预览，见 SecureDialog KDoc 与官方 "Excluding views from assistants"）
            SecureDialog {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.set_master_key_dialog_desc, kdfAlgorithm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MasterKeyChangePasswordSection(
                        changeEnabled = passwordChangeEnabled,
                        passwordVisible = passwordVisible,
                        enabled = !isBusy && !submitted,
                        onToggleChange = { checked ->
                            if (!checked) {
                                // 关闭开关即擦除已输入的字节（不留悬空副本）
                                newPasswordChars.fill('0')
                                newPasswordChars = CharArray(0)
                                confirmPasswordChars.fill('0')
                                confirmPasswordChars = CharArray(0)
                            }
                            passwordChangeEnabled = checked
                        },
                        onNewPassword = { chars ->
                            newPasswordChars.fill('0')
                            newPasswordChars = chars.copyOf()
                        },
                        onConfirmPassword = { chars ->
                            confirmPasswordChars.fill('0')
                            confirmPasswordChars = chars.copyOf()
                        },
                        onToggleVisibility = { passwordVisible = !passwordVisible }
                    )
                    MasterKeyFileChoiceSection(
                        choice = keyFileChoice,
                        picked = pickedKeyFile,
                        readFailed = keyFileReadFailed,
                        enabled = !isBusy && !submitted,
                        onChoiceSelect = { selected ->
                            if (selected != KeyFileChoice.USE) {
                                // 离开「绑定或更换」即擦除未随提交的字节（不留悬空副本）
                                pickedKeyFile?.erase()
                                pickedKeyFile = null
                                keyFileReadFailed = false
                            }
                            keyFileChoice = selected
                        },
                        onPickFile = {
                            keyFilePickerLauncher.launch(arrayOf("*/*"))
                        }
                    )
                }
            }
        },
        confirmButton = {
            MasterKeyChangeConfirmButton(
                // ISSUE-P2-288 AC①：长度下限硬阻断（单一判据，与建库向导共用）
                // ISSUE-P2-354 AC③：忙时禁用 + 内嵌进度
                // ISSUE-P3-428：「绑定或更换」未选定文件时禁提交
                // ISSUE-P3-433：密码开关关且密钥文件 Keep 时禁提交（无任何改动）
                enabled = passwordValid &&
                    hasAnyChange &&
                    keyFileChoiceSatisfied &&
                    !isBusy && !submitted,
                showProgress = isBusy || submitted,
                onClick = {
                    if (!keepPassword &&
                        MasterPasswordPolicy.verdictOf(newPasswordChars.size, strengthBits) ==
                        MasterPasswordPolicy.Verdict.WEAK_REQUIRES_CONFIRM
                    ) {
                        showWeakConfirm = true
                    } else {
                        submitNewPassword()
                    }
                }
            )
        },
        dismissButton = {
            // ISSUE-P2-354 AC③：忙时「取消」禁用（任务在跑，不能假装没提交）
            TextButton(
                onClick = {
                    wipeDialogState()
                    onDismiss()
                },
                enabled = !isBusy
            ) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

/**
 * 改密对话框内密钥文件三态的本地选择（ISSUE-P3-428，映射 [ChangeKeyFileIntent]）。
 * 刻意用枚举而非 `Boolean` 开关——三态语义在类型层面闭合，不重蹈「虚假开关」覆辙。
 * （ISSUE-P3-433：`internal` 供同包渲染面 [MasterKeyFileChoiceSection] 作参数类型。）
 */
internal enum class KeyFileChoice { KEEP, USE, REMOVE }

/**
 * 已读取的用户选定密钥文件（ISSUE-P3-428）。
 * [bytes] 为借用语义：换选、关闭路径由对话框就地清零，提交时所有权随意图移交；
 * [sourceUri] / [displayName] 为非密钥元数据，随 [ChangeKeyFileIntent.Use] 上行，
 * 供提交成功后同步「记住的密钥文件位置」（ISSUE-P3-434）。
 * （ISSUE-P3-433：`internal` 供同包渲染面 [MasterKeyFileChoiceSection] 作参数类型。）
 */
internal class PickedKeyFile(
    val bytes: ByteArray,
    val displayName: String,
    val sourceUri: String
) {
    fun erase() {
        bytes.fill(0)
    }
}

/**
 * 「保存更改」按钮渲染面（§208 自 [MasterKeyChangeDialog] 下沉）。
 *
 * **只搬渲染**：enabled 判定与 onClick 提交逻辑留在对话框现场
 * （copyOf → wipe → 把数组移交 ViewModel 的链路见该函数 KDoc）；
 * ISSUE-P3-134 禁用态共用配色 / 描边随渲染面同迁（逐字）；
 * ISSUE-P2-354 AC③ 新增 [showProgress] 内嵌进度。
 */
@Composable
private fun MasterKeyChangeConfirmButton(
    enabled: Boolean,
    /** ISSUE-P2-354 AC③：任务进行中——按钮内嵌进度（无字面量默认值，调用方必须显式传） */
    showProgress: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CapsuleShape,
        // ISSUE-P3-134：两次输入未一致时「保存更改」须仍可辨识——接入禁用态共用
        // 配色 / 描边，而非 MD3 默认的 onSurface @12%（同 ButtonStyles.kt 的口径）
        colors = disabledPrimaryButtonColors(),
        border = disabledPrimaryButtonBorder()
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(MASTER_KEY_PROGRESS_SIZE.dp),
                strokeWidth = 2.dp
            )
        } else {
            Text(stringResource(R.string.set_save_changes))
        }
    }
}

/** ISSUE-P2-354 AC③：「保存更改」按钮内嵌进度圈直径（dp） */
private const val MASTER_KEY_PROGRESS_SIZE = 18

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "主密钥更改对话框 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "主密钥更改对话框 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun MasterKeyChangeDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        MasterKeyChangeDialog(
            kdfAlgorithm = "Argon2id",
            isBusy = false,
            onDismiss = {},
            // 预览桩：不发起任何真实密钥派生 / 文件读取
            onChangeMasterPassword = { chars, _ -> chars.fill('0') },
            onReadKeyFile = { com.keepasskey.app.ui.screens.unlock.KeyFileReadResult.Unreadable }
        )
    }
}

/**
 * `ISSUE-P2-354 AC③`：`isBusy = true` 那一态（默认态预览只画 `false`）——
 * 忙态差异恰在「保存禁用 + 内嵌进度 + 取消置灰」，不补态就永远看不见。
 */
@androidx.compose.ui.tooling.preview.Preview(name = "主密钥更改对话框 - 更换进行中", showBackground = true)
@Composable
internal fun MasterKeyChangeDialogBusyPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        MasterKeyChangeDialog(
            kdfAlgorithm = "Argon2id",
            isBusy = true,
            onDismiss = {},
            onChangeMasterPassword = { chars, _ -> chars.fill('0') },
            onReadKeyFile = { com.keepasskey.app.ui.screens.unlock.KeyFileReadResult.Unreadable }
        )
    }
}
