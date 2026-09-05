package com.keepasskey.app.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.ui.components.AppBottomBar
import com.keepasskey.app.ui.components.AppNavigationRail
import com.keepasskey.app.ui.components.BottomNavItem
import com.keepasskey.app.ui.navigation.Screen
import com.keepasskey.app.ui.screens.authenticator.AuthenticatorScreen
import com.keepasskey.app.ui.screens.conflict.ConflictResolutionScreen
import com.keepasskey.app.ui.screens.database.DatabasePickerScreen
import com.keepasskey.app.ui.screens.detail.EntryDetailScreen
import com.keepasskey.app.ui.screens.edit.EntryEditScreen
import com.keepasskey.app.ui.screens.generator.GeneratorScreen
import com.keepasskey.app.ui.screens.settings.SettingsScreen
import com.keepasskey.app.ui.screens.settings.SettingsViewModel
import com.keepasskey.app.ui.screens.settings.subscreens.AboutSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.AutofillSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.DatabaseSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.DebugSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.HealthCheckScreen
import com.keepasskey.app.ui.screens.settings.subscreens.SecuritySettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.ThemeSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.TotpSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.WebDavSyncScreen
import com.keepasskey.app.ui.screens.unlock.UnlockScreen
import com.keepasskey.app.ui.screens.vault.VaultListScreen
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import java.util.Locale

/**
 * KeePasskey 界面总入口与全局路由宿主
 */
@Composable
fun KeePasskeyApp() {
    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = hiltViewModel(context as ComponentActivity)
    val appSettings by settingsViewModel.uiState.collectAsStateWithLifecycle()

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
            oledBlack = appSettings.oledBlackOptimization
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
                            autoLockManager?.triggerLock("用户手动点击锁定")
                            navController.navigate(Screen.Unlock.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
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
                        showBackButton = false
                    )
                }

                // 7. 二级设置页面：密码库与加密
                composable(Screen.SettingsDatabase.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    DatabaseSettingsScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onRecycleBinToggle = settingsViewModel::setRecycleBinEnabled,
                        onEncryptionAlgorithmChange = settingsViewModel::setEncryptionAlgorithm,
                        onKdfAlgorithmChange = settingsViewModel::setKdfAlgorithm,
                        onArgon2ParametersChange = settingsViewModel::setArgon2Parameters,
                        onTanExpiresOnUseToggle = settingsViewModel::setTanExpiresOnUse,
                        onCheckForDuplicateUuidsToggle = settingsViewModel::setCheckForDuplicateUuids
                    )
                }

                // 8. 二级设置页面：云端同步与文件处理 (WebDAV / S3 / 离线缓存)
                composable(Screen.SettingsSync.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    WebDavSyncScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onAutoSyncToggle = settingsViewModel::setAutoSyncEnabled,
                        onWifiOnlyToggle = settingsViewModel::setWifiOnlySync,
                        onTriggerSync = settingsViewModel::triggerSync,
                        onTestConnection = settingsViewModel::testSyncConnection,
                        onProviderChange = settingsViewModel::setSyncProvider,
                        onUpdateWebDav = settingsViewModel::updateWebDavConfig,
                        onUpdateS3 = settingsViewModel::updateS3Config,
                        onUseOfflineCacheToggle = settingsViewModel::setUseOfflineCache,
                        onSyncOnColdStartToggle = settingsViewModel::setSyncOnColdStart,
                        onPeriodicBackgroundSyncToggle = settingsViewModel::setPeriodicBackgroundSyncEnabled,
                        onPeriodicIntervalChange = settingsViewModel::setPeriodicBackgroundSyncInterval,
                        onAllowedWifiSsidsChange = settingsViewModel::setAllowedWifiSsids,
                        onCreateBackupBeforeSaveToggle = settingsViewModel::setCreateBackupBeforeSave,
                        onCheckRemoteChangesToggle = settingsViewModel::setCheckRemoteChangesBeforeSave,
                        onConflictResolutionChange = settingsViewModel::setConflictResolution,
                        onUseFileTransactionsToggle = settingsViewModel::setUseFileTransactions,
                        onAcceptAllCertificatesToggle = settingsViewModel::setAcceptAllCertificates,
                        onCleartextTrafficPermittedToggle = settingsViewModel::setCleartextTrafficPermitted,
                        onWebdavChunkedUploadToggle = settingsViewModel::setWebdavChunkedUpload,
                        onPreloadDatabaseEnabledToggle = settingsViewModel::setPreloadDatabaseEnabled
                    )
                }

                // 9. 二级设置页面：自动填充与 Passkey
                composable(Screen.SettingsAutofill.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    AutofillSettingsScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onCredentialProviderToggle = settingsViewModel::setCredentialProviderEnabled,
                        onPasskeySupportToggle = settingsViewModel::setPasskeySupportEnabled,
                        onAutofillServiceToggle = settingsViewModel::setAutofillServiceEnabled,
                        onAutoClearClipboardToggle = settingsViewModel::setAutoClearClipboard,
                        onOfferSaveCredentialsToggle = settingsViewModel::setOfferSaveCredentials,
                        onInlineSuggestionsToggle = settingsViewModel::setInlineSuggestionsEnabled,
                        onAutoReturnFromQueryToggle = settingsViewModel::setAutoReturnFromQuery,
                        onAutofillCopyTotpToggle = settingsViewModel::setAutofillCopyTotp,
                        onAutofillShowTotpNotificationToggle = settingsViewModel::setAutofillShowTotpNotification,
                        onSkipDalVerificationToggle = settingsViewModel::setSkipDalVerification,
                        onOverrideNoAutofillToggle = settingsViewModel::setOverrideNoAutofill
                    )
                }

                // 10. 二级设置页面：设备解锁与安全 (指纹识别与严苛锁定策略)
                composable(Screen.SettingsSecurity.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    SecuritySettingsScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onBiometricToggle = settingsViewModel::setBiometricEnabled,
                        onAutoLockToggle = settingsViewModel::setAutoLockBackground,
                        onFlagSecureToggle = settingsViewModel::setFlagSecureEnabled,
                        onAutoClearClipboardToggle = settingsViewModel::setAutoClearClipboard,
                        onAutoLockTimeoutChange = settingsViewModel::setAutoLockTimeout,
                        onClipboardTimeoutChange = settingsViewModel::setClipboardTimeout,
                        onLockWhenScreenOffToggle = settingsViewModel::setLockWhenScreenOff,
                        onLockWhenNavigateBackToggle = settingsViewModel::setLockWhenNavigateBack,
                        onClearPasswordOnLeaveToggle = settingsViewModel::setClearPasswordOnLeave,
                        onRememberRecentFilesToggle = settingsViewModel::setRememberRecentFiles,
                        onRememberKeyFileLocationToggle = settingsViewModel::setRememberKeyFileLocation,
                        onShowKillAppOptionToggle = settingsViewModel::setShowKillAppOption
                    )
                }

                // 11. 二级设置页面：外观与主题 (显示密度与敏感信息遮掩)
                composable(Screen.SettingsTheme.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    ThemeSettingsScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onThemeSelected = settingsViewModel::setThemeMode,
                        onPaletteSelected = settingsViewModel::setThemePalette,
                        onLanguageSelected = settingsViewModel::setAppLanguage,
                        onOledOptimizationToggle = settingsViewModel::setOledBlackOptimization,
                        onShowUsernameInList = settingsViewModel::setShowUsernameInList,
                        onShowOtpInList = settingsViewModel::setShowOtpInList,
                        onShowPasskeyBadge = settingsViewModel::setShowPasskeyBadge,
                        onShowUrlInList = settingsViewModel::setShowUrlInList,
                        onHideFabOnScrollToggle = settingsViewModel::setHideFabOnScroll,
                        onHapticFeedbackToggle = settingsViewModel::setHapticFeedbackEnabled,
                        onMaskPasswordsDefaultToggle = settingsViewModel::setMaskPasswordsDefault,
                        onMaskTotpDefaultToggle = settingsViewModel::setMaskTotpDefault,
                        onShowUnlockedNotificationToggle = settingsViewModel::setShowUnlockedNotification,
                        onShowGroupInSearchResultToggle = settingsViewModel::setShowGroupInSearchResult,
                        onShowGroupInEntryToggle = settingsViewModel::setShowGroupInEntry,
                        onListDensitySelected = settingsViewModel::setListDensity,
                        onAutoActivateSearchOnOpenToggle = settingsViewModel::setAutoActivateSearchOnOpen,
                        onIconSetSelected = settingsViewModel::setIconSet,
                        onShowAuthenticatorTabToggle = settingsViewModel::setShowAuthenticatorTab,
                        onShowGeneratorTabToggle = settingsViewModel::setShowGeneratorTab
                    )
                }

                // 12. 二级设置页面：两步验证与 TOTP 高级映射 (KP2A 特性)
                composable(Screen.SettingsTotp.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    TotpSettingsScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onUpdateTotpFieldMapping = settingsViewModel::updateTotpFieldMapping
                    )
                }

                // 13. 二级设置页面：健康度检查与密码审计
                composable(Screen.SettingsHealth.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    HealthCheckScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onRescanClick = settingsViewModel::rescanHealth
                    )
                }

                // 14. 二级设置页面：系统诊断与调试日志 (KP2A 特性)
                composable(Screen.SettingsDebug.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    DebugSettingsScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onDebugLogToggle = settingsViewModel::setDebugLogEnabled,
                        onVerboseSyncLogToggle = settingsViewModel::setVerboseSyncLog,
                        onRefreshLogs = settingsViewModel::refreshDebugLogs,
                        onClearLogs = settingsViewModel::clearDebugLogs
                    )
                }

                // 15. 二级设置页面：关于 KeePasskey
                composable(Screen.SettingsAbout.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    AboutSettingsScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
}
}
}
