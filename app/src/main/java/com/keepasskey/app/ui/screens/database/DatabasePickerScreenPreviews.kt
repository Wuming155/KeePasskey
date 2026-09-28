package com.keepasskey.app.ui.screens.database

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.theme.KeePasskeyTheme

/**
 * 密码库选择页预览（`ISSUE-P2-354` 同批自 `DatabasePickerScreen.kt` 拆出——
 * 行数分档闸门 tier2 棘轮倒逼；预览函数名与内容逐字未改，IDE / 截图导出按函数名识别不受影响）。
 */

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "密码库选择内容 - 浅色", showBackground = true)
@Preview(name = "密码库选择内容 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun DatabasePickerContentPreview() {
    KeePasskeyTheme {

        DatabasePickerContent(
            uiState = DatabasePickerUiState(
                databases = listOf(
                    VaultDatabaseInfo(
                        id = "preview-db-local",
                        name = "预览本地密码库",
                        path = "/storage/emulated/0/Documents/preview.kdbx",
                        isRemote = false,
                        syncType = "本地",
                        lastOpenedAt = "2026-01-02 12:00",
                        fileSizeFormatted = "128.0 KB",
                        isActive = true
                    ),
                    VaultDatabaseInfo(
                        id = "preview-db-remote",
                        name = "预览云端密码库",
                        path = "https://dav.example.com/preview.kdbx",
                        isRemote = true,
                        syncType = "WebDAV",
                        lastOpenedAt = "2026-01-01 09:00",
                        fileSizeFormatted = "256.0 KB",
                        isActive = false
                    )
                ),
                isLoading = false,
                showCreateDialog = false,
                showOpenSourceDialog = false
            ),
            onBackClick = {},
            onSelectDatabase = { _ -> },
            onOpenCreateDialog = {},
            onCloseCreateDialog = {},
            onCreateDatabase = { _, _, _, _, _, _ -> },
            onOpenExistingClick = {},
            onCloseOpenSourceDialog = {},
            onImportFromSource = { _, _, _ -> },
            onRemoveDatabase = { _, _ -> },
            keyFileDelivery = KeyFileDeliveryState.PendingSave(
                suggestedFileName = "预览密钥文件.keyx"
            ),
            onSaveKeyFile = { _ -> },
            onKeyFileDeliveryDismissed = {}
        )
    }
}
