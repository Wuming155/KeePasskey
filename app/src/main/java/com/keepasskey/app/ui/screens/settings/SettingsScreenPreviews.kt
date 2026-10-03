package com.keepasskey.app.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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

/**
 * `ISSUE-P3-444` AC④：**系统字体缩放 200%** 下的设置主页——用于核对「界面偏好」入口与整页
 * 分组在最大档字号下不破版（结论与残余声明见 `PD-70`：本仓不建独立缩放偏好，如实适配系统缩放）。
 */
@Preview(name = "浅色模式-系统字号 200%", showBackground = true, fontScale = 2.0f)
@Preview(
    name = "深色模式-系统字号 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2.0f
)
@Composable
internal fun SettingsContentLargeFontScalePreview() {
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
 * `ISSUE-P3-444` AC①：等宽字体开关**关闭**态——密码 / 验证码回落默认字族。
 *
 * 该态在「设置页自身」看不出差别（开关是两态里的一态），必须单独画一行样例字段；
 * 与开关开启态（默认 `@Preview`，由全站既有预览承载）构成正反两态。
 */
@Preview(name = "等宽字体关闭-密码与验证码", showBackground = true)
@Preview(name = "等宽字体关闭-深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SettingsMonospaceFieldsDisabledPreview() {
    KeePasskeyTheme {
        androidx.compose.runtime.CompositionLocalProvider(
            com.keepasskey.app.ui.theme.LocalMonospaceFields provides false
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.padding(16.dp)
            ) {
                androidx.compose.material3.Text(
                    text = "Correct-Horse-Battery-9",
                    style = com.keepasskey.app.ui.theme.passwordFieldStyle(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                )
                androidx.compose.material3.Text(
                    text = "123 456",
                    style = com.keepasskey.app.ui.theme.totpFieldStyle(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
