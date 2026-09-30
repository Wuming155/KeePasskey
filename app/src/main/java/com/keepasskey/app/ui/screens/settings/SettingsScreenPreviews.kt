package com.keepasskey.app.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.keepasskey.app.ui.theme.KeePasskeyTheme

// P3-23：以下 Preview name 为 IDE 预览标注（仅开发期可见，非运行时 UI），保留原样
// ISSUE-P3-340 / 本批：预览与 Hub 本体分文件，避免 SettingsScreen 越入 tier2
@Preview(name = "浅色模式", showBackground = true)
@Preview(name = "深色模式", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SettingsContentPreview() {
    KeePasskeyTheme {
        SettingsContent(
            uiState = SettingsUiState(),
            onNavigateToDatabase = {},
            onNavigateToSync = {},
            onNavigateToAutofill = {},
            onNavigateToSecurity = {},
            onNavigateToTheme = {},
            onNavigateToHealth = {},
            onNavigateToTotp = {},
            onNavigateToDebug = {},
            onNavigateToAbout = {}
        )
    }
}

/**
 * `ISSUE-P3-340`：`showBackButton = true` 那一态此前从未被预览画过（默认 `false` 态才是）。
 * 单独开一个预览函数而不是在同一张图里叠两个整屏：整屏组件叠在一起会把各自的高度都压没，
 * 导出的 PNG 也就无从比对。
 */
@Preview(name = "浅色模式-带返回键", showBackground = true)
@Preview(name = "深色模式-带返回键", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SettingsContentWithBackButtonPreview() {
    KeePasskeyTheme {
        SettingsContent(
            uiState = SettingsUiState(),
            onNavigateToDatabase = {},
            onNavigateToSync = {},
            onNavigateToAutofill = {},
            onNavigateToSecurity = {},
            onNavigateToTheme = {},
            onNavigateToHealth = {},
            onNavigateToTotp = {},
            onNavigateToDebug = {},
            onNavigateToAbout = {},
            showBackButton = true
        )
    }
}
