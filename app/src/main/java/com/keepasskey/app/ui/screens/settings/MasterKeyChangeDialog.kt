package com.keepasskey.app.ui.screens.settings

import android.content.res.Configuration
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.keepasskey.app.R
import com.keepasskey.app.security.SecureDialog
import com.keepasskey.app.ui.components.MasterPasswordPolicy
import com.keepasskey.app.ui.components.MasterPasswordWeakConfirmDialog
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.components.rememberMasterPasswordStrengthBits
import com.keepasskey.app.ui.components.disabledPrimaryButtonBorder
import com.keepasskey.app.ui.components.disabledPrimaryButtonColors
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 现代主密钥更改对话框（ISSUE-P2-354 AC③ 重写提交链路）。
 *
 * 状态分工（勿把任务态搬回本地）：
 * - [isBusy]（真相源 = `SettingsUiState.isChangingMasterKey`，由 `SettingsViewModel` 的
 *   `viewModelScope` 承载）：提交后对话框**保持打开并呈现进度**，忙时不可重复提交、不可关闭，
 *   直到全库 Argon2 重派生 + 重加密完成——任务不跑在任何 UI scope 上，切 Tab 不取消；
 * - 本对话框只持有表单本地态（两个 CharArray、可见性、弱口令确认闸与 `submitted` 防抖）。
 *
 * 结果反馈不再由本对话框发 Snackbar：结果经 UiState 回执，由宿主 `SettingsContent`
 * 的既有 Snackbar 路径展示（这样切 Tab 错过时反馈也不丢）；busy 回落时对话框自行关闭。
 */
@Composable
internal fun MasterKeyChangeDialog(
    kdfAlgorithm: String,
    /** 任务进行中（`SettingsUiState.isChangingMasterKey` 下行；非本地态） */
    isBusy: Boolean,
    /** 提交新主口令：数组**所有权移交** ViewModel（忙 / 闲、成败、异常路径都由其清零） */
    onChangeMasterPassword: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    /** ISSUE-P2-288：用户显式确认弱主口令时的留痕回调（不落明文） */
    onWeakPasswordConfirmed: () -> Unit = {}
) {
    // M3 整改（加解密审查 2026-09）：新主密码以 CharArray 承载（SecurePasswordField 桥接），
    // 不进入 String 状态——String 副本不可擦除且驻留堆内存
    var newPasswordChars by remember { mutableStateOf(CharArray(0)) }
    var confirmPasswordChars by remember { mutableStateOf(CharArray(0)) }
    var passwordVisible by remember { mutableStateOf(false) }
    // ISSUE-P2-354 AC③：本地「已提交」标记——覆盖「点提交 → uiState.busy 尚未重组」的间隙，
    // 防止同帧双击把同一份表单交出去两次；busy 由 uiState 承载（切 Tab 后依然成立）
    var submitted by remember { mutableStateOf(false) }

    // 对话框关闭（确认/取消/点按外部）即擦除；提交副本的擦除责任移交 ViewModel 后，
    // 本地两份仍在关闭路径上按原契约清零
    fun wipeDialogPasswords() {
        newPasswordChars.fill('0')
        newPasswordChars = CharArray(0)
        confirmPasswordChars.fill('0')
        confirmPasswordChars = CharArray(0)
    }

    // ISSUE-P2-288 AC①／AC②：长度下限硬阻断 + 弱口令显式二次确认
    // （单一判据 MasterPasswordPolicy，与建库向导共用；内核评估在 Dispatchers.Default）
    val strengthBits = rememberMasterPasswordStrengthBits(newPasswordChars)
    var showWeakConfirm by remember { mutableStateOf(false) }

    val passwordsMatch = newPasswordChars.isNotEmpty() &&
        newPasswordChars.contentEquals(confirmPasswordChars)

    // ISSUE-P2-354 AC③：任务完成（busy true→false 边沿）后由本对话框关闭——
    // 提交时保持打开并显示进度，完成才 dismiss（反馈经宿主 Snackbar 展示）
    LaunchedEffect(isBusy) {
        if (submitted && !isBusy) onDismiss()
    }

    /** 门槛闸后的真实提交（原 onClick 内联逻辑；提交动作只移交数组，不再自持协程） */
    fun submitNewPassword() {
        if (isBusy || submitted || !passwordsMatch) return
        submitted = true
        val pwdChars = newPasswordChars.copyOf()
        wipeDialogPasswords()
        onChangeMasterPassword(pwdChars)
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
                wipeDialogPasswords()
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
                    MasterKeyChangePasswordFields(
                        kdfAlgorithm = kdfAlgorithm,
                        passwordVisible = passwordVisible,
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
                }
            }
        },
        confirmButton = {
            MasterKeyChangeConfirmButton(
                // ISSUE-P2-288 AC①：长度下限硬阻断（单一判据，与建库向导共用）
                // ISSUE-P2-354 AC③：忙时禁用 + 内嵌进度
                enabled = passwordsMatch &&
                    newPasswordChars.size >= MasterPasswordPolicy.MIN_LENGTH &&
                    !isBusy && !submitted,
                showProgress = isBusy || submitted,
                onClick = {
                    if (MasterPasswordPolicy.verdictOf(newPasswordChars.size, strengthBits) ==
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
                    wipeDialogPasswords()
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
 * 新 / 确认两行主密码输入字段（§208 自 [MasterKeyChangeDialog] 下沉）。
 *
 * **只搬渲染面**：`onPasswordChanged` 回调原样上行（含调用方的 fill(0) + copyOf 擦除链），
 * 密码字节不经过本段落驻留；提交链路（copyOf → wipe → 移交 ViewModel 的 viewModelScope 任务，
 * 由其负责任何结果路径的清零）仍留在对话框现场发起（擦除链「单一现场」口径，勿为行数外搬）。
 * `SecureDialog {` 包裹与 `AlertDialog` 本体留在宿主文件——`SecureDialogFlagPolicyTest`
 * 以 `SecureDialog {` 调用点计数锚定该文件（§193 清单锁）。
 */
@Composable
private fun MasterKeyChangePasswordFields(
    kdfAlgorithm: String,
    passwordVisible: Boolean,
    onNewPassword: (CharArray) -> Unit,
    onConfirmPassword: (CharArray) -> Unit,
    onToggleVisibility: () -> Unit
) {
    Text(
        text = stringResource(R.string.set_master_key_dialog_desc, kdfAlgorithm),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    SecurePasswordField(
        label = stringResource(R.string.set_new_master_password),
        onPasswordChanged = onNewPassword,
        isPasswordVisible = passwordVisible,
        onToggleVisibility = onToggleVisibility
    )

    SecurePasswordField(
        label = stringResource(R.string.set_confirm_master_password),
        onPasswordChanged = onConfirmPassword,
        isPasswordVisible = passwordVisible,
        onToggleVisibility = onToggleVisibility
    )
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
            // 预览桩：不发起任何真实密钥派生
            onChangeMasterPassword = { chars -> chars.fill('0') }
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
            onChangeMasterPassword = { chars -> chars.fill('0') }
        )
    }
}
