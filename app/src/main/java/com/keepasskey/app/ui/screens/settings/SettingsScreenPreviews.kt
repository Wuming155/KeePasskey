package com.keepasskey.app.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.keepasskey.app.ui.theme.KeePasskeyTheme

/**
 * 设置主页整屏预览（ISSUE-P3-467 自 `SettingsScreen.kt` 纯结构性迁出——
 * 主文件因新增「数据导入与导出」「列表与导航」两个入口行逼近 tier2 棘轮预算，
 * 预览面下沉独立文件，主文件回归 tier3；预览覆盖口径不变，含 `showBackButton=true` 态）。
 */

// P3-23：以下 Preview name 为 IDE 预览标注（仅开发期可见，非运行时 UI），保留原样
@Preview(name = "浅色模式", showBackground = true)
@Preview(name = "深色模式", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SettingsContentPreview() {
    KeePasskeyTheme {
        SettingsContent(
            uiState = SettingsUiState(),
            onNavigateToDatabase = {},
            onNavigateToImportExport = {},
            onNavigateToSync = {},
            onNavigateToAutofill = {},
            onNavigateToPasskey = {},
            onNavigateToSecurity = {},
            onNavigateToTheme = {},
            onNavigateToListNav = {},
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
internal fun SettingsContentWithBackPreview() {
    KeePasskeyTheme {
        SettingsContent(
            uiState = SettingsUiState(),
            onNavigateToDatabase = {},
            onNavigateToImportExport = {},
            onNavigateToSync = {},
            onNavigateToAutofill = {},
            onNavigateToPasskey = {},
            onNavigateToSecurity = {},
            onNavigateToTheme = {},
            onNavigateToListNav = {},
            onNavigateToHealth = {},
            onNavigateToTotp = {},
            onNavigateToDebug = {},
            onNavigateToAbout = {},
            showBackButton = true
        )
    }
}
