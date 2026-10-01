package com.keepasskey.app.ui.screens.database

import android.content.res.Configuration
import androidx.compose.runtime.Composable

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// （§391 自 CreateVaultWizardDialog.kt 逐字搬移，本体行数分档闸门所需）
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview(name = "新建密码库向导 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "新建密码库向导 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun CreateVaultWizardDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        CreateVaultWizardDialog(
            onDismiss = {},
            onConfirm = { _, _, _, _, _, _ -> }
        )
    }
}

/**
 * `ISSUE-P2-354 AC①`：`isBusy = true` 那一态（默认态预览只画 `false`）——
 * busy 的差异恰在按钮禁用 / 内嵌进度 / 取消置灰，不补态就永远看不见。
 * 单独开一个预览函数，不在同一张图里叠两个整屏。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview(name = "新建密码库向导 - 建库进行中", showBackground = true)
@Composable
internal fun CreateVaultWizardDialogBusyPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        CreateVaultWizardDialog(
            onDismiss = {},
            onConfirm = { _, _, _, _, _, _ -> },
            isBusy = true
        )
    }
}
