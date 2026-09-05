package com.keepasskey.app.ui.screens.settings

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.crypto.kdf.KdfBenchmark
import com.keepasskey.database.audit.HealthCheckEngine
import com.keepasskey.database.audit.PasswordRiskLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页面状态容器 ViewModel (涵盖 KeePass2Android 与 KeePassDX 2026 高保真全量偏好)
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vaultRepository: VaultRepository,
    private val syncCredentialsStore: SyncCredentialsStore,
    private val syncCoordinator: SyncCoordinator,
    private val debugLogBuffer: DebugLogBuffer,
    // 允许为 null 仅用于单测注入；生产 DI 注入 @ApplicationContext
    @ApplicationContext private val appContext: Context? = null
) : ViewModel() {

    companion object {
        private const val HEALTH_SCORE_BASE = 100
        private const val HEALTH_PENALTY_WEAK = 5
        private const val HEALTH_PENALTY_REUSED = 10
        private const val HEALTH_PENALTY_EXPIRED = 15

        // M2 整改：固定长度掩码，不随真实凭据长度变化
        private const val FIXED_PASSWORD_MASK = "••••••••••••"

        /** ActivityManager 不可得时的兜底应用堆上限（MiB） */
        private const val DEFAULT_HEAP_MB = 128

        // 标记当前应用进程生命周期内是否已执行过冷启动同步检测
        // 当软件被彻底杀死重启时，该静态字段重新变为 false，从而再次自动触发云端同步
        @Volatile
        private var hasCheckedColdStartSync = false
    }

    private val syncStateFlow = MutableStateFlow(
        SyncUiState(
            provider = CloudSyncProvider.WEBDAV,
            autoSyncEnabled = true,
            wifiOnlySync = true,
            isSyncing = false,
            syncFeedbackMessage = null
        )
    )

    private val healthStateFlow = MutableStateFlow(
        HealthCheckUiState(
            healthScore = 0,
            healthStatus = "未扫描",
            healthMessage = "点击重新扫描以评估密码库安全健康状态",
            weakPasswordCount = 0,
            reusedPasswordCount = 0,
            compromisedPasswordCount = 0,
            lastHealthScanTime = "未扫描",
            isHealthScanning = false
        )
    )

    private val autofillStateFlow = MutableStateFlow(
        AutofillUiState(
            credentialProviderEnabled = true,
            passkeySupportEnabled = true,
            autofillServiceEnabled = true
        )
    )

    private val databaseConfigStateFlow = MutableStateFlow(
        DatabaseConfigUiState(
            databaseName = "master_vault.kdbx",
            defaultUsername = "user@keepasskey.com",
            encryptionAlgorithm = "ChaCha20-Poly1305 (256-bit)",
            kdfAlgorithm = "Argon2id",
            argon2Iterations = 3L,
            argon2MemoryMb = 64L,
            argon2Parallelism = 4,
            recycleBinEnabled = true,
            tanExpiresOnUse = true,
            checkForDuplicateUuids = true,
            childDatabasesCount = 0
        )
    )

    private val securityTimeoutStateFlow = MutableStateFlow(
        SecurityTimeoutUiState(
            autoLockTimeoutSeconds = 0
        )
    )

    // KP2A 进阶特性与文件处理、快速解锁、显示、TOTP、调试日志状态集
    private val extendedSettingsFlow = MutableStateFlow(ExtendedSettings())

    // 调试日志真实缓冲快照（随刷新/清除动作更新）
    private val debugLogLinesFlow = MutableStateFlow(debugLogBuffer.snapshot())

    private data class ExtendedSettings(
        // 文件处理与进阶同步
        val useOfflineCache: Boolean = true,
        val periodicBackgroundSyncEnabled: Boolean = false,
        val periodicBackgroundSyncIntervalMinutes: Int = 30,
        val allowedWifiSsids: String = "",
        val createBackupBeforeSave: Boolean = true,
        val checkRemoteChangesBeforeSave: Boolean = true,
        val conflictResolution: ConflictResolution = ConflictResolution.AUTO_MERGE,
        val useFileTransactions: Boolean = true,
        val acceptAllCertificates: Boolean = false,
        val cleartextTrafficPermitted: Boolean = false,
        val webdavChunkedUpload: Boolean = false,
        val webdavChunkSizeMb: Int = 10,
        val preloadDatabaseEnabled: Boolean = true,

        // 安全锁定规则与环境
        val lockWhenScreenOff: Boolean = true,
        val lockWhenNavigateBack: Boolean = false,
        val clearPasswordOnLeave: Boolean = false,
        val rememberRecentFiles: Boolean = true,
        val rememberKeyFileLocation: Boolean = true,
        val showKillAppOption: Boolean = false,

        // 自动填充进阶
        val offerSaveCredentials: Boolean = true,
        val inlineSuggestionsEnabled: Boolean = true,
        val autoReturnFromQuery: Boolean = true,
        val autofillCopyTotp: Boolean = true,
        val autofillShowTotpNotification: Boolean = false,
        val skipDalVerification: Boolean = false,
        val overrideNoAutofill: Boolean = false,
        val disabledAutofillQueriesCount: Int = 0,

        // 显示与视觉进阶
        val maskPasswordsDefault: Boolean = true,
        val maskTotpDefault: Boolean = false,
        val showUnlockedNotification: Boolean = true,
        val showGroupInSearchResult: Boolean = true,
        val showGroupInEntry: Boolean = false,
        val listDensity: ListDensity = ListDensity.NORMAL,
        val autoActivateSearchOnOpen: Boolean = false,
        val iconSet: IconSetOption = IconSetOption.MATERIAL,

        // TOTP 规范字段映射
        val totpSeedFieldName: String = "TOTP Seed",
        val totpSettingsFieldName: String = "TOTP Settings",
        val defaultTotpStepSeconds: Int = 30,
        val defaultTotpDigits: Int = 6,

        // 调试日志
        val debugLogEnabled: Boolean = false,
        val verboseSyncLog: Boolean = false
    )

    private data class SyncUiState(
        val provider: CloudSyncProvider = CloudSyncProvider.WEBDAV,
        // M2 整改：默认值一律空串，杜绝示例凭据（mypassword123 / AKIA 示例密钥对）
        // 被静默保存为真实云端凭据
        val webdavUrl: String = "",
        val webdavUsername: String = "",
        val webdavPassword: String = "",
        val webdavRemotePath: String = "/keepasskey.kdbx",
        val s3Endpoint: String = "",
        val s3Bucket: String = "",
        val s3Region: String = "auto",
        val s3AccessKey: String = "",
        val s3SecretKey: String = "",
        val s3ObjectKey: String = "keepasskey.kdbx",
        val s3UsePathStyle: Boolean = false,
        val autoSyncEnabled: Boolean = true,
        val wifiOnlySync: Boolean = true,
        val isSyncing: Boolean = false,
        val syncFeedbackMessage: UiMessage? = null,
        // H1 整改：真实同步完成时刻文案（空串=本会话尚未同步成功过）
        val lastSyncTimeText: String = ""
    )

    private data class HealthCheckUiState(
        val healthScore: Int,
        val healthStatus: String,
        val healthMessage: String,
        val weakPasswordCount: Int,
        val reusedPasswordCount: Int,
        val compromisedPasswordCount: Int,
        val lastHealthScanTime: String,
        val isHealthScanning: Boolean
    )

    private data class AutofillUiState(
        val credentialProviderEnabled: Boolean,
        val passkeySupportEnabled: Boolean,
        val autofillServiceEnabled: Boolean
    )

    private data class DatabaseConfigUiState(
        val databaseName: String,
        val defaultUsername: String,
        val encryptionAlgorithm: String,
        val kdfAlgorithm: String,
        val argon2Iterations: Long,
        val argon2MemoryMb: Long,
        val argon2Parallelism: Int,
        val recycleBinEnabled: Boolean,
        val tanExpiresOnUse: Boolean,
        val checkForDuplicateUuids: Boolean,
        val childDatabasesCount: Int
    )

    private data class SecurityTimeoutUiState(
        val autoLockTimeoutSeconds: Int
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.getSettings(),
        syncStateFlow,
        healthStateFlow,
        combine(autofillStateFlow, databaseConfigStateFlow) { af, db -> Pair(af, db) },
        combine(securityTimeoutStateFlow, extendedSettingsFlow, debugLogLinesFlow) { sec, ext, logs -> Triple(sec, ext, logs) }
    ) { userSettings, syncState, healthState, (autofillState, dbState), (secState, extState, debugLogLines) ->
        SettingsUiState(
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
            childDatabasesCount = dbState.childDatabasesCount,

            // 2. 云端多协议同步与文件处理
            syncProvider = syncState.provider,
            webdavUrl = syncState.webdavUrl,
            webdavUsername = syncState.webdavUsername,
            webdavPassword = syncState.webdavPassword,
            // M2 整改：掩码采用固定长度，杜绝通过掩码长度推断真实密码长度
            webdavPasswordMasked = FIXED_PASSWORD_MASK,
            webdavRemotePath = syncState.webdavRemotePath,
            s3Endpoint = syncState.s3Endpoint,
            s3Bucket = syncState.s3Bucket,
            s3Region = syncState.s3Region,
            s3AccessKey = syncState.s3AccessKey,
            s3SecretKey = syncState.s3SecretKey,
            // M2 整改：掩码采用固定长度，杜绝通过掩码长度推断真实密钥长度
            s3SecretKeyMasked = FIXED_PASSWORD_MASK,
            s3ObjectKey = syncState.s3ObjectKey,
            s3UsePathStyle = syncState.s3UsePathStyle,
            autoSyncEnabled = syncState.autoSyncEnabled,
            wifiOnlySync = syncState.wifiOnlySync,
            isSyncing = syncState.isSyncing,
            syncFeedbackMessage = syncState.syncFeedbackMessage,
            syncLastTime = syncState.lastSyncTimeText.ifEmpty { "尚未同步" },
            useOfflineCache = extState.useOfflineCache,
            syncOnColdStart = userSettings.syncOnColdStart,
            periodicBackgroundSyncEnabled = extState.periodicBackgroundSyncEnabled,
            periodicBackgroundSyncIntervalMinutes = extState.periodicBackgroundSyncIntervalMinutes,
            allowedWifiSsids = extState.allowedWifiSsids,
            createBackupBeforeSave = extState.createBackupBeforeSave,
            checkRemoteChangesBeforeSave = extState.checkRemoteChangesBeforeSave,
            conflictResolution = extState.conflictResolution,
            useFileTransactions = extState.useFileTransactions,
            acceptAllCertificates = extState.acceptAllCertificates,
            cleartextTrafficPermitted = extState.cleartextTrafficPermitted,
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
            disabledAutofillQueriesCount = extState.disabledAutofillQueriesCount,

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
            lastHealthScanTime = healthState.lastHealthScanTime,
            isHealthScanning = healthState.isHealthScanning,

            // 8. 调试日志
            debugLogEnabled = extState.debugLogEnabled,
            verboseSyncLog = extState.verboseSyncLog,
            debugLogLines = debugLogLines
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    init {
        restoreSyncCredentials()
        // 离线开关联动：冷启动时把默认/持久化的离线偏好传导至同步协调器
        syncCoordinator.setOfflineMode(extendedSettingsFlow.value.useOfflineCache)
        checkAndTriggerColdStartSync()
    }

    private fun restoreSyncCredentials() {
        val store = syncCredentialsStore ?: return
        val savedProvider = store.loadProvider()
        val savedWebDav = store.loadWebDavConfig()
        val savedS3 = store.loadS3Config()
        syncStateFlow.update { cur ->
            cur.copy(
                provider = savedProvider,
                webdavUrl = savedWebDav?.url ?: cur.webdavUrl,
                webdavUsername = savedWebDav?.username ?: cur.webdavUsername,
                webdavPassword = savedWebDav?.password ?: cur.webdavPassword,
                webdavRemotePath = savedWebDav?.remotePath ?: cur.webdavRemotePath,
                s3Endpoint = savedS3?.endpoint ?: cur.s3Endpoint,
                s3Bucket = savedS3?.bucket ?: cur.s3Bucket,
                s3Region = savedS3?.region ?: cur.s3Region,
                s3AccessKey = savedS3?.accessKey ?: cur.s3AccessKey,
                s3SecretKey = savedS3?.secretKey ?: cur.s3SecretKey,
                s3ObjectKey = savedS3?.objectKey ?: cur.s3ObjectKey,
                s3UsePathStyle = savedS3?.usePathStyle ?: cur.s3UsePathStyle
            )
        }
    }

    private fun checkAndTriggerColdStartSync() {
        if (hasCheckedColdStartSync) return
        hasCheckedColdStartSync = true
        viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.getSettings().first()
                if (currentSettings.syncOnColdStart) {
                    triggerSync()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun setAppLanguage(language: com.keepasskey.app.data.repository.AppLanguage) {
        viewModelScope.launch {
            settingsRepository.setAppLanguage(language)
        }
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(themeMode)
        }
    }

    fun setThemePalette(themePalette: com.keepasskey.app.ui.theme.AppThemePalette) {
        viewModelScope.launch {
            settingsRepository.setThemePalette(themePalette)
        }
    }

    fun setOledBlackOptimization(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOledBlackOptimization(enabled)
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBiometricEnabled(enabled)
        }
    }

    fun setAutoLockBackground(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoLockBackground(enabled)
        }
    }

    fun setFlagSecureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setFlagSecureEnabled(enabled)
        }
    }

    fun setAutoClearClipboard(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoClearClipboard(enabled)
        }
    }

    fun setSyncProvider(provider: CloudSyncProvider) {
        syncCredentialsStore?.saveProvider(provider)
        syncStateFlow.update { it.copy(provider = provider) }
    }

    fun updateWebDavConfig(
        url: String,
        username: String,
        password: String = syncStateFlow.value.webdavPassword,
        remotePath: String
    ) {
        syncCredentialsStore?.saveWebDavConfig(url, username, password, remotePath)
        syncStateFlow.update {
            it.copy(
                webdavUrl = url,
                webdavUsername = username,
                webdavPassword = password,
                webdavRemotePath = remotePath
            )
        }
    }

    fun updateS3Config(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: String,
        secretKey: String = syncStateFlow.value.s3SecretKey,
        objectKey: String,
        usePathStyle: Boolean = syncStateFlow.value.s3UsePathStyle
    ) {
        syncCredentialsStore?.saveS3Config(endpoint, bucket, region, accessKey, secretKey, objectKey, usePathStyle)
        syncStateFlow.update {
            it.copy(
                s3Endpoint = endpoint,
                s3Bucket = bucket,
                s3Region = region,
                s3AccessKey = accessKey,
                s3SecretKey = secretKey,
                s3ObjectKey = objectKey,
                s3UsePathStyle = usePathStyle
            )
        }
    }

    fun setEncryptionAlgorithm(algorithm: String) {
        databaseConfigStateFlow.update { it.copy(encryptionAlgorithm = algorithm) }
    }

    fun setKdfAlgorithm(kdf: String) {
        databaseConfigStateFlow.update { it.copy(kdfAlgorithm = kdf) }
    }

    fun setArgon2Parameters(iterations: Long, memoryMb: Long, parallelism: Int) {
        databaseConfigStateFlow.update {
            it.copy(
                argon2Iterations = iterations,
                argon2MemoryMb = memoryMb,
                argon2Parallelism = parallelism
            )
        }
    }

    // ================= M6 整改：KDF 设备自适应基准真实接线 =================

    private val kdfBenchmarkFlow = MutableStateFlow(KdfBenchmarkUiState())

    /** KDF 基准实时状态（运行中 / 推荐参数 / 失败原因） */
    val kdfBenchmark: StateFlow<KdfBenchmarkUiState> = kdfBenchmarkFlow

    /**
     * 运行真实 KDF 基准测试（Dispatchers.Default，不阻塞主线程）：
     * 以设备应用堆上限为内存约束，实测 Argon2 单轮耗时后按 1s 目标外推推荐参数。
     */
    fun runKdfBenchmark() {
        if (kdfBenchmarkFlow.value.isRunning) return
        viewModelScope.launch(Dispatchers.Default) {
            kdfBenchmarkFlow.value = KdfBenchmarkUiState(isRunning = true)
            try {
                val recommendation = KdfBenchmark.benchmarkArgon2(
                    availableMemoryBytes = deviceAvailableMemoryBytes()
                )
                kdfBenchmarkFlow.value = KdfBenchmarkUiState(
                    isRunning = false,
                    recommendedIterations = recommendation.iterations,
                    recommendedMemoryMb = recommendation.memoryBytes / (1024L * 1024L),
                    recommendedParallelism = recommendation.parallelism
                )
            } catch (t: Throwable) {
                kdfBenchmarkFlow.value = KdfBenchmarkUiState(
                    isRunning = false,
                    errorMessage = t.message ?: "基准测试失败"
                )
            }
        }
    }

    /** Argon2 在 Java 堆分配内存矩阵，应用堆上限（memoryClass）即实际可用内存约束 */
    private fun deviceAvailableMemoryBytes(): Long {
        val activityManager = appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val heapMb = activityManager?.memoryClass ?: DEFAULT_HEAP_MB
        return heapMb * 1024L * 1024L
    }

    fun setAutoLockTimeout(seconds: Int) {
        securityTimeoutStateFlow.update {
            it.copy(autoLockTimeoutSeconds = seconds)
        }
        viewModelScope.launch {
            settingsRepository.setAutoLockTimeoutSeconds(seconds)
        }
    }

    fun setClipboardTimeout(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.setClipboardTimeout(seconds)
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        syncStateFlow.update { it.copy(autoSyncEnabled = enabled) }
    }

    fun setWifiOnlySync(enabled: Boolean) {
        syncStateFlow.update { it.copy(wifiOnlySync = enabled) }
    }

    fun setCredentialProviderEnabled(enabled: Boolean) {
        autofillStateFlow.update { it.copy(credentialProviderEnabled = enabled) }
    }

    fun setPasskeySupportEnabled(enabled: Boolean) {
        autofillStateFlow.update { it.copy(passkeySupportEnabled = enabled) }
    }

    fun setAutofillServiceEnabled(enabled: Boolean) {
        autofillStateFlow.update { it.copy(autofillServiceEnabled = enabled) }
    }

    fun setRecycleBinEnabled(enabled: Boolean) {
        databaseConfigStateFlow.update { it.copy(recycleBinEnabled = enabled) }
    }

    fun setTanExpiresOnUse(enabled: Boolean) {
        databaseConfigStateFlow.update { it.copy(tanExpiresOnUse = enabled) }
    }

    fun setCheckForDuplicateUuids(enabled: Boolean) {
        databaseConfigStateFlow.update { it.copy(checkForDuplicateUuids = enabled) }
    }

    fun setShowUsernameInList(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowUsernameInList(enabled)
        }
    }

    fun setShowOtpInList(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowOtpInList(enabled)
        }
    }

    fun setShowPasskeyBadge(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowPasskeyBadge(enabled)
        }
    }

    fun setShowUrlInList(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowUrlInList(enabled)
        }
    }

    fun setHideFabOnScroll(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHideFabOnScroll(enabled)
        }
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHapticFeedbackEnabled(enabled)
        }
    }

    fun setSyncOnColdStart(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSyncOnColdStart(enabled)
        }
    }

    // ========== 安全锁定规则控制 ==========
    fun setLockWhenScreenOff(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(lockWhenScreenOff = enabled) }
        viewModelScope.launch {
            settingsRepository.setLockWhenScreenOff(enabled)
        }
    }

    fun setLockWhenNavigateBack(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(lockWhenNavigateBack = enabled) }
    }

    fun setClearPasswordOnLeave(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(clearPasswordOnLeave = enabled) }
    }

    fun setRememberRecentFiles(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(rememberRecentFiles = enabled) }
    }

    fun setRememberKeyFileLocation(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(rememberKeyFileLocation = enabled) }
    }

    fun setShowKillAppOption(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(showKillAppOption = enabled) }
    }

    // ========== KP2A 扩展：表单自动填充与体验 ==========
    fun setOfferSaveCredentials(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(offerSaveCredentials = enabled) }
    }

    fun setInlineSuggestionsEnabled(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(inlineSuggestionsEnabled = enabled) }
    }

    fun setAutoReturnFromQuery(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(autoReturnFromQuery = enabled) }
    }

    fun setAutofillCopyTotp(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(autofillCopyTotp = enabled) }
    }

    fun setAutofillShowTotpNotification(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(autofillShowTotpNotification = enabled) }
    }

    fun setSkipDalVerification(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(skipDalVerification = enabled) }
    }

    fun setOverrideNoAutofill(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(overrideNoAutofill = enabled) }
    }

    // ========== KP2A 扩展：显示与外观交互 ==========
    fun setMaskPasswordsDefault(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(maskPasswordsDefault = enabled) }
    }

    fun setMaskTotpDefault(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(maskTotpDefault = enabled) }
    }

    fun setShowUnlockedNotification(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(showUnlockedNotification = enabled) }
    }

    fun setShowGroupInSearchResult(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(showGroupInSearchResult = enabled) }
    }

    fun setShowGroupInEntry(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(showGroupInEntry = enabled) }
    }

    fun setListDensity(density: ListDensity) {
        extendedSettingsFlow.update { it.copy(listDensity = density) }
    }

    fun setAutoActivateSearchOnOpen(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(autoActivateSearchOnOpen = enabled) }
    }

    fun setIconSet(iconSet: IconSetOption) {
        extendedSettingsFlow.update { it.copy(iconSet = iconSet) }
    }

    fun setShowAuthenticatorTab(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowAuthenticatorTab(enabled)
        }
    }

    fun setShowGeneratorTab(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowGeneratorTab(enabled)
        }
    }

    // ========== KP2A 扩展：文件处理与高级同步策略 ==========
    fun setUseOfflineCache(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(useOfflineCache = enabled) }
        // 离线开关联动：实时传导至同步引擎决策树（SyncEngine.isOffline）
        syncCoordinator.setOfflineMode(enabled)
    }

    fun setPeriodicBackgroundSyncEnabled(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(periodicBackgroundSyncEnabled = enabled) }
    }

    fun setPeriodicBackgroundSyncInterval(minutes: Int) {
        extendedSettingsFlow.update { it.copy(periodicBackgroundSyncIntervalMinutes = minutes) }
    }

    fun setAllowedWifiSsids(ssids: String) {
        extendedSettingsFlow.update { it.copy(allowedWifiSsids = ssids) }
    }

    fun setCreateBackupBeforeSave(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(createBackupBeforeSave = enabled) }
    }

    fun setCheckRemoteChangesBeforeSave(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(checkRemoteChangesBeforeSave = enabled) }
    }

    fun setConflictResolution(resolution: ConflictResolution) {
        extendedSettingsFlow.update { it.copy(conflictResolution = resolution) }
    }

    fun setUseFileTransactions(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(useFileTransactions = enabled) }
    }

    fun setAcceptAllCertificates(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(acceptAllCertificates = enabled) }
    }

    fun setCleartextTrafficPermitted(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(cleartextTrafficPermitted = enabled) }
    }

    fun setWebdavChunkedUpload(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(webdavChunkedUpload = enabled) }
    }

    fun setWebdavChunkSizeMb(sizeMb: Int) {
        extendedSettingsFlow.update { it.copy(webdavChunkSizeMb = sizeMb) }
    }

    fun setPreloadDatabaseEnabled(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(preloadDatabaseEnabled = enabled) }
    }

    // ========== KP2A 扩展：TOTP 规范映射 ==========
    fun updateTotpFieldMapping(seedField: String, settingsField: String, stepSeconds: Int, digits: Int) {
        extendedSettingsFlow.update {
            it.copy(
                totpSeedFieldName = seedField,
                totpSettingsFieldName = settingsField,
                defaultTotpStepSeconds = stepSeconds,
                defaultTotpDigits = digits
            )
        }
    }

    // ========== KP2A 扩展：调试日志 ==========
    fun setDebugLogEnabled(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(debugLogEnabled = enabled) }
    }

    fun setVerboseSyncLog(enabled: Boolean) {
        extendedSettingsFlow.update { it.copy(verboseSyncLog = enabled) }
    }

    fun triggerSync() {
        if (syncStateFlow.value.isSyncing) return
        val provider = syncStateFlow.value.provider
        viewModelScope.launch {
            syncStateFlow.update {
                it.copy(
                    isSyncing = true,
                    syncFeedbackMessage = UiMessage(R.string.sync_feedback_connecting, listOf(provider.protocol))
                )
            }

            val outcome = syncCoordinator.syncNow()
            val feedback = when (outcome) {
                is SyncOutcome.UpToDate -> UiMessage(R.string.sync_feedback_done, listOf(provider.protocol))
                is SyncOutcome.UploadedLocal -> UiMessage(R.string.sync_feedback_uploaded, listOf(provider.protocol))
                is SyncOutcome.MergedAndUploaded -> UiMessage(R.string.sync_feedback_merged, listOf(provider.protocol))
                is SyncOutcome.ConflictNeedsUser -> UiMessage(R.string.sync_feedback_conflict)
                is SyncOutcome.Offline -> UiMessage(R.string.sync_feedback_offline)
                is SyncOutcome.Error -> UiMessage(R.string.sync_feedback_error, listOf(outcome.message))
            }
            // H1 整改：syncLastTime 由真实同步完成时刻填充，不再展示写死的演示文案
            val syncedNow = outcome is SyncOutcome.UpToDate ||
                    outcome is SyncOutcome.UploadedLocal ||
                    outcome is SyncOutcome.MergedAndUploaded
            syncStateFlow.update {
                it.copy(
                    isSyncing = false,
                    syncFeedbackMessage = feedback,
                    lastSyncTimeText = if (syncedNow) formatSyncTimestamp() else syncStateFlow.value.lastSyncTimeText
                )
            }
        }
    }

    /** 将本次同步完成时刻格式化为「今天/昨天/M月d日 HH:mm」本地文案 */
    private fun formatSyncTimestamp(): String {
        val dateTime = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(java.time.ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        val datePrefix = when (dateTime.toLocalDate()) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> dateTime.format(java.time.format.DateTimeFormatter.ofPattern("M月d日"))
        }
        return "$datePrefix ${dateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
    }

    fun testSyncConnection() {
        if (syncStateFlow.value.isSyncing) return
        val provider = syncStateFlow.value.provider
        viewModelScope.launch {
            syncStateFlow.update {
                it.copy(
                    isSyncing = true,
                    syncFeedbackMessage = UiMessage(R.string.sync_feedback_connecting, listOf(provider.protocol))
                )
            }
            val result = syncCoordinator.testConnection()
            val feedback = if (result.isSuccess) {
                UiMessage(R.string.sync_feedback_done, listOf(provider.protocol))
            } else {
                UiMessage(R.string.sync_feedback_error, listOf(result.exceptionOrNull()?.message ?: "连接失败"))
            }
            syncStateFlow.update {
                it.copy(
                    isSyncing = false,
                    syncFeedbackMessage = feedback
                )
            }
        }
    }

    fun clearSyncFeedbackMessage() {
        syncStateFlow.update { it.copy(syncFeedbackMessage = null) }
    }

    // ========== KP2A 扩展：调试日志（真实进程内缓冲） ==========
    fun refreshDebugLogs() {
        debugLogLinesFlow.value = debugLogBuffer.snapshot()
    }

    fun clearDebugLogs() {
        debugLogBuffer.clear()
        debugLogLinesFlow.value = emptyList()
    }

    fun rescanHealth() {
        if (healthStateFlow.value.isHealthScanning) return
        viewModelScope.launch {
            healthStateFlow.update { it.copy(isHealthScanning = true) }
            try {
                val entries = vaultRepository.getKdbxEntries()
                val issues = HealthCheckEngine.analyzeEntries(entries)

                val weakCount = issues.count { it.riskLevel == PasswordRiskLevel.WEAK }
                val reusedCount = issues.count { it.riskLevel == PasswordRiskLevel.REUSED }
                val expiredCount = issues.count { it.riskLevel == PasswordRiskLevel.EXPIRED }

                val calculatedScore = (HEALTH_SCORE_BASE -
                        weakCount * HEALTH_PENALTY_WEAK -
                        reusedCount * HEALTH_PENALTY_REUSED -
                        expiredCount * HEALTH_PENALTY_EXPIRED).coerceIn(0, 100)

                val status = when {
                    calculatedScore >= 90 -> "优秀"
                    calculatedScore >= 70 -> "良好"
                    calculatedScore >= 50 -> "一般"
                    else -> "需改进"
                }

                val nowTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.getDefault())
                    .format(java.time.LocalTime.now())
                val lastScanText = "今天 $nowTime"

                val message = when {
                    expiredCount > 0 -> "发现 $expiredCount 个已过期凭据，$weakCount 个弱密码，$reusedCount 个复用"
                    weakCount == 0 && reusedCount == 0 -> "全库扫描完成，未发现弱密码与复用"
                    reusedCount > 0 && weakCount > 0 -> "发现 $weakCount 个弱密码，$reusedCount 个重复使用"
                    reusedCount > 0 -> "发现 $reusedCount 个密码重复使用，建议启用唯一密码"
                    else -> "发现 $weakCount 个弱密码，建议提升密码复杂度"
                }

                healthStateFlow.update {
                    it.copy(
                        isHealthScanning = false,
                        healthScore = calculatedScore,
                        healthStatus = status,
                        healthMessage = message,
                        weakPasswordCount = weakCount,
                        reusedPasswordCount = reusedCount,
                        compromisedPasswordCount = 0,
                        lastHealthScanTime = lastScanText
                    )
                }
            } catch (e: Exception) {
                healthStateFlow.update {
                    it.copy(
                        isHealthScanning = false,
                        healthMessage = "健康扫描失败: ${e.message}"
                    )
                }
            }
        }
    }
}
