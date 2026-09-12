package com.keepasskey.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.keepasskey.app.R
import com.keepasskey.app.security.SecureDialog
import com.keepasskey.app.ui.components.SecurePasswordField
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
    onChangeMasterPassword: suspend (CharArray) -> KdbxResult<Unit>
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

    val passwordsMatch = newPasswordChars.isNotEmpty() &&
        newPasswordChars.contentEquals(confirmPasswordChars)

    AlertDialog(
        onDismissRequest = {
            wipeDialogPasswords()
            onDismiss()
        },
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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

                    SecurePasswordField(
                        label = stringResource(R.string.set_new_master_password),
                        onPasswordChanged = { chars ->
                            newPasswordChars.fill('0')
                            newPasswordChars = chars.copyOf()
                        },
                        isPasswordVisible = passwordVisible,
                        onToggleVisibility = { passwordVisible = !passwordVisible }
                    )

                    SecurePasswordField(
                        label = stringResource(R.string.set_confirm_master_password),
                        onPasswordChanged = { chars ->
                            confirmPasswordChars.fill('0')
                            confirmPasswordChars = chars.copyOf()
                        },
                        isPasswordVisible = passwordVisible,
                        onToggleVisibility = { passwordVisible = !passwordVisible }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
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
                },
                enabled = passwordsMatch,
                shape = CapsuleShape
            ) {
                Text(stringResource(R.string.set_save_changes))
            }
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
