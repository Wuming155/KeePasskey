package com.keepasskey.app.ui.screens.detail

import android.content.res.Configuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview

/**
 * `EntryDetailScreen.kt` 的 IDE 预览夹具（`ISSUE-P3-188` §169 自母文件**归位**到本包预览文件，
 * 与 `EntryEditFormSectionsPreviews.kt` 同形态）。函数名、包名与两个 `@Preview` 标注逐字未改
 * ⇒ 截图导出任务（`tools/export_previews/`）的发现结果不受影响。
 */

/**
 * IDE 预览专用状态装载：仅在组合首帧把示例状态写入 remember 状态，绕开
 * `remember(…) { mutableStateOf(示例) }` 的「非 Composable 上下文求值」静态检查。
 */
@Composable
private fun <T> previewStateOf(value: T): androidx.compose.runtime.MutableState<T> {
    val state = remember { mutableStateOf(value) }
    LaunchedEffect(Unit) { state.value = value }
    return state
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "凭据详情内容 - 浅色", showBackground = true)
@Preview(name = "凭据详情内容 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun EntryDetailContentPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        val previewUiState = previewStateOf(
            com.keepasskey.app.ui.screens.detail.EntryDetailUiState(
                entry = com.keepasskey.app.ui.preview.PreviewEntryLogin,
                isFavorite = true,
                groupPath = "网站登录 / 预览分组路径",
                totpRemainingSeconds = 18,
                passwordStrengthBits = 72,
                isTotpVisible = true,
                allGroups = com.keepasskey.app.ui.preview.PreviewGroups
            )
        ).value
        val previewSnackbar = remember { SnackbarHostState() }

        EntryDetailContent(
            uiState = previewUiState,
            snackbarHostState = previewSnackbar,
            onBackClick = {},
            onEditClick = {},
            onToggleFavorite = {},
            onDuplicateEntry = {},
            onToggleAutofillBlock = {},
            onDeleteCustomIcon = {},
            onDeleteEntry = {},
            onMoveEntry = { _ -> },
            onTogglePasswordVisibility = {},
            onToggleTotpVisibility = {},
            onAdvanceHotp = {},
            onCopyTotp = {},
            onToggleCustomFieldVisibility = { _ -> },
            onCopyCustomField = { _, _ -> },
            onExportAttachment = { _ -> },
            onRollbackRevision = { _ -> },
            onPrepareRevisionDiff = { _ -> },
            onClearRevisionDiff = {},
            onShowMessage = { _ -> },
            onCopyPassword = { _ -> },
            onCopyUsername = { _, _ -> }
        )
    }
}
