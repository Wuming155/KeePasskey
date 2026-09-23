package com.keepasskey.app.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.ui.navigation.AppNavigationMotion
import com.keepasskey.app.ui.theme.AppThemeMode

/**
 * KeePasskey 一级路由图（ISSUE-P3-29：自 `KeePasskeyApp.kt` 拆出，纯结构性拆分）。
 *
 * 逐条路由定义原样迁移；本扩展函数只接收路由构建所需的宿主上下文
 * （[navController] / [themeMode] / [toggleTheme] / [killAppAction] / [autoLockManager]），
 * 不持有任何状态，故可独立阅读与推理。二级设置页路由见 `keepasskeySettingsNavGraph`。
 *
 * `ISSUE-P3-183`：此处原接收**整个** `SettingsUiState`（100+ 字段）——本文件实际只用到其中的
 * `themeMode` 两处，而 `NavHost` 以 `remember(route, startDestination, builder)` 建图：
 * builder 捕获的状态若含不稳定字段（`SettingsUiState` 带 `List<String>`、未标 `@Immutable`），
 * 则任一**无关**偏好变化都会让 builder 成为新实例 ⇒ 整图 `createGraph` + `setGraph`，
 * 重建全部 `NavDestination`。收窄为 `AppThemeMode`（枚举，稳定）后，仅主题变化才会重建。
 *
 * §280：逐条 [composable] 注册体下沉同包 `KeePasskeyNavGraphRoutes.kt`，本门面只按序装配。
 */
@Suppress("LongParameterList")
internal fun NavGraphBuilder.keepasskeyNavGraph(
    navController: NavHostController,
    motion: AppNavigationMotion,
    themeMode: AppThemeMode,
    toggleTheme: () -> Unit,
    killAppAction: (() -> Unit)?,
    autoLockManager: AutoLockManager?
) {
    unlockRoute(navController, motion, themeMode, toggleTheme, autoLockManager)
    databasePickerRoute(navController)
    vaultListRoute(navController, motion, themeMode, toggleTheme, killAppAction, autoLockManager)
    authenticatorRoute(navController, motion)
    generatorRoute(motion)
    conflictResolverRoute(navController)
    entryDetailRoute(navController)
    entryEditRoute(navController)
    settingsHomeRoute(navController, motion, autoLockManager)

    // 7~15. 二级设置页路由（拆至 keepasskeySettingsNavGraph，ISSUE-P3-29）
    keepasskeySettingsNavGraph(navController)
}
