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
import com.keepasskey.app.ui.screens.settings.subscreens.SecuritySettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.ThemeSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.TotpSettingsScreen
import com.keepasskey.app.ui.screens.settings.subscreens.WebDavSyncScreen

/**
 * 二级设置页路由图（ISSUE-P3-29：自 `KeePasskeyNavGraph.kt` 拆出，纯结构性拆分）。
 *
 * 逐条路由定义原样迁移；这些页面均为自包含的 `hiltViewModel<SettingsViewModel>()` 宿主，
 * 除 [navController] 外不依赖任何外层上下文。
 */
internal fun NavGraphBuilder.keepasskeySettingsNavGraph(
    navController: NavHostController
) {
    // 7. 二级设置页面：密码库与加密
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

    // 8. 二级设置页面：云端同步与文件处理 (WebDAV / S3 / 离线缓存)
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
            onAllowedWifiSsidsChange = settingsViewModel::setAllowedWifiSsids,
            onCreateBackupBeforeSaveToggle = settingsViewModel::setCreateBackupBeforeSave,
            onCheckRemoteChangesToggle = settingsViewModel::setCheckRemoteChangesBeforeSave,
            onConflictResolutionChange = settingsViewModel::setConflictResolution,
            onUseFileTransactionsToggle = settingsViewModel::setUseFileTransactions,
            onWebdavChunkedUploadToggle = settingsViewModel::setWebdavChunkedUpload,
            onPreloadDatabaseEnabledToggle = settingsViewModel::setPreloadDatabaseEnabled
        )
    }

    // 9. 二级设置页面：自动填充与 Passkey
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
            onAutoClearClipboardToggle = settingsViewModel::setAutoClearClipboard,
            onOfferSaveCredentialsToggle = settingsViewModel::setOfferSaveCredentials,
            onInlineSuggestionsToggle = settingsViewModel::setInlineSuggestionsEnabled,
            onAutoReturnFromQueryToggle = settingsViewModel::setAutoReturnFromQuery,
            onAutofillCopyTotpToggle = settingsViewModel::setAutofillCopyTotp,
            onAutofillShowTotpNotificationToggle = settingsViewModel::setAutofillShowTotpNotification,
            onSkipDalVerificationToggle = settingsViewModel::setSkipDalVerification,
            onOverrideNoAutofillToggle = settingsViewModel::setOverrideNoAutofill,
            onAutofillSessionGrantToggle = settingsViewModel::setAutofillSessionGrantEnabled,
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

    // 10. 二级设置页面：设备解锁与安全 (指纹识别与严苛锁定策略)
    composable(Screen.SettingsSecurity.route) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
        SecuritySettingsScreen(
            uiState = settingsState,
            // ISSUE-P2-08：下发运行完整性快照，驱动风险提示卡片（不静默放行）
            integrityReport = settingsState.integrityReport,
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
            onRescanClick = settingsViewModel::rescanHealth,
            // TASK-47：已泄露密码检测开关（默认关闭，开启后才会联网比对）
            onBreachCheckToggle = settingsViewModel::setBreachCheckEnabled
        )
    }

    // 14. 二级设置页面：系统诊断与调试日志 (KP2A 特性)
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
