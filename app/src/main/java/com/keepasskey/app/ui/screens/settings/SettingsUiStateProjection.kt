package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
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
 * 九路输入流收拢为 [SettingsUiStateFlows]（§284 摘除 `LongParameterList` 压制）；
 * combine 仍以 `Map`/`Triple` 元组嵌套避开重载上限，行为零变化。
 */
/** 九路输入流快照（§284 参数对象化；仅承载引用，不产生新订阅） */
internal data class SettingsUiStateFlows(
    val userSettings: Flow<UserSettings>,
    val syncState: Flow<SettingsSyncController.SyncUiState>,
    val healthState: Flow<SettingsHealthController.HealthCheckUiState>,
    val databaseConfigState: Flow<DatabaseConfigUiState>,
    // ISSUE-P2-212：生物识别开关的「验证中 / 一次性反馈」局部状态（与持久化偏好正交）
    val biometricToggleState: Flow<BiometricToggleUiState>,
    val securityTimeoutState: Flow<SecurityTimeoutUiState>,
    val extendedSettings: Flow<ExtendedSettings>,
    val debugLogLines: Flow<List<String>>,
    val childDatabaseCount: Flow<Int>
)

internal fun settingsUiStateFlow(
    scope: CoroutineScope,
    timeoutMillis: Long,
    flows: SettingsUiStateFlows,
    strings: StringsProvider
): StateFlow<SettingsUiState> = combine(
    flows.userSettings,
    flows.syncState,
    flows.healthState,
    combine(flows.databaseConfigState, flows.biometricToggleState) { db, toggle ->
        Pair(db, toggle)
    },
    combine(
        combine(flows.securityTimeoutState, flows.extendedSettings, flows.debugLogLines) { sec, ext, logs ->
            Triple(sec, ext, logs)
        },
        flows.childDatabaseCount
    ) { securityState, mountedChildDatabases ->
        Pair(securityState, mountedChildDatabases)
    }
) { settings, sync, health, (db, biometricToggle), (securityState, mounted) ->
    val (secState, extState, logs) = securityState
    buildSettingsUiState(
        userSettings = settings,
        syncState = sync,
        healthState = health,
        dbState = db,
        biometricToggle = biometricToggle,
        secState = secState,
        extState = extState,
        debugLogLines = logs,
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
    dbState: DatabaseConfigUiState,
    // ISSUE-P2-212：生物识别开关的验证中/一次性反馈（默认值便于既有调用点不受影响）
    biometricToggle: BiometricToggleUiState = BiometricToggleUiState(),
    secState: SecurityTimeoutUiState,
    extState: ExtendedSettings,
    debugLogLines: List<String>,
    mountedChildDatabases: Int,
    strings: StringsProvider
): SettingsUiState = SettingsUiState(
    // 1. 密码库与加密设置
    databaseName = dbState.databaseName,
    databaseDefaultUsername = dbState.defaultUsername,
    // ISSUE-P3-59：文件路径真实下发（活动库记录）
    databasePath = dbState.databasePath,
    encryptionAlgorithm = dbState.encryptionAlgorithm,
    kdfAlgorithm = dbState.kdfAlgorithm,
    argon2Iterations = dbState.argon2Iterations,
    argon2MemoryMb = dbState.argon2MemoryMb,
    argon2Parallelism = dbState.argon2Parallelism,
    recycleBinEnabled = dbState.recycleBinEnabled,
    // ISSUE-P3-65：tanExpiresOnUse / checkForDuplicateUuids 映射已随字段移除（假开关如实禁用）
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
    // ISSUE-P3-272：autoSyncEnabled 改由持久化的 extState 投影（原读 SyncUiState 内存态，重启即回弹）
    autoSyncEnabled = extState.autoSyncEnabled,
    wifiOnlySync = syncState.wifiOnlySync,
    isSyncing = syncState.isSyncing,
    syncFeedbackMessage = syncState.syncFeedbackMessage,
    syncLastTime = syncState.lastSyncTimeText.ifEmpty { strings.get(R.string.sync_last_time_never) },
    isConnectionVerified = syncState.isConnectionVerified,
    // ISSUE-P2-285 AC②：凭据封印声明的实测硬件落位（单一真相源 = SyncCredentialsStore 探测）
    syncSealHardwareBacked = syncState.syncSealHardwareBacked,
    syncStatusText = when {
        syncState.isSyncing -> strings.get(R.string.sync_status_syncing)
        syncState.isConnectionVerified -> strings.get(R.string.sync_status_synced)
        else -> strings.get(R.string.sync_status_unverified)
    },
    useOfflineCache = extState.useOfflineCache,
    syncOnColdStart = userSettings.syncOnColdStart,
    periodicBackgroundSyncEnabled = extState.periodicBackgroundSyncEnabled,
    periodicBackgroundSyncIntervalMinutes = extState.periodicBackgroundSyncIntervalMinutes,
    createBackupBeforeSave = extState.createBackupBeforeSave,
    checkRemoteChangesBeforeSave = extState.checkRemoteChangesBeforeSave,
    conflictResolution = extState.conflictResolution,
    webdavChunkedUpload = extState.webdavChunkedUpload,
    webdavChunkSizeMb = extState.webdavChunkSizeMb,

    // 3. 表单自动填充与 Passkey
    // ISSUE-P2-228：三条通道开关取自持久化的 extState（迁移前来自内存态 AutofillUiState，重启即回弹）
    credentialProviderEnabled = extState.credentialProviderEnabled,
    passkeySupportEnabled = extState.passkeySupportEnabled,
    autofillServiceEnabled = extState.autofillServiceEnabled,
    autofillLegacyAccessibilityEnabled = extState.autofillLegacyAccessibilityEnabled,
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
    // ISSUE-P2-212：开关开启动作的即时状态（持久化值是开关选中态的唯一真相源）
    biometricVerifying = biometricToggle.verifying,
    biometricToggleNotice = biometricToggle.notice,
    autoLockBackground = userSettings.autoLockBackground,
    flagSecureEnabled = userSettings.flagSecureEnabled,
    autoClearClipboard = userSettings.autoClearClipboard,
    autoLockTimeoutSeconds = secState.autoLockTimeoutSeconds,
    clipboardTimeoutSeconds = userSettings.clipboardTimeoutSeconds,
    lockWhenScreenOff = extState.lockWhenScreenOff,
    lockWhenNavigateBack = extState.lockWhenNavigateBack,
    clearPasswordOnLeave = extState.clearPasswordOnLeave,
    rememberKeyFileLocation = extState.rememberKeyFileLocation,
    showKillAppOption = extState.showKillAppOption,
    // ISSUE-P3-68：重试节流开关与最长锁定时长（仓库直写项，userSettings 为单一真相源）
    unlockThrottleEnabled = userSettings.unlockThrottleEnabled,
    unlockLockoutMaxSeconds = userSettings.unlockLockoutMaxSeconds,
    // ISSUE-P1-22：软件级快速解锁降级的用户确认记录（常驻声明渲染依据）
    quickUnlockDowngradeAcknowledged = userSettings.quickUnlockDowngradeAcknowledged,

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
    searchMatchMode = extState.searchMatchMode,
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
    hasHealthScanned = healthState.hasScanned,

    // 8. 调试日志
    debugLogEnabled = extState.debugLogEnabled,
    verboseSyncLog = extState.verboseSyncLog,
    debugLogLines = debugLogLines
)

/**
 * 活动库文件头 → 设置页显示值的映射（ISSUE-P2-19 / P3-59）。
 *
 * 此前「密码库与加密」页的算法/KDF/参数全部来自 [DatabaseConfigUiState] 的硬编码占位默认值，
 * 与真实文件头不符（ ChaCha20 显示 vs AES 实际 / Argon2d·8轮 vs Argon2id·2轮 等）。
 * 本映射以活动库 [KdbxDatabase.header] 为单一真相源；未挂接会话时返回 null（UI 保持空态占位）。
 */
internal fun databaseConfigFromHeader(db: KdbxDatabase): DatabaseConfigUiState {
    val header = db.header
    val cipherLabel = when (header.cipherUuid) {
        KdbxConstants.Cipher.AES_256_CBC -> CipherLabels.AES_256_CBC
        // ISSUE-P3-92：ChaCha20 无 Poly1305 AEAD 标签（词汇表见 CipherLabels）
        KdbxConstants.Cipher.CHACHA20 -> CipherLabels.CHACHA20
        KdbxConstants.Cipher.TWOFISH -> CipherLabels.TWOFISH_CBC
        else -> ""
    }
    val kdf = header.kdfParameters
    val kdfLabel = when (kdf) {
        is KdfParameters.Aes -> KdfLabels.AES_KDF
        is KdfParameters.Argon2 -> if (kdf.type == KdfParameters.Argon2.Argon2Type.ARGON2D) KdfLabels.ARGON2D else KdfLabels.ARGON2ID
    }
    val compressionLabel = when (header.compression) {
        KdbxConstants.Compression.GZIP -> "GZip 压缩"
        else -> "无压缩"
    }
    return DatabaseConfigUiState(
        databaseName = db.databaseName,
        defaultUsername = db.defaultUserName,
        encryptionAlgorithm = cipherLabel,
        kdfAlgorithm = kdfLabel,
        argon2Iterations = if (kdf is KdfParameters.Argon2) kdf.iterations else 0L,
        argon2MemoryMb = if (kdf is KdfParameters.Argon2) kdf.memoryInBytes / (1024L * 1024L) else 0L,
        argon2Parallelism = if (kdf is KdfParameters.Argon2) kdf.parallelism else 0,
        compressionAlgorithm = compressionLabel,
        recycleBinEnabled = db.recycleBinEnabled
    )
}

/** 密码库配置的局部投影（原 `SettingsViewModel` 私有嵌套类型，ISSUE-P3-29 上移为同包 internal） */
internal data class DatabaseConfigUiState(
    val databaseName: String,
    val defaultUsername: String,
    /** 文件路径（ISSUE-P3-59：取自活动库记录；无活动库时为空，UI 显示「未设置」占位） */
    val databasePath: String = "",
    val encryptionAlgorithm: String,
    val kdfAlgorithm: String,
    val argon2Iterations: Long,
    val argon2MemoryMb: Long,
    val argon2Parallelism: Int,
    /** 压缩算法显示值（ISSUE-P2-19：真实值来自文件头 compressionFlags） */
    val compressionAlgorithm: String = "",
    val recycleBinEnabled: Boolean
)

/** 安全超时配置的局部投影（原 `SettingsViewModel` 私有嵌套类型，ISSUE-P3-29 上移为同包 internal） */
internal data class SecurityTimeoutUiState(
    val autoLockTimeoutSeconds: Int
)
