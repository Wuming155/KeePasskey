package com.keepasskey.app.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.ui.navigation.AppNavigationMotion
import com.keepasskey.app.ui.navigation.Screen
import com.keepasskey.app.ui.screens.authenticator.AuthenticatorScreen
import com.keepasskey.app.ui.screens.conflict.ConflictResolutionScreen
import com.keepasskey.app.ui.screens.database.DatabasePickerScreen
import com.keepasskey.app.ui.screens.detail.EntryDetailScreen
import com.keepasskey.app.ui.screens.edit.EntryEditScreen
import com.keepasskey.app.ui.screens.generator.GeneratorScreen
import com.keepasskey.app.ui.screens.settings.SettingsScreen
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.screens.unlock.UnlockScreen
import com.keepasskey.app.ui.screens.vault.VaultListScreen

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
    // 1. 登录与解锁页
    composable(
        route = Screen.Unlock.route,
        enterTransition = motion.topLevelEnterTransition,
        exitTransition = motion.topLevelExitTransition,
        popEnterTransition = motion.topLevelEnterTransition,
        popExitTransition = motion.topLevelExitTransition
    ) {
        UnlockScreen(
            currentTheme = themeMode,
            onThemeToggle = toggleTheme,
            onUnlockSuccess = {
                autoLockManager?.onUnlockSuccess()
                navController.navigate(Screen.VaultList.route) {
                    popUpTo(Screen.Unlock.route) { inclusive = true }
                }
            },
            onNavigateToDatabasePicker = {
                navController.navigate(Screen.DatabasePicker.route)
            }
        )
    }

    // 2. 密码库选择与多库管理页
    composable(Screen.DatabasePicker.route) {
        DatabasePickerScreen(
            onBackClick = { navController.popBackStack() },
            onDatabaseSelected = {
                navController.popBackStack()
            }
        )
    }

    // 3. 主密码库列表页
    // ISSUE-P2-260 AC④ / ISSUE-P3-261 偏差表第 6 项：顶层 Tab 路由的 `popEnterTransition` 例外
    // 配置**保留**——`navigateToTopLevel` 修复后同级切换不再走 pop 语义，该 slot 的唯一命中路径
    // 是「下钻页（详情 / 编辑）返回本页」，此时共享轴的视差还原才是正确语义（而非同级淡入）。
    // 原 `popExitTransition = topLevelExitTransition` 则是不可达的例外：顶层路由的退出只发生在
    // `navigateToTopLevel` 内部的 pop（同一次 `navigate` 内完成，`isPop` 收尾为 false，走
    // `enterTransition` / `exitTransition`），若它在未来被命中，用「同级淡出」渲染一次层级弹出
    // 反而是错的 ⇒ 删除，继承 `NavHost` 层的层级默认值。
    composable(
        route = Screen.VaultList.route,
        enterTransition = motion.topLevelEnterTransition,
        exitTransition = motion.topLevelExitTransition,
        popEnterTransition = motion.defaultPopEnterTransition
    ) {
        VaultListScreen(
            currentTheme = themeMode,
            onThemeToggle = toggleTheme,
            onEntryClick = { entryId ->
                navController.navigate(Screen.EntryDetail.createRoute(entryId))
            },
            onAddEntryClick = { groupId ->
                navController.navigate(Screen.EntryEdit.createRoute(groupId = groupId))
            },
            // ISSUE-P3-51：从模板新建——携带模板 id 进入编辑页，由状态层预填为**新条目**
            onAddFromTemplateClick = { groupId, templateId ->
                navController.navigate(Screen.EntryEdit.createRoute(groupId = groupId, templateId = templateId))
            },
            onLockClick = {
                // P3-23：锁定原因仅供 AutoLockManager 内部 debugLog 留痕（非用户可见），保留原样
                autoLockManager?.triggerLock("用户手动点击锁定")
                navController.navigate(Screen.Unlock.route) {
                    popUpTo(0) { inclusive = true }
                }
            },
            // H2 整改：冲突解决死路由接线——冲突横幅可直接进入冲突解决页
            onNavigateToConflictResolver = {
                navController.navigate(Screen.ConflictResolver.route)
            },
            // ISSUE-P3-17：showKillAppOption 真实消费点（详见 killAppAction 注释）
            onKillApp = killAppAction
        )
    }

    // 3.1 独立双重认证验证码 (TOTP) 管理页
    composable(
        route = Screen.Authenticator.route,
        enterTransition = motion.topLevelEnterTransition,
        exitTransition = motion.topLevelExitTransition,
        popEnterTransition = motion.defaultPopEnterTransition
    ) {
        AuthenticatorScreen(
            onEntryClick = { entryId ->
                navController.navigate(Screen.EntryDetail.createRoute(entryId))
            }
        )
    }

    // 3.2 独立全功能密码生成器页
    composable(
        route = Screen.Generator.route,
        enterTransition = motion.topLevelEnterTransition,
        exitTransition = motion.topLevelExitTransition,
        popEnterTransition = motion.defaultPopEnterTransition
    ) {
        GeneratorScreen()
    }

    // 3.3 云端同步冲突解决双栏合并页
    composable(Screen.ConflictResolver.route) {
        ConflictResolutionScreen(
            onBackClick = { navController.popBackStack() },
            onResolveSuccess = { navController.popBackStack() }
        )
    }

    // 4. 密码详情页
    composable(
        route = Screen.EntryDetail.route,
        arguments = listOf(
            navArgument("entryId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        val entryId = backStackEntry.arguments?.getString("entryId")
        // entryId 可能为空或无效，由详情页展示"未找到凭据"空状态
        EntryDetailScreen(
            entryId = entryId,
            onBackClick = { navController.popBackStack() },
            onEditClick = { id ->
                navController.navigate(Screen.EntryEdit.createRoute(id))
            }
        )
    }

    // 5. 添加/编辑条目页
    composable(
        route = Screen.EntryEdit.route,
        arguments = listOf(
            navArgument("entryId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("groupId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("templateId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        val entryId = backStackEntry.arguments?.getString("entryId")
        EntryEditScreen(
            entryId = entryId,
            onBackClick = { navController.popBackStack() },
            onSaveSuccess = { navController.popBackStack() }
        )
    }

    // 6. 设置主页（作为一级标签页展示）
    // ISSUE-P3-261 AC② / 偏差表第 8 项：`Screen.Settings` 同为底栏顶层 Tab
    // （`AppBottomBar` / `BottomNavItem.SETTINGS`）却缺席 `topLevel*` 覆盖，切换时走的是
    // 「旧页 150ms 淡出 + 新页 300ms 滑入」，与其余三个 Tab 不同族。现补齐为同族的 fade through；
    // 其 `popEnterTransition` 同样保留为共享轴还原（自二级设置页返回）。
    composable(
        route = Screen.Settings.route,
        enterTransition = motion.topLevelEnterTransition,
        exitTransition = motion.topLevelExitTransition,
        popEnterTransition = motion.defaultPopEnterTransition
    ) {
        SettingsScreen(
            onNavigateToDatabase = { navController.navigate(Screen.SettingsDatabase.route) },
            onNavigateToSync = { navController.navigate(Screen.SettingsSync.route) },
            onNavigateToAutofill = { navController.navigate(Screen.SettingsAutofill.route) },
            onNavigateToSecurity = { navController.navigate(Screen.SettingsSecurity.route) },
            onNavigateToTheme = { navController.navigate(Screen.SettingsTheme.route) },
            onNavigateToHealth = { navController.navigate(Screen.SettingsHealth.route) },
            onNavigateToTotp = { navController.navigate(Screen.SettingsTotp.route) },
            onNavigateToDebug = { navController.navigate(Screen.SettingsDebug.route) },
            onNavigateToAbout = { navController.navigate(Screen.SettingsAbout.route) },
            onLockClick = {
                // P3-23：同上，锁定原因仅供内部 debugLog 留痕，保留原样
                autoLockManager?.triggerLock("用户从设置界面手动点击锁定")
                navController.navigate(Screen.Unlock.route) {
                    popUpTo(0) { inclusive = true }
                }
            },
            showBackButton = false
        )
    }

    // 7~15. 二级设置页路由（拆至 keepasskeySettingsNavGraph，ISSUE-P3-29）
    keepasskeySettingsNavGraph(navController)
}
