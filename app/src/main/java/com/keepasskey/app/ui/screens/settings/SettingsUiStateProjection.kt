package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.security.RuntimeIntegrityReport
import com.keepasskey.app.ui.model.StringsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 设置页 UI 状态的**纯投影层**（ISSUE-P3-29：自 `SettingsViewModel.kt` 拆出）。
 *
 * 把「多路输入流快照 → [SettingsUiState]」的字段装配收敛为纯函数：不持有状态、不触发 IO，
 * 可脱离 ViewModel 独立推理与测试。原 `uiState` combine 变换体逐字迁移，行为零变更。
 */

/**
 * 组装设置页 [SettingsUiState] 状态流（原 `SettingsViewModel.uiState` 的 combine 编排逐字迁移）。
 *
 * 五路输入流 → `Map`/`Triple` 元组嵌套，避开 combine 的重载上限。
 */
@Suppress("LongParameterList")
internal fun settingsUiStateFlow(
    scope: CoroutineScope,
    timeoutMillis: Long,
    userSettings: Flow<UserSettings>,
    syncState: Flow<SettingsSyncController.SyncUiState>,
    healthState: Flow<SettingsHealthController.HealthCheckUiState>,
    autofillState: Flow<AutofillUiState>,
    databaseConfigState: Flow<DatabaseConfigUiState>,
    securityTimeoutState: Flow<SecurityTimeoutUiState>,
    extendedSettings: Flow<ExtendedSettings>,
    debugLogLines: Flow<List<String>>,
    integrityReport: Flow<RuntimeIntegrityReport?>,
    childDatabaseCount: Flow<Int>,
    strings: StringsProvider
): StateFlow<SettingsUiState> = combine(
    userSettings,
    syncState,
    healthState,
    combine(autofillState, databaseConfigState) { af, db -> Pair(af, db) },
    combine(
        combine(securityTimeoutState, extendedSettings, debugLogLines) { sec, ext, logs ->
            Triple(sec, ext, logs)
        },
        integrityReport,
        childDatabaseCount
    ) { securityState, report, mountedChildDatabases ->
        Triple(securityState, report, mountedChildDatabases)
    }
) { settings, sync, health, (autofill, db), (securityState, report, mounted) ->
    val (secState, extState, logs) = securityState
    buildSettingsUiState(
        userSettings = settings,
        syncState = sync,
        healthState = health,
        autofillState = autofill,
        dbState = db,
        secState = secState,
        extState = extState,
        debugLogLines = logs,
        integrityReport = report,
        mountedChildDatabases = mounted,
        strings = strings
    )
}.stateIn(
    scope = scope,
    started = SharingStarted.WhileSubscribed(timeoutMillis),
    initialValue = SettingsUiState()
)

internal fun buildSettingsUiState(
    userSettings: UserSettings,
    syncState: SettingsSyncController.SyncUiState,
    healthState: SettingsHealthController.HealthCheckUiState,
    autofillState: AutofillUiState,
    dbState: DatabaseConfigUiState,
    secState: SecurityTimeoutUiState,
    extState: ExtendedSettings,
    debugLogLines: List<String>,
    integrityReport: RuntimeIntegrityReport?,
    mountedChildDatabases: Int,
    strings: StringsProvider
): SettingsUiState = SettingsUiState(
    // 1. 密码库与加密设置
    databaseName = dbState.databaseName,
    databaseDefaultUsername = dbState.defaultUsername,
    encryptionAlgorithm = dbState.encryptionAlgorithm,
    kdfAlgorithm = dbState.kdfAlgorithm,
    argon2Iterations = dbState.argon2Iterations,
    argon2MemoryMb = dbState.argon2MemoryMb,
    argon2Parallelism = dbState.argon2Parallelism,
    recycleBinEnabled = dbState.recycleBinEnabled,
    tanExpiresOnUse = dbState.tanExpiresOnUse,
    checkForDuplicateUuids = dbState.checkForDuplicateUuids,
    // ISSUE-P3-20：真实已挂载计数（原为硬编码 0；语义为「已挂载」而非「已解锁」）
    childDatabasesCount = mountedChildDatabases,

    // 2. 云端多协议同步与文件处理
    syncProvider = syncState.provider,
    webdavUrl = syncState.webdavUrl,
    webdavUsername = syncState.webdavUsername,
    webdavRemotePath = syncState.webdavRemotePath,
    s3Endpoint = syncState.s3Endpoint,
    s3Bucket = syncState.s3Bucket,
    s3Region = syncState.s3Region,
    s3ObjectKey = syncState.s3ObjectKey,
    s3UsePathStyle = syncState.s3UsePathStyle,
    autoSyncEnabled = syncState.autoSyncEnabled,
    wifiOnlySync = syncState.wifiOnlySync,
    isSyncing = syncState.isSyncing,
    syncFeedbackMessage = syncState.syncFeedbackMessage,
    syncLastTime = syncState.lastSyncTimeText.ifEmpty { strings.get(R.string.sync_last_time_never) },
    useOfflineCache = extState.useOfflineCache,
    syncOnColdStart = userSettings.syncOnColdStart,
    periodicBackgroundSyncEnabled = extState.periodicBackgroundSyncEnabled,
    periodicBackgroundSyncIntervalMinutes = extState.periodicBackgroundSyncIntervalMinutes,
    allowedWifiSsids = extState.allowedWifiSsids,
    createBackupBeforeSave = extState.createBackupBeforeSave,
    checkRemoteChangesBeforeSave = extState.checkRemoteChangesBeforeSave,
    conflictResolution = extState.conflictResolution,
    useFileTransactions = extState.useFileTransactions,
    webdavChunkedUpload = extState.webdavChunkedUpload,
    webdavChunkSizeMb = extState.webdavChunkSizeMb,
    preloadDatabaseEnabled = extState.preloadDatabaseEnabled,

    // 3. 表单自动填充与 Passkey
    credentialProviderEnabled = autofillState.credentialProviderEnabled,
    passkeySupportEnabled = autofillState.passkeySupportEnabled,
    autofillServiceEnabled = autofillState.autofillServiceEnabled,
    offerSaveCredentials = extState.offerSaveCredentials,
    inlineSuggestionsEnabled = extState.inlineSuggestionsEnabled,
    autoReturnFromQuery = extState.autoReturnFromQuery,
    autofillCopyTotp = extState.autofillCopyTotp,
    autofillShowTotpNotification = extState.autofillShowTotpNotification,
    skipDalVerification = extState.skipDalVerification,
    overrideNoAutofill = extState.overrideNoAutofill,
    autofillSessionGrantEnabled = extState.autofillSessionGrantEnabled,

    // 4. 设备解锁与安全 (指纹识别与锁定规则)
    biometricEnabled = userSettings.biometricEnabled,
    autoLockBackground = userSettings.autoLockBackground,
    flagSecureEnabled = userSettings.flagSecureEnabled,
    autoClearClipboard = userSettings.autoClearClipboard,
    autoLockTimeoutSeconds = secState.autoLockTimeoutSeconds,
    clipboardTimeoutSeconds = userSettings.clipboardTimeoutSeconds,
    lockWhenScreenOff = extState.lockWhenScreenOff,
    lockWhenNavigateBack = extState.lockWhenNavigateBack,
    clearPasswordOnLeave = extState.clearPasswordOnLeave,
    rememberRecentFiles = extState.rememberRecentFiles,
    rememberKeyFileLocation = extState.rememberKeyFileLocation,
    showKillAppOption = extState.showKillAppOption,

    // 5. 外观与显示偏好
    themeMode = userSettings.themeMode,
    themePalette = userSettings.themePalette,
    appLanguage = userSettings.appLanguage,
    oledBlackOptimization = userSettings.oledBlackOptimization,
    dynamicColorEnabled = userSettings.dynamicColorEnabled,
    showUsernameInList = userSettings.showUsernameInList,
    showOtpInList = userSettings.showOtpInList,
    showPasskeyBadge = userSettings.showPasskeyBadge,
    showUrlInList = userSettings.showUrlInList,
    hideFabOnScroll = userSettings.hideFabOnScroll,
    hapticFeedbackEnabled = userSettings.hapticFeedbackEnabled,
    maskPasswordsDefault = extState.maskPasswordsDefault,
    maskTotpDefault = extState.maskTotpDefault,
    showUnlockedNotification = extState.showUnlockedNotification,
    showGroupInSearchResult = extState.showGroupInSearchResult,
    showGroupInEntry = extState.showGroupInEntry,
    listDensity = extState.listDensity,
    autoActivateSearchOnOpen = extState.autoActivateSearchOnOpen,
    iconSet = extState.iconSet,
    showAuthenticatorTab = userSettings.showAuthenticatorTab,
    showGeneratorTab = userSettings.showGeneratorTab,

    // 6. TOTP 规范字段映射
    totpSeedFieldName = extState.totpSeedFieldName,
    totpSettingsFieldName = extState.totpSettingsFieldName,
    defaultTotpStepSeconds = extState.defaultTotpStepSeconds,
    defaultTotpDigits = extState.defaultTotpDigits,

    // 7. 密码库健康度检查
    healthScore = healthState.healthScore,
    healthStatus = healthState.healthStatus,
    healthMessage = healthState.healthMessage,
    weakPasswordCount = healthState.weakPasswordCount,
    reusedPasswordCount = healthState.reusedPasswordCount,
    compromisedPasswordCount = healthState.compromisedPasswordCount,
    breachCheckStatus = healthState.breachCheckStatus,
    breachCheckMessage = healthState.breachCheckMessage,
    breachCheckEnabled = extState.breachCheckEnabled,
    lastHealthScanTime = healthState.lastHealthScanTime,
    isHealthScanning = healthState.isHealthScanning,

    // 8. 调试日志
    debugLogEnabled = extState.debugLogEnabled,
    verboseSyncLog = extState.verboseSyncLog,
    debugLogLines = debugLogLines,

    // 9. 运行环境完整性（ISSUE-P2-08 风险提示数据源）
    integrityReport = integrityReport
)
