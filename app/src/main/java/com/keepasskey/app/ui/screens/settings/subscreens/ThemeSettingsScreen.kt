package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.ui.screens.settings.ListDensity
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.AppThemePalette

/**
 * 外观、显示与交互偏好二级设置页 (全面融合 KeePass2Android 显示定制与防肩窥设计)
 *
 * ISSUE-P3-31 批次 C：7 个分节已按**纯结构性拆分**搬至 `ThemeSettingsSections.kt`、
 * 展示组件搬至 `ThemeSettingsComponents.kt`；本文件仅保留 Scaffold 骨架与分节装配顺序，
 * `LazyColumn` 的 item 数量与顺序**逐条保持不变**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onThemeSelected: (AppThemeMode) -> Unit,
    onPaletteSelected: (AppThemePalette) -> Unit = {},
    // ISSUE-P3-263 AC③：动态取色置灰分区的「一步切回」动作（关闭动态取色，恢复品牌调色盘）
    onSwitchToBrandPalette: () -> Unit = {},
    onLanguageSelected: (AppLanguage) -> Unit = {},
    onOledOptimizationToggle: (Boolean) -> Unit,
    onDynamicColorToggle: (Boolean) -> Unit = {},
    onShowUsernameInList: (Boolean) -> Unit = {},
    onShowOtpInList: (Boolean) -> Unit = {},
    onShowPasskeyBadge: (Boolean) -> Unit = {},
    onShowUrlInList: (Boolean) -> Unit = {},
    onHideFabOnScrollToggle: (Boolean) -> Unit = {},
    onHapticFeedbackToggle: (Boolean) -> Unit = {},
    onShowAuthenticatorTabToggle: (Boolean) -> Unit = {},
    onShowGeneratorTabToggle: (Boolean) -> Unit = {},
    // KP2A 扩展显示操作
    onMaskPasswordsDefaultToggle: (Boolean) -> Unit = {},
    onMaskTotpDefaultToggle: (Boolean) -> Unit = {},
    onShowUnlockedNotificationToggle: (Boolean) -> Unit = {},
    onShowGroupInSearchResultToggle: (Boolean) -> Unit = {},
    onShowGroupInEntryToggle: (Boolean) -> Unit = {},
    onListDensitySelected: (ListDensity) -> Unit = {},
    onAutoActivateSearchOnOpenToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    SettingsSubscreenScaffold(
        titleRes = R.string.settings_theme,
        onBackClick = onBackClick,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            themeModeSection(
                uiState = uiState,
                onThemeSelected = onThemeSelected,
                onOledOptimizationToggle = onOledOptimizationToggle,
                onDynamicColorToggle = onDynamicColorToggle
            )

            themePaletteSection(
                uiState = uiState,
                onPaletteSelected = onPaletteSelected,
                onSwitchToBrandPalette = onSwitchToBrandPalette
            )

            themePeekSection(
                uiState = uiState,
                onMaskPasswordsDefaultToggle = onMaskPasswordsDefaultToggle,
                onMaskTotpDefaultToggle = onMaskTotpDefaultToggle
            )

            themeListSection(
                uiState = uiState,
                onListDensitySelected = onListDensitySelected,
                onShowUsernameInList = onShowUsernameInList,
                onShowOtpInList = onShowOtpInList,
                onShowPasskeyBadge = onShowPasskeyBadge,
                onShowUrlInList = onShowUrlInList,
                onHideFabOnScrollToggle = onHideFabOnScrollToggle,
                onHapticFeedbackToggle = onHapticFeedbackToggle,
                onShowAuthenticatorTabToggle = onShowAuthenticatorTabToggle,
                onShowGeneratorTabToggle = onShowGeneratorTabToggle
            )

            themeNavSearchSection(
                uiState = uiState,
                onShowUnlockedNotificationToggle = onShowUnlockedNotificationToggle,
                onAutoActivateSearchOnOpenToggle = onAutoActivateSearchOnOpenToggle,
                onShowGroupInSearchResultToggle = onShowGroupInSearchResultToggle,
                onShowGroupInEntryToggle = onShowGroupInEntryToggle
            )

            themeLanguageSection(
                uiState = uiState,
                onLanguageSelected = onLanguageSelected
            )

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "外观设置页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "外观设置页 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ThemeSettingsScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        ThemeSettingsScreen(
            uiState = com.keepasskey.app.ui.screens.settings.SettingsUiState(),
            onBackClick = {},
            onThemeSelected = {},
            onOledOptimizationToggle = {}
        )
    }
}
