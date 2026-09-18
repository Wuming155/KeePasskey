package com.keepasskey.app.ui

import android.content.res.Configuration
import android.content.Context
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.keepasskey.app.MainApplication
import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.security.AutoLockManager
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
 *
 * **@Preview 标注说明**：本文件仅含 [KeePasskeyApp] 一个 Composable，它经 `hiltViewModel()` 取
 * SettingsViewModel、依赖 `ComponentActivity` 宿主与完整导航图，且 `keepasskeyNavGraph` 的各路由
 * 入口同样有状态/注入依赖，无法在 Preview 面板独立渲染，故按约定**不添加**预览标注。
 */
@Composable
fun KeePasskeyApp() {
    val context = LocalContext.current
    // 宿主必为 ComponentActivity（本 Composable 仅由 MainActivity 承载），但**先**按可空承接：
    // ISSUE-P3-17 要求「宿主不可终止时如实不呈现入口」，故必须保留可空判定而非硬转。
    // 声明顺序有意如此——若先做非空硬转，`context` 会被智能转换为 ComponentActivity，
    // 使此处的 `as?` 变为冗余转换（编译器告警）。
    val hostActivity = context as? ComponentActivity
    val settingsViewModel: SettingsViewModel = hiltViewModel(context as ComponentActivity)
    val appSettings by settingsViewModel.uiState.collectAsStateWithLifecycle()

    // ISSUE-P3-17：showKillAppOption 开启且宿主 Activity 可终止时，才向库列表下发「彻底退出应用」
    // 入口（不可终止时如实不呈现，不做点了没反应的假入口）。
    // 终止动作由本层持有 Activity 上下文执行：finishAffinity() 解除任务栈亲和性后终止进程。
    val killAppAction: (() -> Unit)? = remember(appSettings.showKillAppOption, hostActivity) {
        buildKillAppAction(
            context = context,
            hostActivity = hostActivity,
            showKillAppOption = appSettings.showKillAppOption
        )
    }

    // 动态国际化语言支持：默认中文，支持跟随系统、强制中文与英文热切换
    val appLanguage = appSettings.appLanguage
    val targetLocale = remember(appLanguage) { localeFor(appLanguage) }

    val localizedConfiguration = remember(targetLocale, context) {
        localizedConfigurationOf(context.resources.configuration, targetLocale)
    }

    val localizedContext = remember(targetLocale, context) {
        localizedContextOf(context, context.resources.configuration, targetLocale)
    }

    // 主题三态循环切换方法
    val toggleTheme: () -> Unit =
        { settingsViewModel.setThemeMode(nextThemeMode(appSettings.themeMode)) }

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

            val autoLockManager = (context as? com.keepasskey.app.MainActivity)?.autoLockManager

            // 三处全局导航守卫与副作用（组合位置仍在 Scaffold 之前，BackHandler 优先级语义不变）
            AppShellNavigationEffects(
                navController = navController,
                currentRoute = currentRoute,
                autoLockManager = autoLockManager,
                lockWhenNavigateBack = appSettings.lockWhenNavigateBack,
                showAuthenticatorTab = appSettings.showAuthenticatorTab,
                showGeneratorTab = appSettings.showGeneratorTab
            )

            AppShellScaffold(
                navController = navController,
                currentRoute = currentRoute,
                showBottomBar = showBottomBar,
                visibleNavItems = visibleNavItems,
                themeMode = appSettings.themeMode,
                toggleTheme = toggleTheme,
                killAppAction = killAppAction,
                autoLockManager = autoLockManager
            )


        }
    }
}

/**
 * ISSUE-P3-17 + P3-116：仅在「偏好开启且宿主可终止」时构造「彻底退出应用」动作，否则 null（不呈现入口）。
 *
 * §185 自 [KeePasskeyApp] **逐字搬移**为普通函数（它是 `remember { … }` 的取值体）。刻意留在同一文件：
 * `AppTerminationPolicyTest` 的接线判据（`purgeCaches = {` / `purgeVolatileCachesBeforeExit()`）
 * 锚在本文件文本上，不外迁即无需扩扫并集（见 `ACTIVE_ISSUES` 剩余清单第 3 项）。
 */
private fun buildKillAppAction(
    context: Context,
    hostActivity: ComponentActivity?,
    showKillAppOption: Boolean
): (() -> Unit)? {
    if (!AppTerminationPolicy.showsEntry(showKillAppOption, hostActivity != null)) {
        return null
    }
    return {
        AppTerminationPolicy.terminate(
            detachTask = { hostActivity?.finishAffinity() },
            // ISSUE-P3-116：退出前清理易失缓存（`cacheDir/attachments` 附件明文 +
            // `cacheDir/sync` 密文快照）。取 Application 单例上的统一入口，避免此处
            // 直接依赖 Hilt 图（Composable 内无法字段注入）；清理为同步 best-effort，
            // 失败只落日志、不阻断退出。
            purgeCaches = {
                (context.applicationContext as? MainApplication)?.purgeVolatileCachesBeforeExit()
            },
            exitProcess = { code -> kotlin.system.exitProcess(code) }
        )
    }
}

/** 语言偏好 → [Locale]；[AppLanguage.SYSTEM] 返回 null 表示「跟随系统、不改写配置」（§185 下沉，可 JVM 单测） */
internal fun localeFor(appLanguage: AppLanguage): Locale? = when (appLanguage) {
    AppLanguage.ZH_CN -> Locale.SIMPLIFIED_CHINESE
    AppLanguage.EN_US -> Locale.ENGLISH
    AppLanguage.SYSTEM -> null
}

/** 主题三态循环 Light → Dark → System → Light（§185 下沉为纯函数，可 JVM 单测） */
internal fun nextThemeMode(themeMode: AppThemeMode): AppThemeMode = when (themeMode) {
    AppThemeMode.LIGHT -> AppThemeMode.DARK
    AppThemeMode.DARK -> AppThemeMode.SYSTEM
    AppThemeMode.SYSTEM -> AppThemeMode.LIGHT
}

/**
 * 用户关闭正在浏览的 Tab 时应回落的路由（§185 下沉为纯函数，可 JVM 单测）。
 *
 * 仅当「当前正停在该 Tab 且该 Tab 已被隐藏」时返回密码库路由，否则 null（不导航）。
 */
internal fun hiddenTabRedirectRoute(
    currentRoute: String?,
    showAuthenticatorTab: Boolean,
    showGeneratorTab: Boolean
): String? = when {
    currentRoute == Screen.Authenticator.route && !showAuthenticatorTab -> Screen.VaultList.route
    currentRoute == Screen.Generator.route && !showGeneratorTab -> Screen.VaultList.route
    else -> null
}

/** 按 [locale] 改写 [base] 的语言与布局方向；`null` 时返回原样副本（§185：原先两处重复逻辑并为一处） */
private fun localizedConfigurationOf(base: Configuration, locale: Locale?): Configuration {
    val cfg = Configuration(base)
    if (locale != null) {
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
    }
    return cfg
}

/** 供 `LocalContext` 使用的本地化上下文；`null` 语言时退回原上下文 */
private fun localizedContextOf(
    context: Context,
    base: Configuration,
    locale: Locale?
): Context = if (locale == null) {
    context
} else {
    context.createConfigurationContext(localizedConfigurationOf(base, locale))
}

/**
 * 顶层 Tab 切换（§185 下沉为 `NavHostController` 扩展）：仅在目标与当前不同时导航，
 * 并保存 / 恢复各 Tab 的后栈状态。
 *
 * **与 Tab 被隐藏时的回落导航不同**：那条用 `popUpTo(start) { inclusive = false }` 且不保存状态，
 * 保持原样，见 [KeePasskeyApp] 内的 `hiddenTabRedirectRoute` 调用点。
 */
private fun NavHostController.navigateToTopLevel(targetRoute: String, currentRoute: String?) {
    if (targetRoute != currentRoute) {
        navigate(targetRoute) {
            popUpTo(graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/**
 * 外壳层的三处全局导航守卫与副作用（§185 自 [KeePasskeyApp] **逐字搬移**）：
 * 被隐藏 Tab 的平滑回落、ISSUE-P2-21 的返回键锁定、ISSUE-P3-67 的「锁库事件读实时路由」守卫。
 *
 * **组合位置刻意不变**：仍在 `Scaffold` / `NavHost` 之前组合——`BackHandler` 的优先级依赖
 * 「先于 NavHost 组合，使库列表内部的返回处理优先消费」，挪动顺序即改变语义。
 */
@Composable
private fun AppShellNavigationEffects(
    navController: NavHostController,
    currentRoute: String?,
    autoLockManager: AutoLockManager?,
    lockWhenNavigateBack: Boolean,
    showAuthenticatorTab: Boolean,
    showGeneratorTab: Boolean
) {
    // 若用户在设置中关闭了当前正在浏览的 Tab，平滑重定向回密码库
    LaunchedEffect(currentRoute, showAuthenticatorTab, showGeneratorTab) {
        hiddenTabRedirectRoute(
            currentRoute = currentRoute,
            showAuthenticatorTab = showAuthenticatorTab,
            showGeneratorTab = showGeneratorTab
        )?.let { fallback ->
            navController.navigate(fallback) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // ISSUE-P2-21：接线「返回键锁定」——主页（顶层路由）按返回键立即锁库。
    // 本 BackHandler 先于 NavHost 组合：库列表内部的批量选择/搜索/子目录返回
    // （后组合，优先级更高）仍先行消费；仅在内部无人处理且处于顶层路由时，
    // 由本层熔断会话，锁库事件经 lockEvents 导航至解锁页。
    val backLockEnabled = lockWhenNavigateBack &&
        autoLockManager != null &&
        BottomNavItem.isTopLevelRoute(currentRoute)
    BackHandler(enabled = backLockEnabled) {
        autoLockManager?.triggerLock("返回键锁定")
    }

    // ISSUE-P3-67：守卫必须读取**实时**路由——effect 以恒定的 autoLockManager 为 key，
    // 闭包捕获的 currentRoute 不会随导航更新（此前恒捕获 null，守卫恒真，
    // 已在解锁页时收到锁库事件仍会 popUpTo(0) 重建页面并清空已输入的主密码）。
    // rememberUpdatedState 让 collect 每次读到最新路由，守卫恢复真实语义。
    val latestRoute by rememberUpdatedState(currentRoute)
    LaunchedEffect(autoLockManager) {
        autoLockManager?.lockEvents?.collect {
            if (latestRoute != Screen.Unlock.route) {
                navController.navigate(Screen.Unlock.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }
}

/**
 * 外壳 UI 装配（§185 自 [KeePasskeyApp] **逐字搬移**）：窄屏用底栏、宽屏用侧栏，
 * 中央 `NavHost` 交由同包 `keepasskeyNavGraph` 注册路由。
 *
 * 宽屏判定改在本组件内读 `LocalConfiguration`（与原先在壳体内读同一 CompositionLocal、
 * 阈值为 600dp 完全一致）。
 */
@Composable
private fun AppShellScaffold(
    navController: NavHostController,
    currentRoute: String?,
    showBottomBar: Boolean,
    visibleNavItems: List<BottomNavItem>,
    themeMode: AppThemeMode,
    toggleTheme: () -> Unit,
    killAppAction: (() -> Unit)?,
    autoLockManager: AutoLockManager?
) {
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= 600
    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            if (showBottomBar && !isWideScreen) {
                AppBottomBar(
                    currentRoute = currentRoute,
                    visibleItems = visibleNavItems,
                    onNavigateToRoute = { route ->
                        navController.navigateToTopLevel(route, currentRoute)
                    }
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
                    onNavigateToRoute = { route ->
                        navController.navigateToTopLevel(route, currentRoute)
                    }
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
                        themeMode = themeMode,
                        toggleTheme = toggleTheme,
                        killAppAction = killAppAction,
                        autoLockManager = autoLockManager
                    )
                }
            }
        }
    }
}
