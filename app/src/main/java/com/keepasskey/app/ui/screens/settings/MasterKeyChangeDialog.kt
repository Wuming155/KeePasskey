package com.keepasskey.app.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 现代主密钥更改对话框
 */
@Composable
internal fun MasterKeyChangeDialog(
    kdfAlgorithm: String,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    masterKeyUpdatedMsg: String,
    onDismiss: () -> Unit,
    onChangeMasterPassword: suspend (CharArray) -> KdbxResult<Unit>,
    /** ISSUE-P2-288：用户显式确认弱主口令时的留痕回调（不落明文） */
    onWeakPasswordConfirmed: () -> Unit = {}
) {
    // M3 整改（加解密审查 2026-09）：新主密码以 CharArray 承载（SecurePasswordField 桥接），
    // 不进入 String 状态——String 副本不可擦除且驻留堆内存
    var newPasswordChars by remember { mutableStateOf(CharArray(0)) }
    var confirmPasswordChars by remember { mutableStateOf(CharArray(0)) }
    var passwordVisible by remember { mutableStateOf(false) }

    // 对话框关闭（确认/取消/点按外部）即擦除；下游 changeCredentials 不擦调用方数组，
    // 提交副本的擦除责任由本对话框承担
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

    /** 门槛闸后的真实提交（原 onClick 内联逻辑，行为逐字不变） */
    fun submitNewPassword() {
        if (passwordsMatch) {
            val pwdChars = newPasswordChars.copyOf()
            wipeDialogPasswords()
            onDismiss()
            coroutineScope.launch {
                try {
                    val result = onChangeMasterPassword(pwdChars)
                    when (result) {
                        is KdbxResult.Success<*> -> {
                            snackbarHostState.showSnackbar(masterKeyUpdatedMsg)
                        }
                        is KdbxResult.Failure -> {
                            snackbarHostState.showSnackbar(result.message)
                        }
                    }
                } finally {
                    // M3 整改：提交副本在任何结果路径用毕即清零
                    pwdChars.fill('0')
                }
            }
        }
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
            wipeDialogPasswords()
            onDismiss()
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
                enabled = passwordsMatch && newPasswordChars.size >= MasterPasswordPolicy.MIN_LENGTH,
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
            TextButton(onClick = {
                wipeDialogPasswords()
                onDismiss()
            }) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

/**
 * 新 / 确认两行主密码输入字段（§208 自 [MasterKeyChangeDialog] 下沉）。
 *
 * **只搬渲染面**：`onPasswordChanged` 回调原样上行（含调用方的 fill(0) + copyOf 擦除链），
 * 密码字节不经过本段落驻留；提交链路（copyOf → wipe → 协程 → finally 擦除）仍留在
 * 对话框现场（擦除链「单一现场」口径，勿为行数外搬）。
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
 * **只搬渲染**：enabled 判定与 onClick 提交逻辑（含 `pwdChars` 的 copyOf → wipe → 协程 →
 * finally 擦除链）全部留在对话框现场；ISSUE-P3-134 禁用态共用配色 / 描边随渲染面同迁（逐字）。
 */
@Composable
private fun MasterKeyChangeConfirmButton(
    enabled: Boolean,
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
        Text(stringResource(R.string.set_save_changes))
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "主密钥更改对话框 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "主密钥更改对话框 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun MasterKeyChangeDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        MasterKeyChangeDialog(
            kdfAlgorithm = "Argon2id",
            coroutineScope = androidx.compose.runtime.rememberCoroutineScope(),
            snackbarHostState = remember { SnackbarHostState() },
            masterKeyUpdatedMsg = "预览提示：主密钥已更新",
            onDismiss = {},
            // 预览桩：仅返回成功结果占位，不做任何真实密钥派生
            onChangeMasterPassword = { KdbxResult.Success(Unit) }
        )
    }
}
