package com.keepasskey.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.keepasskey.app.ui.components.AppBottomBar
import com.keepasskey.app.ui.components.BottomNavItem
import com.keepasskey.app.ui.navigation.Screen
import com.keepasskey.app.ui.screens.detail.EntryDetailScreen
import com.keepasskey.app.ui.screens.edit.EntryEditScreen
import com.keepasskey.app.ui.screens.settings.SettingsScreen
import com.keepasskey.app.ui.screens.settings.SettingsViewModel
import com.keepasskey.app.ui.screens.settings.subscreens.AboutSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.AutofillSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.DatabaseSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.HealthCheckScreen
import com.keepasskey.app.ui.screens.settings.subscreens.SecuritySettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.ThemeSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.WebDavSyncScreen
import com.keepasskey.app.ui.screens.unlock.UnlockScreen
import com.keepasskey.app.ui.screens.vault.VaultListScreen
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * KeePasskey 界面总入口与全局路由宿主
 */
@Composable
fun KeePasskeyApp() {
    // 全局主题状态（持久化保存于进程生命周期）
    var themeMode by rememberSaveable { mutableStateOf(AppThemeMode.SYSTEM) }

    // 主题三态循环切换方法
    val toggleTheme: () -> Unit = {
        themeMode = when (themeMode) {
            AppThemeMode.LIGHT -> AppThemeMode.DARK
            AppThemeMode.DARK -> AppThemeMode.SYSTEM
            AppThemeMode.SYSTEM -> AppThemeMode.LIGHT
        }
    }

    KeePasskeyTheme(themeMode = themeMode) {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val showBottomBar = BottomNavItem.isTopLevelRoute(currentRoute)

        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    AppBottomBar(
                        currentRoute = currentRoute,
                        onNavigateToRoute = { targetRoute ->
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
                    )
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Unlock.route,
                modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() }
            ) {
                // 1. 登录与解锁页
                composable(Screen.Unlock.route) {
                    UnlockScreen(
                        currentTheme = themeMode,
                        onThemeToggle = toggleTheme,
                        onUnlockSuccess = {
                            navController.navigate(Screen.VaultList.route) {
                                popUpTo(Screen.Unlock.route) { inclusive = true }
                            }
                        }
                    )
                }

                // 2. 主密码库列表页
                composable(Screen.VaultList.route) {
                    VaultListScreen(
                        currentTheme = themeMode,
                        onThemeToggle = toggleTheme,
                        onEntryClick = { entryId ->
                            navController.navigate(Screen.EntryDetail.createRoute(entryId))
                        },
                        onAddEntryClick = { groupId ->
                            navController.navigate(Screen.EntryEdit.createRoute(groupId = groupId))
                        },
                        onLockClick = {
                            navController.navigate(Screen.Unlock.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    )
                }

                // 3. 密码详情页
                composable(
                    route = Screen.EntryDetail.route,
                    arguments = listOf(navArgument("entryId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val entryId = backStackEntry.arguments?.getString("entryId") ?: "1"
                    EntryDetailScreen(
                        entryId = entryId,
                        onBackClick = { navController.popBackStack() },
                        onEditClick = { id ->
                            navController.navigate(Screen.EntryEdit.createRoute(id))
                        }
                    )
                }

                // 4. 添加/编辑条目页
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

                // 5. 设置主页（作为一级标签页展示）
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onNavigateToDatabase = { navController.navigate(Screen.SettingsDatabase.route) },
                        onNavigateToSync = { navController.navigate(Screen.SettingsSync.route) },
                        onNavigateToAutofill = { navController.navigate(Screen.SettingsAutofill.route) },
                        onNavigateToSecurity = { navController.navigate(Screen.SettingsSecurity.route) },
                        onNavigateToTheme = { navController.navigate(Screen.SettingsTheme.route) },
                        onNavigateToHealth = { navController.navigate(Screen.SettingsHealth.route) },
                        onNavigateToAbout = { navController.navigate(Screen.SettingsAbout.route) },
                        showBackButton = false
                    )
                }

                // 6. 二级设置页面：密码库与加密
                composable(Screen.SettingsDatabase.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    DatabaseSettingsScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onRecycleBinToggle = settingsViewModel::setRecycleBinEnabled,
                        onEncryptionAlgorithmChange = settingsViewModel::setEncryptionAlgorithm,
                        onKdfAlgorithmChange = settingsViewModel::setKdfAlgorithm,
                        onArgon2ParametersChange = settingsViewModel::setArgon2Parameters
                    )
                }

                // 7. 二级设置页面：云端同步 (WebDAV 与 兼容 S3 存储)
                composable(Screen.SettingsSync.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    WebDavSyncScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onAutoSyncToggle = settingsViewModel::setAutoSyncEnabled,
                        onWifiOnlyToggle = settingsViewModel::setWifiOnlySync,
                        onTriggerSync = settingsViewModel::triggerSync,
                        onProviderChange = settingsViewModel::setSyncProvider,
                        onUpdateWebDav = settingsViewModel::updateWebDavConfig,
                        onUpdateS3 = settingsViewModel::updateS3Config
                    )
                }

                // 8. 二级设置页面：自动填充与 Passkey
                composable(Screen.SettingsAutofill.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    AutofillSettingsScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onCredentialProviderToggle = settingsViewModel::setCredentialProviderEnabled,
                        onPasskeySupportToggle = settingsViewModel::setPasskeySupportEnabled,
                        onAutofillServiceToggle = settingsViewModel::setAutofillServiceEnabled,
                        onAutoClearClipboardToggle = settingsViewModel::setAutoClearClipboard
                    )
                }

                // 9. 二级设置页面：设备解锁与安全
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
                        onClipboardTimeoutChange = settingsViewModel::setClipboardTimeout
                    )
                }

                // 10. 二级设置页面：外观与主题
                composable(Screen.SettingsTheme.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    ThemeSettingsScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onThemeSelected = { newTheme ->
                            settingsViewModel.setThemeMode(newTheme)
                            themeMode = newTheme
                        },
                        onOledOptimizationToggle = settingsViewModel::setOledBlackOptimization
                    )
                }

                // 11. 二级设置页面：健康度检查
                composable(Screen.SettingsHealth.route) {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    HealthCheckScreen(
                        uiState = settingsState,
                        onBackClick = { navController.popBackStack() },
                        onRescanClick = settingsViewModel::rescanHealth
                    )
                }

                // 12. 二级设置页面：关于 KeePasskey
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

