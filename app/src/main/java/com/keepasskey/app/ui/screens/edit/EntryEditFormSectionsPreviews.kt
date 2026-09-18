package com.keepasskey.app.ui.screens.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keepasskey.app.ui.preview.PreviewGroupLogins
import com.keepasskey.app.ui.preview.PreviewGroups
import com.keepasskey.app.ui.theme.KeePasskeyTheme

/**
 * 编辑页基础表单分节的 IDE 预览（ISSUE-P3-188 自 `EntryEditFormSections.kt` 纯结构性搬出，
 * 预览不参与运行时 UI，与被预览的分节同包可见）。
 */

@Preview(name = "编辑页基础表单分节 - 浅色", showBackground = true)
@Preview(name = "编辑页基础表单分节 - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
internal fun EntryEditBasicInfoSectionPreview() {
    KeePasskeyTheme {
        val previewUiState = EntryEditUiState(
            entryId = "preview-entry-edit",
            groupId = PreviewGroupLogins.id,
            availableGroups = PreviewGroups,
            iconName = "key",
            title = "预览编辑条目",
            username = "demo@example.com",
            // 与调用方传入的 loadedPassword 长度一致，避免「空密码 + 强度条」假状态
            passwordLength = 8,
            url = "https://example.com",
            notes = "预览用备注文本",
            isReadOnly = false,
            isPasswordVisible = false,
            showGenerator = false,
            passLength = 20f
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EntryEditGroupSection(
                uiState = previewUiState,
                onGroupChange = { }
            )
            EntryEditReadOnlyBanner(isReadOnly = true)
            EntryEditBasicInfoSection(
                uiState = previewUiState,
                customIconOptions = emptyList(),
                onIconClick = { },
                onTitleChange = { },
                onUrlChange = { }
            )
            // 空密码态：passwordLength 必须为 0，避免强度条在无密码时误显示
            EntryEditAccountSection(
                uiState = previewUiState.copy(passwordLength = 0),
                loadedPassword = null,
                onUsernameChange = { },
                onPasswordChangeSecure = { },
                onTogglePasswordVisibility = { },
                onToggleGenerator = { },
                onPassLengthChange = { },
                onGeneratePassword = { },
                onToggleUpper = { },
                onToggleLower = { },
                onToggleDigits = { },
                onToggleSymbols = { }
            )
            // 有密码态：loadedPassword 长度与 passwordLength 必须一致
            EntryEditAccountSection(
                uiState = previewUiState.copy(
                    showGenerator = true,
                    isPasswordVisible = true,
                    passwordLength = 8
                ),
                loadedPassword = "Passw0rd".toCharArray(),
                onUsernameChange = { },
                onPasswordChangeSecure = { },
                onTogglePasswordVisibility = { },
                onToggleGenerator = { },
                onPassLengthChange = { },
                onGeneratePassword = { },
                onToggleUpper = { },
                onToggleLower = { },
                onToggleDigits = { },
                onToggleSymbols = { }
            )
            EntryEditNotesSection(
                notes = "预览用备注文本，仅用于界面排版展示。",
                onNotesChange = { }
            )
        }
    }
}

/**
 * TASK-139 预览：URL 字段的「应用绑定」形态（`android://<包名>`）。
 *
 * 与上一预览的差异仅在 URL —— 用于目视核对三项新增绘制：前置应用图标、右侧选择器入口、
 * 以及「已关联应用：<名称>（<包名>）」辅助文案（含全角括号与长包名的换行表现）。
 * 预览环境下该包名不可解析，故按实现**如实回落**为「包名即名称 + 通用系统图标」。
 */
@Preview(name = "编辑页-应用绑定URL - 浅色", showBackground = true)
@Preview(name = "编辑页-应用绑定URL - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
internal fun EntryEditBoundAppSectionPreview() {
    KeePasskeyTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EntryEditBasicInfoSection(
                // 明显虚构的包名占位，不涉及任何真实应用或凭据
                uiState = EntryEditUiState(
                    entryId = "preview-entry-bound",
                    iconName = "key",
                    title = "预览编辑条目",
                    url = "android://com.example.previewapp"
                ),
                customIconOptions = emptyList(),
                onIconClick = { },
                onTitleChange = { },
                onUrlChange = { }
            )
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "凭据编辑内容 - 浅色", showBackground = true)
@Preview(name = "凭据编辑内容 - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
internal fun EntryEditContentPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        val previewSnackbar = remember { SnackbarHostState() }

        EntryEditContent(
            uiState = com.keepasskey.app.ui.screens.edit.EntryEditUiState(
                entryId = "preview-entry-edit",
                groupId = com.keepasskey.app.ui.preview.PreviewGroupLogins.id,
                availableGroups = com.keepasskey.app.ui.preview.PreviewGroups,
                iconName = "key",
                title = "预览编辑条目",
                username = "demo@example.com",
                // 与 loadedPassword 长度一致，避免「空密码框 + 强度条」并存的假状态
                passwordLength = 8,
                url = "https://example.com",
                notes = "预览用备注文本",
                isPasskey = false,
                customFields = listOf(
                    com.keepasskey.app.ui.model.UiCustomField(
                        id = "preview-field-1",
                        key = "预览自定义字段",
                        value = "预览值"
                    ),
                    com.keepasskey.app.ui.model.UiCustomField(
                        id = "preview-field-2",
                        key = "预览受保护字段",
                        value = "",
                        isProtected = true
                    )
                ),
                attachments = com.keepasskey.app.ui.preview.PreviewAttachments,
                tagsInput = "预览标签",
                autoTypeSequence = "{USERNAME}{TAB}{PASSWORD}{ENTER}",
                overrideUrl = "https://example.com/preview",
                isPasswordVisible = false,
                showGenerator = true,
                passLength = 20f,
                isDirty = true
            ),
            loadedPassword = "Passw0rd".toCharArray(),
            loadedTotpSecret = null,
            loadedProtectedFields = mapOf("preview-field-2" to "预览受保护字段值".toCharArray()),
            isDirty = true,
            snackbarHostState = previewSnackbar,
            onBackClick = {},
            onSaveClick = {},
            onGroupChange = { _ -> },
            onIconChange = { _ -> },
            customIconOptions = emptyList(),
            onSelectCustomIcon = { _ -> },
            onUploadCustomIcon = {},
            onTitleChange = { _ -> },
            onUsernameChange = { _ -> },
            onPasswordChangeSecure = { _ -> },
            onUrlChange = { _ -> },
            onNotesChange = { _ -> },
            onTogglePasskey = {},
            onTotpSecretChangeSecure = { _ -> },
            onUpdateProtectedFieldValue = { _, _ -> },
            onTagsInputChange = { _ -> },
            onAutoTypeSequenceChange = { _ -> },
            onOverrideUrlChange = { _ -> },
            onAddCustomField = {},
            onUpdateCustomField = { _, _, _, _ -> },
            onRemoveCustomField = { _ -> },
            onRemoveAttachment = { _ -> },
            onTogglePasswordVisibility = {},
            onToggleGenerator = {},
            onPassLengthChange = { _ -> },
            onGeneratePassword = {},
            onToggleUpper = {},
            onToggleLower = {},
            onToggleDigits = {},
            onToggleSymbols = {},
            onShowMessage = { _ -> },
            onPickAttachmentFile = {},
            onScanTotpQr = {}
        )
    }
}
