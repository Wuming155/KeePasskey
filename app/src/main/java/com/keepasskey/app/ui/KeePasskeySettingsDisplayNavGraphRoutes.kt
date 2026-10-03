package com.keepasskey.app.ui

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.keepasskey.app.ui.navigation.Screen
import com.keepasskey.app.ui.screens.settings.SettingsViewModel
import com.keepasskey.app.ui.screens.settings.subscreens.InterfaceSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.ListDisplaySettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.ThemeSettingsScreen

/**
 * 「外观与显示」域二级设置页的逐条注册体（自 `KeePasskeySettingsNavGraphRoutes.kt` 拆出，**纯结构性搬迁**）。
 *
 * 拆出缘由：原文件承载全部二级页注册体，新增「界面偏好」页后越过 `tier2(400~500)` 棘轮预算
 * （`tools/doc/count_line_tiers.py`，只紧不松）。三项同属「应用长什么样」——
 * 主题（配色 / 语言 / 遮掩）、列表与导航（列表字段与搜索行为）、界面偏好（字段字体与动效），
 * 故按域整组外迁；函数体、回调接线与注释**逐字保留**，仅换文件。
 */

/** 11. 二级设置页面：外观与主题 (显示密度与敏感信息遮掩；ISSUE-P3-467 列表/导航偏好已拆至 settingsListDisplayRoute) */
internal fun NavGraphBuilder.settingsThemeRoute(navController: NavHostController) {
    composable(Screen.SettingsTheme.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
        ThemeSettingsScreen(
            uiState = settingsState,
            onBackClick = { navController.popBackStack() },
            onThemeSelected = settingsViewModel::setThemeMode,
            onPaletteSelected = settingsViewModel::setThemePalette,
            // ISSUE-P3-263 / PD-30（候选 A）：动态取色生效期间调色盘置灰，
            // 「一步切回」＝关闭动态取色即可恢复品牌调色盘（偏好值已落盘，无须另行选择）
            onSwitchToBrandPalette = { settingsViewModel.setDynamicColorEnabled(false) },
            // ISSUE-P3-441 AC①：自定义种子色（null = 清除；互斥写由仓库层原子事务兜底）
            onCustomSeedSelected = settingsViewModel::setCustomSeedColor,
            onLanguageSelected = settingsViewModel::setAppLanguage,
            onOledOptimizationToggle = settingsViewModel::setOledBlackOptimization,
            onDynamicColorToggle = settingsViewModel::setDynamicColorEnabled,
            onMaskPasswordsDefaultToggle = settingsViewModel::setMaskPasswordsDefault,
            onMaskTotpDefaultToggle = settingsViewModel::setMaskTotpDefault
        )
    }
}

/** 11a. 二级设置页面：列表与导航 (ISSUE-P3-467 自主题页纯迁位拆出——列表显示与搜索行为归位) */
internal fun NavGraphBuilder.settingsListDisplayRoute(navController: NavHostController) {
    composable(Screen.SettingsListDisplay.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
        ListDisplaySettingsScreen(
            uiState = settingsState,
            onBackClick = { navController.popBackStack() },
            onShowUsernameInList = settingsViewModel::setShowUsernameInList,
            onShowOtpInList = settingsViewModel::setShowOtpInList,
            onShowPasskeyBadge = settingsViewModel::setShowPasskeyBadge,
            onShowUrlInList = settingsViewModel::setShowUrlInList,
            onHideFabOnScrollToggle = settingsViewModel::setHideFabOnScroll,
            onHapticFeedbackToggle = settingsViewModel::setHapticFeedbackEnabled,
            // ISSUE-P3-443：底栏 Tab「显隐 + 排序」一体化
            onBottomNavOrderChange = settingsViewModel::setBottomNavOrder,
            onShowUnlockedNotificationToggle = settingsViewModel::setShowUnlockedNotification,
            onShowGroupInSearchResultToggle = settingsViewModel::setShowGroupInSearchResult,
            onShowGroupInEntryToggle = settingsViewModel::setShowGroupInEntry,
            onListDensitySelected = settingsViewModel::setListDensity,
            onAutoActivateSearchOnOpenToggle = settingsViewModel::setAutoActivateSearchOnOpen,
            onSearchMatchModeSelected = settingsViewModel::setSearchMatchMode
        )
    }
}

/**
 * 11b. 二级设置页面：界面偏好 (ISSUE-P3-444 修订)。
 *
 * 等宽字段字体与动效降级两项原以独立分组平铺在设置主页，本批收进「界面与显示」组的第三个二级入口；
 * 两个回调直取 [SettingsViewModel]（页面自身不再经 `SettingsActions` 中转）。
 */
internal fun NavGraphBuilder.settingsInterfaceRoute(navController: NavHostController) {
    composable(Screen.SettingsInterface.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
        InterfaceSettingsScreen(
            uiState = settingsState,
            onBackClick = { navController.popBackStack() },
            // 直取进阶偏好通道（不在 ViewModel 再包一层 setter；与主页原先把两开关上行时同一口径）
            onMonospaceFieldsToggle = { settingsViewModel.extendedPreferences.setMonospaceFieldsEnabled(it) },
            onReduceAnimationsToggle = { settingsViewModel.extendedPreferences.setReduceAnimations(it) }
        )
    }
}
