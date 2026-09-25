package com.keepasskey.app.ui

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.keepasskey.app.ui.navigation.Screen
import com.keepasskey.app.ui.screens.settings.SettingsViewModel
import com.keepasskey.app.ui.screens.settings.subscreens.AboutSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.AutofillSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.DatabaseSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.DebugSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.HealthCheckScreen
import com.keepasskey.app.ui.screens.settings.subscreens.PrivilegedBrowserSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.SecuritySettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.ThemeSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.TotpSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.WebDavSyncScreen

/**
 * 二级设置页的逐条注册体（§280 自 [keepasskeySettingsNavGraph] 拆出，纯结构性改动）。
 *
 * 与门面同文件族：每条 [composable] 独立成窄扩展，状态流收集与 ViewModel 方法引用
 * 逐条原样迁移。页面均为自包含的 `hiltViewModel<SettingsViewModel>()` 宿主。
 */

/** 7. 二级设置页面：密码库与加密 */
internal fun NavGraphBuilder.settingsDatabaseRoute(navController: NavHostController) {
    composable(Screen.SettingsDatabase.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
        val kdfBenchmarkState by settingsViewModel.kdfBenchmark.collectAsStateWithLifecycle()
        // TASK-13 整改：导出/模板动作反馈流
        val exportFeedback by settingsViewModel.exportFeedback.collectAsStateWithLifecycle()
        // ISSUE-P3-19：明文导入状态流（Idle / Parsing / Done / Failed）
        val importState by settingsViewModel.importState.collectAsStateWithLifecycle()
        // ISSUE-P3-20：子库挂载状态流（真实挂载记录 + 运行时状态 + 操作反馈）
        val childDatabaseState by settingsViewModel.childDatabaseState.collectAsStateWithLifecycle()
        DatabaseSettingsScreen(
            uiState = settingsState,
            onBackClick = { navController.popBackStack() },
            onRecycleBinToggle = settingsViewModel::setRecycleBinEnabled,
            onEncryptionAlgorithmChange = settingsViewModel::setEncryptionAlgorithm,
            onKdfAlgorithmChange = settingsViewModel::setKdfAlgorithm,
            onArgon2ParametersChange = settingsViewModel::setArgon2Parameters,
            // M6 整改：真实 KDF 基准接线
            kdfBenchmarkState = kdfBenchmarkState,
            onRunKdfBenchmark = settingsViewModel::runKdfBenchmark,
            // TASK-13 整改：导出/模板真实动作接线
            exportFeedback = exportFeedback,
            onClearExportFeedback = settingsViewModel::clearExportFeedback,
            onExportKdbx = settingsViewModel::exportKdbxTo,
            onExportXml = settingsViewModel::exportVaultXmlTo,
            onExportCsv = settingsViewModel::exportVaultCsvTo,
            onExportKeyFile = settingsViewModel::exportKeyFileTo,
            onInstallTemplates = settingsViewModel::installEntryTemplates,
            // ISSUE-P3-19：导入链路（选源 → SAF 选文件 → 控制器解析/落库 → 报告对话框）
            importState = importState,
            onImportFileSelected = settingsViewModel::startImport,
            onImportReportDismiss = settingsViewModel::dismissImportReport,
            // ISSUE-P3-20：子库挂载链路（真实挂载 / 凭据重录解锁 / 卸载）
            childDatabaseState = childDatabaseState,
            onMountChildDatabase = settingsViewModel::mountChildDatabase,
            onUnlockChildDatabase = settingsViewModel::unlockChildDatabase,
            onUnmountChildDatabase = settingsViewModel::unmountChildDatabase,
            onChildDatabaseFeedbackDismiss = settingsViewModel::dismissChildDatabaseFeedback
        )
    }
}

/** 8. 二级设置页面：云端同步与文件处理 (WebDAV / S3 / 离线缓存) */
internal fun NavGraphBuilder.settingsSyncRoute(navController: NavHostController) {
    composable(Screen.SettingsSync.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
        // Wave 15 整改：同步凭据一次性预填通道（明文不进 UiState）
        val webdavPasswordPrefill by settingsViewModel.webdavPasswordPrefill.collectAsStateWithLifecycle()
        val s3SecretKeyPrefill by settingsViewModel.s3SecretKeyPrefill.collectAsStateWithLifecycle()
        // ISSUE-P2-01：S3 AccessKey ID 一次性预填通道（明文不进 UiState）
        val s3AccessKeyPrefill by settingsViewModel.s3AccessKeyPrefill.collectAsStateWithLifecycle()
        WebDavSyncScreen(
            uiState = settingsState,
            onBackClick = { navController.popBackStack() },
            onAutoSyncToggle = settingsViewModel::setAutoSyncEnabled,
            onWifiOnlyToggle = settingsViewModel::setWifiOnlySync,
            onTriggerSync = settingsViewModel::triggerSync,
            onTestConnection = settingsViewModel::testSyncConnection,
            // 本批整改：「保存并同步」——保存成功后由 ViewModel 顺序编排
            // （未验证连接则先测连接，通过才同步，失败即停并上浮）
            onSyncAfterSave = settingsViewModel::verifyConnectionThenSync,
            onProviderChange = settingsViewModel::setSyncProvider,
            onUpdateWebDav = settingsViewModel::updateWebDavConfig,
            onUpdateS3 = settingsViewModel::updateS3Config,
            webdavPasswordPrefill = webdavPasswordPrefill,
            s3SecretKeyPrefill = s3SecretKeyPrefill,
            s3AccessKeyPrefill = s3AccessKeyPrefill,
            onWebDavPasswordEdited = settingsViewModel::clearWebDavPasswordPrefill,
            onS3SecretKeyEdited = settingsViewModel::clearS3SecretKeyPrefill,
            onS3AccessKeyEdited = settingsViewModel::clearS3AccessKeyPrefill,
            onUseOfflineCacheToggle = settingsViewModel::setUseOfflineCache,
            onSyncOnColdStartToggle = settingsViewModel::setSyncOnColdStart,
            onPeriodicBackgroundSyncToggle = settingsViewModel::setPeriodicBackgroundSyncEnabled,
            onPeriodicIntervalChange = settingsViewModel::setPeriodicBackgroundSyncInterval,
            onCreateBackupBeforeSaveToggle = settingsViewModel::setCreateBackupBeforeSave,
            onCheckRemoteChangesToggle = settingsViewModel::setCheckRemoteChangesBeforeSave,
            onConflictResolutionChange = settingsViewModel::setConflictResolution,
            onWebdavChunkedUploadToggle = settingsViewModel::setWebdavChunkedUpload
        )
    }
}

/** 9. 二级设置页面：自动填充与 Passkey */
internal fun NavGraphBuilder.settingsAutofillRoute(navController: NavHostController) {
    composable(Screen.SettingsAutofill.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
        // TASK-44：黑名单独立通道（不进 settingsState 的 5 流 combine）
        val blockedPackages by settingsViewModel.autofillBlockedPackages.collectAsStateWithLifecycle()
        // ISSUE-P3-43：保存侧黑名单与字段级屏蔽计数（同样走独立通道）
        val saveBlockedPackages by settingsViewModel.autofillSaveBlockedPackages.collectAsStateWithLifecycle()
        val blockedFieldCount by settingsViewModel.autofillBlockedFieldCount.collectAsStateWithLifecycle()
        AutofillSettingsScreen(
            uiState = settingsState,
            onBackClick = { navController.popBackStack() },
            onCredentialProviderToggle = settingsViewModel::setCredentialProviderEnabled,
            onPasskeySupportToggle = settingsViewModel::setPasskeySupportEnabled,
            onAutofillServiceToggle = settingsViewModel::setAutofillServiceEnabled,
            onAutofillLegacyAccessibilityToggle = settingsViewModel::setAutofillLegacyAccessibilityEnabled,
            onAutoClearClipboardToggle = settingsViewModel::setAutoClearClipboard,
            onOfferSaveCredentialsToggle = settingsViewModel::setOfferSaveCredentials,
            onInlineSuggestionsToggle = settingsViewModel::setInlineSuggestionsEnabled,
            onAutoReturnFromQueryToggle = settingsViewModel::setAutoReturnFromQuery,
            onAutofillCopyTotpToggle = settingsViewModel::setAutofillCopyTotp,
            onAutofillShowTotpNotificationToggle = settingsViewModel::setAutofillShowTotpNotification,
            onSkipDalVerificationToggle = settingsViewModel::setSkipDalVerification,
            onOverrideNoAutofillToggle = settingsViewModel::setOverrideNoAutofill,
            onAutofillSessionGrantToggle = settingsViewModel::setAutofillSessionGrantEnabled,
            onOpenPrivilegedBrowsers = { navController.navigate(Screen.SettingsPrivilegedBrowsers.route) },
            blockedPackages = blockedPackages,
            onBlockAutofillPackage = settingsViewModel::blockAutofillPackage,
            onUnblockAutofillPackage = settingsViewModel::unblockAutofillPackage,
            saveBlockedPackages = saveBlockedPackages,
            onBlockSavePackage = settingsViewModel::blockSavePackage,
            onUnblockSavePackage = settingsViewModel::unblockSavePackage,
            blockedFieldCount = blockedFieldCount,
            onClearBlockedFields = settingsViewModel::clearBlockedFields
        )
    }
}

/** 9b. 三级设置页面：特权浏览器白名单（CM 通道通行密钥在非 Chrome 浏览器上的可用性） */
internal fun NavGraphBuilder.settingsPrivilegedBrowsersRoute(navController: NavHostController) {
    composable(Screen.SettingsPrivilegedBrowsers.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val browsers by settingsViewModel.privilegedBrowsers.collectAsStateWithLifecycle()
        PrivilegedBrowserSettingsScreen(
            browsers = browsers,
            onBackClick = { navController.popBackStack() },
            onToggle = settingsViewModel::setPrivilegedBrowserEnabled,
            onRefresh = settingsViewModel::refreshPrivilegedBrowsers
        )
    }
}

/** 10. 二级设置页面：设备解锁与安全 (指纹识别与严苛锁定策略) */
internal fun NavGraphBuilder.settingsSecurityRoute(navController: NavHostController) {
    composable(Screen.SettingsSecurity.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
        SecuritySettingsScreen(
            uiState = settingsState,
            // ISSUE-P2-08：下发运行完整性快照，驱动风险提示卡片（不静默放行）
            integrityReport = settingsState.integrityReport,
            onBackClick = { navController.popBackStack() },
            onBiometricToggle = settingsViewModel::setBiometricEnabled,
            // ISSUE-P3-236 / PD-15：运行环境完整性检测总开关（默认关闭，关闭时不阻断指纹）
            onIntegrityCheckToggle = settingsViewModel::setIntegrityCheckEnabled,
            onAutoLockToggle = settingsViewModel::setAutoLockBackground,
            onFlagSecureToggle = settingsViewModel::setFlagSecureEnabled,
            onAutoClearClipboardToggle = settingsViewModel::setAutoClearClipboard,
            onAutoLockTimeoutChange = settingsViewModel::setAutoLockTimeout,
            onClipboardTimeoutChange = settingsViewModel::setClipboardTimeout,
            onUnlockThrottleToggle = settingsViewModel::setUnlockThrottleEnabled,
            onUnlockLockoutMaxChange = settingsViewModel::setUnlockLockoutMaxSeconds,
            onLockWhenScreenOffToggle = settingsViewModel::setLockWhenScreenOff,
            onLockWhenNavigateBackToggle = settingsViewModel::setLockWhenNavigateBack,
            onClearPasswordOnLeaveToggle = settingsViewModel::setClearPasswordOnLeave,
            onRememberKeyFileLocationToggle = settingsViewModel::setRememberKeyFileLocation,
            onShowKillAppOptionToggle = settingsViewModel::setShowKillAppOption
        )
    }
}

/** 11. 二级设置页面：外观与主题 (显示密度与敏感信息遮掩) */
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
            onLanguageSelected = settingsViewModel::setAppLanguage,
            onOledOptimizationToggle = settingsViewModel::setOledBlackOptimization,
            onDynamicColorToggle = settingsViewModel::setDynamicColorEnabled,
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
            onSearchMatchModeSelected = settingsViewModel::setSearchMatchMode,
            onShowAuthenticatorTabToggle = settingsViewModel::setShowAuthenticatorTab,
            onShowGeneratorTabToggle = settingsViewModel::setShowGeneratorTab
        )
    }
}

/** 12. 二级设置页面：两步验证与 TOTP 高级映射 (KP2A 特性) */
internal fun NavGraphBuilder.settingsTotpRoute(navController: NavHostController) {
    composable(Screen.SettingsTotp.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
        TotpSettingsScreen(
            uiState = settingsState,
            onBackClick = { navController.popBackStack() },
            onUpdateTotpFieldMapping = settingsViewModel::updateTotpFieldMapping
        )
    }
}

/** 13. 二级设置页面：健康度检查与密码审计 */
internal fun NavGraphBuilder.settingsHealthRoute(navController: NavHostController) {
    composable(Screen.SettingsHealth.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
        HealthCheckScreen(
            uiState = settingsState,
            onBackClick = { navController.popBackStack() },
            onRescanClick = settingsViewModel::rescanHealth,
            // TASK-47：已泄露密码检测开关（默认关闭，开启后才会联网比对）
            // 本批整改：**仅开启方向**顺带就地扫描一次——开关在页面底部、重扫按钮在顶部，
            // 否则用户开启后须自行滚回顶部再点一次才能看到结果；关闭方向仍只写偏好（零外联）。
            onBreachCheckToggle = { enabled ->
                if (enabled) {
                    settingsViewModel.enableBreachCheckAndScan()
                } else {
                    settingsViewModel.setBreachCheckEnabled(false)
                }
            }
        )
    }
}

/** 14. 二级设置页面：系统诊断与调试日志 (KP2A 特性) */
internal fun NavGraphBuilder.settingsDebugRoute(navController: NavHostController) {
    composable(Screen.SettingsDebug.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
        val debugExportFeedback by settingsViewModel.debugExportFeedback.collectAsStateWithLifecycle()
        DebugSettingsScreen(
            uiState = settingsState,
            onBackClick = { navController.popBackStack() },
            onDebugLogToggle = settingsViewModel::setDebugLogEnabled,
            onVerboseSyncLogToggle = settingsViewModel::setVerboseSyncLog,
            onRefreshLogs = settingsViewModel::refreshDebugLogs,
            onClearLogs = settingsViewModel::clearDebugLogs,
            onExportLogs = settingsViewModel::exportDebugLogs,
            exportFeedback = debugExportFeedback,
            onClearExportFeedback = settingsViewModel::clearDebugExportFeedback
        )
    }
}

/** 15. 二级设置页面：关于 KeePasskey */
internal fun NavGraphBuilder.settingsAboutRoute(navController: NavHostController) {
    composable(Screen.SettingsAbout.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
        AboutSettingsScreen(
            uiState = settingsState,
            onBackClick = { navController.popBackStack() }
        )
    }
}
