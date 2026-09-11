package com.keepasskey.app.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.ui.components.AppBottomBar
import com.keepasskey.app.ui.components.AppNavigationRail
import com.keepasskey.app.ui.components.BottomNavItem
import com.keepasskey.app.ui.navigation.Screen
import com.keepasskey.app.ui.screens.settings.SettingsViewModel
import com.keepasskey.app.ui.screens.vault.AppTerminationPolicy
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import java.util.Locale

/**
 * KeePasskey 界面总入口与全局路由宿主。
 *
 * ISSUE-P3-29：各路由定义已拆至同包 `KeePasskeyNavGraph.kt`（`keepasskeyNavGraph` 扩展函数），
 * 本文件只保留主题 / 语言 / 外层 Scaffold 与 NavHost 装配，为纯结构性拆分。
 */
@Composable
fun KeePasskeyApp() {
    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = hiltViewModel(context as ComponentActivity)
    val appSettings by settingsViewModel.uiState.collectAsStateWithLifecycle()

    // ISSUE-P3-17：showKillAppOption 开启且宿主 Activity 可终止时，才向库列表下发「彻底退出应用」
    // 入口（不可终止时如实不呈现，不做点了没反应的假入口）。
    // 终止动作由本层持有 Activity 上下文执行：finishAffinity() 解除任务栈亲和性后终止进程。
    val hostActivity = context as? ComponentActivity
    val killAppAction: (() -> Unit)? = remember(appSettings.showKillAppOption, hostActivity) {
        if (AppTerminationPolicy.showsEntry(appSettings.showKillAppOption, hostActivity != null)) {
            val action: () -> Unit = {
                AppTerminationPolicy.terminate(
                    detachTask = { hostActivity?.finishAffinity() },
                    exitProcess = { code -> kotlin.system.exitProcess(code) }
                )
            }
            action
        } else {
            null
        }
    }

    // 动态国际化语言支持：默认中文，支持跟随系统、强制中文与英文热切换
    val appLanguage = appSettings.appLanguage
    val targetLocale = remember(appLanguage) {
        when (appLanguage) {
            AppLanguage.ZH_CN -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.EN_US -> Locale.ENGLISH
            AppLanguage.SYSTEM -> null
        }
    }

    val localizedConfiguration = remember(targetLocale, context) {
        val cfg = Configuration(context.resources.configuration)
        if (targetLocale != null) {
            cfg.setLocale(targetLocale)
            cfg.setLayoutDirection(targetLocale)
        }
        cfg
    }

    val localizedContext = remember(targetLocale, context) {
        if (targetLocale != null) {
            val cfg = Configuration(context.resources.configuration).apply {
                setLocale(targetLocale)
                setLayoutDirection(targetLocale)
            }
            context.createConfigurationContext(cfg)
        } else {
            context
        }
    }

    // 主题三态循环切换方法
    val toggleTheme: () -> Unit = {
        settingsViewModel.setThemeMode(
            when (appSettings.themeMode) {
                AppThemeMode.LIGHT -> AppThemeMode.DARK
                AppThemeMode.DARK -> AppThemeMode.SYSTEM
                AppThemeMode.SYSTEM -> AppThemeMode.LIGHT
            }
        )
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration
    ) {
        KeePasskeyTheme(
            themeMode = appSettings.themeMode,
            themePalette = appSettings.themePalette,
            oledBlack = appSettings.oledBlackOptimization,
            dynamicColorEnabled = appSettings.dynamicColorEnabled
        ) {
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route
            val showBottomBar = BottomNavItem.isTopLevelRoute(currentRoute)

            val visibleNavItems = remember(appSettings.showAuthenticatorTab, appSettings.showGeneratorTab) {
                BottomNavItem.getVisibleItems(
                    showAuthenticator = appSettings.showAuthenticatorTab,
                    showGenerator = appSettings.showGeneratorTab
                )
            }

            // 若用户在设置中关闭了当前正在浏览的 Tab，平滑重定向回密码库
            LaunchedEffect(currentRoute, appSettings.showAuthenticatorTab, appSettings.showGeneratorTab) {
                if (currentRoute == Screen.Authenticator.route && !appSettings.showAuthenticatorTab) {
                    navController.navigate(Screen.VaultList.route) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                        launchSingleTop = true
                    }
                } else if (currentRoute == Screen.Generator.route && !appSettings.showGeneratorTab) {
                    navController.navigate(Screen.VaultList.route) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }

            val configuration = LocalConfiguration.current
            val isWideScreen = configuration.screenWidthDp >= 600
            val autoLockManager = (context as? com.keepasskey.app.MainActivity)?.autoLockManager

            // ISSUE-P2-21：接线「返回键锁定」——主页（顶层路由）按返回键立即锁库。
            // 本 BackHandler 先于 NavHost 组合：库列表内部的批量选择/搜索/子目录返回
            // （后组合，优先级更高）仍先行消费；仅在内部无人处理且处于顶层路由时，
            // 由本层熔断会话，锁库事件经 lockEvents 导航至解锁页。
            val backLockEnabled = appSettings.lockWhenNavigateBack &&
                autoLockManager != null &&
                BottomNavItem.isTopLevelRoute(currentRoute)
            BackHandler(enabled = backLockEnabled) {
                autoLockManager?.triggerLock("返回键锁定")
            }

            LaunchedEffect(autoLockManager) {
                autoLockManager?.lockEvents?.collect {
                    if (currentRoute != Screen.Unlock.route) {
                        navController.navigate(Screen.Unlock.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            }

            val navigateToTopLevel: (String) -> Unit = { targetRoute ->
                if (targetRoute != currentRoute) {
                    navController.navigate(targetRoute) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }

            Scaffold(
                modifier = Modifier.imePadding(),
                bottomBar = {
                    if (showBottomBar && !isWideScreen) {
                        AppBottomBar(
                            currentRoute = currentRoute,
                            visibleItems = visibleNavItems,
                            onNavigateToRoute = navigateToTopLevel
                        )
                    }
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding())
                ) {
                    if (showBottomBar && isWideScreen) {
                        AppNavigationRail(
                            currentRoute = currentRoute,
                            visibleItems = visibleNavItems,
                            onNavigateToRoute = navigateToTopLevel
                        )
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Unlock.route,
                            modifier = Modifier.fillMaxSize(),
                            enterTransition = { fadeIn() },
                            exitTransition = { fadeOut() }
                        ) {
                            keepasskeyNavGraph(
                                navController = navController,
                                appSettings = appSettings,
                                toggleTheme = toggleTheme,
                                killAppAction = killAppAction,
                                autoLockManager = autoLockManager
                            )
                        }
                    }
                }
            }
        }
    }
}
