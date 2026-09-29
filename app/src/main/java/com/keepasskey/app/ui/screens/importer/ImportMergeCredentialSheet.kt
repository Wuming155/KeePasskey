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
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.SecurePasswordField

/**
 * ISSUE-P3-384：第二库凭据补录对话框（`.kdbx` 并入）。
 *
 * 敏感纪律：主密码以 CharArray 借用语义提交；密钥文件只上行 **Uri**，
 * 字节读取由宿主（设置页 / ViewModel）在提交时经 ContentResolver 完成并负责清零。
 * 取消时由调用方清零密码数组。
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
        title = { Text(stringResource(R.string.kdbx_merge_credential_title)) },
        text = {
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
