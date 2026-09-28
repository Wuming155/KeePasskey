package com.keepasskey.app.passkey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.SecurePasswordField

/**
 * 「无匹配 → 就地新建」的口令草稿表单（`ISSUE-P3-345` / `PD-51`）。
 *
 * 两条通道（CM `Action` 与传统 Autofill 选择器）共用本表单：归属站点为**只读展示**
 * （`PD-51` 裁决 3：站点标识只允许出现在说明句与只读绑定展示），用户名按系统背书请求预填，
 * 密码由用户在本窗口输入——落库走 create-only 路径（`PD-51` 裁决 2），保存后由宿主 Activity
 * 决定交付形态（CM 回凭据本身 / 选择器回条目 id 走既有数据集链路）。
 *
 * 布尔参数一律**必填、不带默认值**（`ISSUE-P3-340` 普查口径：带默认值开关必须两态齐预览，
 * 新组件从源头不进该漏口）。
 */
@Composable
fun PasswordDraftScreen(
    bindingLabel: String,
    userName: String,
    onUserNameChange: (String) -> Unit,
    onPasswordChange: (CharArray) -> Unit,
    isSaving: Boolean,
    isSaveFailed: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.cred_draft_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.cred_draft_binding_label, bindingLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = userName,
                onValueChange = onUserNameChange,
                label = { Text(stringResource(R.string.edit_username_hint)) },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )
            SecurePasswordField(
                label = stringResource(R.string.edit_password_hint),
                onPasswordChanged = onPasswordChange,
                isError = isSaveFailed,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )
            if (isSaveFailed) {
                Text(
                    text = stringResource(R.string.autofill_save_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel, enabled = !isSaving) {
                    Text(stringResource(R.string.autofill_confirm_cancel))
                }
                Button(onClick = onSave, enabled = !isSaving) {
                    Text(stringResource(R.string.cred_draft_save))
                }
            }
        }
    }
}

@Preview(name = "草稿表单 · 可编辑态")
@Composable
internal fun PasswordDraftScreenPreviewEditable() {
    PasswordDraftScreen(
        bindingLabel = "example.com",
        userName = "user@example.com",
        onUserNameChange = {},
        onPasswordChange = {},
        isSaving = false,
        isSaveFailed = false,
        onSave = {},
        onCancel = {}
    )
}

@Preview(name = "草稿表单 · 保存中态")
@Composable
internal fun PasswordDraftScreenPreviewSaving() {
    PasswordDraftScreen(
        bindingLabel = "example.com",
        userName = "",
        onUserNameChange = {},
        onPasswordChange = {},
        isSaving = true,
        isSaveFailed = false,
        onSave = {},
        onCancel = {}
    )
}

@Preview(name = "草稿表单 · 保存失败态")
@Composable
internal fun PasswordDraftScreenPreviewFailed() {
    PasswordDraftScreen(
        bindingLabel = "example.com",
        userName = "user@example.com",
        onUserNameChange = {},
        onPasswordChange = {},
        isSaving = false,
        isSaveFailed = true,
        onSave = {},
        onCancel = {}
    )
}
