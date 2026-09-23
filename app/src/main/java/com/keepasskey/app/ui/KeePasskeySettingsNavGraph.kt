package com.keepasskey.app.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/**
 * 二级设置页路由图（ISSUE-P3-29：自 `KeePasskeyNavGraph.kt` 拆出，纯结构性拆分）。
 *
 * 逐条路由定义原样迁移；这些页面均为自包含的 `hiltViewModel<SettingsViewModel>()` 宿主，
 * 除 [navController] 外不依赖任何外层上下文。
 *
 * §280：逐条 [composable] 注册体下沉同包 `KeePasskeySettingsNavGraphRoutes.kt`，
 * 本门面只按序装配。
 */
internal fun NavGraphBuilder.keepasskeySettingsNavGraph(
    navController: NavHostController
) {
    settingsDatabaseRoute(navController)
    settingsSyncRoute(navController)
    settingsAutofillRoute(navController)
    settingsPrivilegedBrowsersRoute(navController)
    settingsSecurityRoute(navController)
    settingsThemeRoute(navController)
    settingsTotpRoute(navController)
    settingsHealthRoute(navController)
    settingsDebugRoute(navController)
    settingsAboutRoute(navController)
}
