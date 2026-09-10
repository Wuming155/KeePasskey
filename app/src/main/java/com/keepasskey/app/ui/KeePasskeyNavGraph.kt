package com.keepasskey.app.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.ui.navigation.Screen
import com.keepasskey.app.ui.screens.authenticator.AuthenticatorScreen
import com.keepasskey.app.ui.screens.conflict.ConflictResolutionScreen
import com.keepasskey.app.ui.screens.database.DatabasePickerScreen
import com.keepasskey.app.ui.screens.detail.EntryDetailScreen
import com.keepasskey.app.ui.screens.edit.EntryEditScreen
import com.keepasskey.app.ui.screens.generator.GeneratorScreen
import com.keepasskey.app.ui.screens.settings.SettingsScreen
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import com.keepasskey.app.ui.screens.unlock.UnlockScreen
import com.keepasskey.app.ui.screens.vault.VaultListScreen

/**
 * KeePasskey 一级路由图（ISSUE-P3-29：自 `KeePasskeyApp.kt` 拆出，纯结构性拆分）。
 *
 * 逐条路由定义原样迁移；本扩展函数只接收路由构建所需的宿主上下文
 * （[navController] / [appSettings] / [toggleTheme] / [killAppAction] / [autoLockManager]），
 * 不持有任何状态，故可独立阅读与推理。二级设置页路由见 `keepasskeySettingsNavGraph`。
 */
@Suppress("LongParameterList")
internal fun NavGraphBuilder.keepasskeyNavGraph(
    navController: NavHostController,
    appSettings: SettingsUiState,
    toggleTheme: () -> Unit,
    killAppAction: (() -> Unit)?,
    autoLockManager: AutoLockManager?
) {
    // 1. 登录与解锁页
    composable(Screen.Unlock.route) {
        UnlockScreen(
            currentTheme = appSettings.themeMode,
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
    composable(Screen.VaultList.route) {
        VaultListScreen(
            currentTheme = appSettings.themeMode,
            onThemeToggle = toggleTheme,
            onEntryClick = { entryId ->
                navController.navigate(Screen.EntryDetail.createRoute(entryId))
            },
            onAddEntryClick = { groupId ->
                navController.navigate(Screen.EntryEdit.createRoute(groupId = groupId))
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
    composable(Screen.Authenticator.route) {
        AuthenticatorScreen(
            onEntryClick = { entryId ->
                navController.navigate(Screen.EntryDetail.createRoute(entryId))
            }
        )
    }

    // 3.2 独立全功能密码生成器页
    composable(Screen.Generator.route) {
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
    composable(Screen.Settings.route) {
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
