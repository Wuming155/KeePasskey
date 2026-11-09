package com.keepasskey.app.ui.screens.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.keepasskey.app.R
import com.keepasskey.app.security.SecureDialogWindowEffect
import com.keepasskey.app.ui.components.SecurePasswordField

/**
 * ISSUE-P3-384：第二库凭据补录对话框（`.kdbx` 并入）。
 *
 * 敏感纪律（ISSUE-P3-580 校正，原「借用语义 / 取消时由调用方清零」表述失实）：本组件自身状态中的
 * 主密码数组在**提交与取消两条路径**都即时清零（提交时 `copyOf()` 交出后清自身状态，数组所有权随
 * `onSubmit` 转移；本组件不再持有、也不再清零交出的那一份）。**交出的数组由宿主提交链收口清零**——
 * 生产链首环 `SettingsImportPresenter.submitMergeCredentials` 在提交返回后统一 `fill(0)`。
 * 密钥文件只上行 **Uri**，字节读取与清零同在宿主侧该收口点完成。
 *
 * 窗口保护：本窗承载第二库主密码（`SecurePasswordField`，可切明文显示），属**凭据类对话框**，
 * 故 `securePolicy` 恒为 `SecureOn`，并由 [SecureDialogWindowEffect] 在本窗内容槽位内施加遮挡触摸
 * 过滤，**不读**用户防截屏开关——与 `SecureDialog` KDoc 列出的其余始终保护对话框同口径。
 * 两者都作用于**本对话框自己的窗口**（Compose `AlertDialog` 由 `Dialog` 另建窗口，宿主 Activity
 * 的 flag 与触摸过滤都不会传播），故 effect 必须在内容槽位内调用。
 */
@Composable
fun ImportMergeCredentialSheet(
    displayName: String?,
    keyFileDisplayName: String?,
    onPickKeyFile: () -> Unit,
    onClearKeyFile: () -> Unit,
    onSubmit: (passwordChars: CharArray) -> Unit,
    onCancel: () -> Unit
) {
    var passwordChars by remember { mutableStateOf(CharArray(0)) }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        // 本窗承载第二库主密码，属**始终保护**的凭据类对话框：不读用户防截屏开关，显式 SecureOn。
        // 对话框窗口 FLAG_SECURE 的实际决定者是本参数（默认 Inherit 会按宿主窗口清除）
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.kdbx_merge_credential_title)) },
        text = {
            // FLAG_SECURE 与遮挡触摸过滤都是窗口级属性，不会从宿主 Activity 窗口传播；
            // 必须在对话框自身内容槽位内施加（在 AlertDialog(...) 之外调用解析不到本窗）
            SecureDialogWindowEffect()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(
                        R.string.kdbx_merge_credential_body,
                        displayName ?: stringResource(R.string.kdbx_merge_credential_unknown_file)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                SecurePasswordField(
                    label = stringResource(R.string.kdbx_merge_credential_password),
                    onPasswordChanged = { chars ->
                        passwordChars.fill('0')
                        passwordChars = chars.copyOf()
                    },
                    isPasswordVisible = passwordVisible,
                    onToggleVisibility = { passwordVisible = !passwordVisible },
                    initialPassword = null,
                    initialKey = null
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onPickKeyFile,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            keyFileDisplayName
                                ?: stringResource(R.string.kdbx_merge_credential_pick_keyfile)
                        )
                    }
                    if (keyFileDisplayName != null) {
                        TextButton(onClick = onClearKeyFile) {
                            Text(stringResource(R.string.btn_cancel))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pwd = passwordChars.copyOf()
                    passwordChars.fill('0')
                    passwordChars = CharArray(0)
                    onSubmit(pwd)
                }
            ) {
                Text(stringResource(R.string.kdbx_merge_credential_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                passwordChars.fill('0')
                passwordChars = CharArray(0)
                onCancel()
            }) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
