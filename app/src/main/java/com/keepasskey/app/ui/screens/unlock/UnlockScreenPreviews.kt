package com.keepasskey.app.ui.screens.unlock

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import com.keepasskey.app.ui.theme.AppThemeMode

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// （§392 自 UnlockScreen.kt 逐字搬移：该文件因 ISSUE-P3-427 新增一枚导航回调越入 400~500 档，
//  按 §391 同一范式把预览拆出降档，预览内容零改动）
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockContentPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        UnlockContent(
            uiState = UnlockUiState().copy(
                hasDatabase = true,
                databaseName = "Preview Vault.kdbx",
                databaseStatus = "Ready",
                unlockMode = UnlockMode.STANDARD,
                isQuickUnlockAvailable = true
            ),
            currentTheme = AppThemeMode.SYSTEM,
            onThemeToggle = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onSelectKeyFile = {},
            onClearKeyFile = {},
            onToggleReadOnly = {},
            onSwitchMode = {},
            onUnlock = {},
            onBiometricUnlock = {},
            onDowngradeDecision = {},
            onNavigateToDatabasePicker = {},
            onOpenExistingVault = {}
        )
    }
}
