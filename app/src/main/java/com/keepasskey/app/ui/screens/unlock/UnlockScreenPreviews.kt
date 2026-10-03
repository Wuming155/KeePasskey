package com.keepasskey.app.ui.screens.unlock

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
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

// ISSUE-P3-438：一次性轻提示态（上次会话未正常关闭）——预览画反向态，保证该态非真机不可见
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 会话未正常关闭提示 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 会话未正常关闭提示 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockContentAbnormalCloseNoticePreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        UnlockContent(
            uiState = UnlockUiState().copy(
                hasDatabase = true,
                databaseName = "Preview Vault.kdbx",
                databaseStatus = "Ready",
                unlockMode = UnlockMode.STANDARD,
                isQuickUnlockAvailable = true,
                lastSessionAbnormalCloseNotice = true
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

// ISSUE-P3-457：错误态 + 「已自动载入记住的密钥文件」info **同屏**——这正是真机走查拍到叠字的
// 组合（用户原话「自动加载密钥文件的提示文字和…取消重叠」；「取消」实为生物识别失败文案
// `sec_biometric_auth_failed` 的尾三字，非系统弹窗按钮）。该态此前**没有任何预览**，
// 于是「两行文案压在同一个 y 上」在编译期与预览导出图里全部隐形，只有真机肉眼能撞见。
// 补本态后，此槽的上下分离在预览图上直接可查（不变量由 check_box_slot_children.py 机检）。
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 错误与密钥文件 info 同屏 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 错误与密钥文件 info 同屏 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockContentErrorWithKeyFileInfoPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        UnlockContent(
            uiState = UnlockUiState().copy(
                hasDatabase = true,
                databaseName = "Preview Vault.kdbx",
                databaseStatus = "Ready",
                unlockMode = UnlockMode.STANDARD,
                isQuickUnlockAvailable = true,
                hasKeyFile = true,
                keyFileName = "usr.dat",
                // §433（ISSUE-P3-448 走查续）：新「来源」行只在确有来源时渲染——
                // 默认参数（null）的预览看不到它，故在此画真机同形的副本绝对路径（折叠态）
                keyFileSourcePath = "/data/user/0/com.keepasskey.debug/files/keyfiles/" +
                    "08f8c3ef4ce4b5ea548d5bc5c445cc8a28501e93342268c96a00105ee84fd7c9.kfc",
                errorMessage = UiMessage(R.string.sec_biometric_auth_failed),
                infoMessage = UiMessage(R.string.keyfile_restored_from_memory, listOf("usr.dat"))
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
