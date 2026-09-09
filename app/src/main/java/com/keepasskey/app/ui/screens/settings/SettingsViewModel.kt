package com.keepasskey.app.ui.screens.settings

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.crypto.kdf.KdfBenchmark
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    // TASK-12 整改：进阶偏好持久化仓库（冷启动不再静默回落默认值）
    private val extendedSettingsStore: com.keepasskey.app.data.repository.ExtendedSettingsStore,
    // TASK-08 整改：周期后台同步调度器（设置变更即时生效）
    private val periodicSyncScheduler: com.keepasskey.app.sync.PeriodicSyncScheduler,
    // TASK-44 整改：自动填充黑名单（真实包名条目，替代无写入方的禁用计数）
    private val autofillBlocklistStore: com.keepasskey.app.data.repository.AutofillBlocklistStore,
    // TASK-47 整改：已泄露密码检测（HIBP k-匿名范围查询，由 breachCheckEnabled 开关门控）
    private val breachCheckCoordinator: com.keepasskey.app.data.breach.BreachCheckCoordinator,
    // 允许为 null 仅用于单测注入；生产 DI 注入 @ApplicationContext
    @ApplicationContext private val appContext: Context? = null,
    // TASK-21：非 Compose 层文案资源解析通道（生产经 appContext 转发；单测注入假实现）
    private val stringsProvider: StringsProvider? = null
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"

        /** ActivityManager 不可得时的兜底应用堆上限（MiB） */
        private const val DEFAULT_HEAP_MB = 128

        // 标记当前应用进程生命周期内是否已执行过冷启动同步检测
        // 当软件被彻底杀死重启时，该静态字段重新变为 false，从而再次自动触发云端同步
        @Volatile
        private var hasCheckedColdStartSync = false
    }

    // TASK-21 拆分：文案解析通道与领域控制器（同步/健康/导出），ViewModel 保留状态编排
    private val strings: StringsProvider = stringsProvider
        ?: appContext?.let { ctx -> StringsProvider { id, args -> ctx.getString(id, *args) } }
        ?: StringsProvider { _, _ -> "" }

    private val syncController = SettingsSyncController(
        syncCredentialsStore, syncCoordinator, extendedSettingsStore, strings, viewModelScope
    )
    private val healthController = SettingsHealthController(
        vaultRepository = vaultRepository,
        breachCheckCoordinator = breachCheckCoordinator,
        strings = strings,
        breachCheckEnabled = { extendedSettingsFlow.value.breachCheckEnabled },
        scope = viewModelScope
    )
    private val exportController = SettingsExportController(
        vaultRepository, debugLogBuffer, appContext, strings, viewModelScope
    )

    // TASK-21 拆分：同步状态流与凭据明文预填通道由 [SettingsSyncController] 承载
    val webdavPasswordPrefill: StateFlow<CharArray?> get() = syncController.webdavPasswordPrefill

    val s3SecretKeyPrefill: StateFlow<CharArray?> get() = syncController.s3SecretKeyPrefill

    /** Wave 15 整改：用户开始编辑密码后终结预填通道生命周期（防旋转后旧值回写覆盖用户输入） */
    fun clearWebDavPasswordPrefill() = syncController.clearWebDavPasswordPrefill()

    fun clearS3SecretKeyPrefill() = syncController.clearS3SecretKeyPrefill()

    private val autofillStateFlow = MutableStateFlow(
        AutofillUiState(
            credentialProviderEnabled = true,
            passkeySupportEnabled = true,
            autofillServiceEnabled = true
        )
    )

    private val databaseConfigStateFlow = MutableStateFlow(
        DatabaseConfigUiState(
            databaseName = "",
            defaultUsername = "",
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
    // TASK-12 整改：初值自持久化仓库恢复（原为纯内存回显，冷启动静默回落默认值）
    private val extendedSettingsFlow = MutableStateFlow(extendedSettingsStore.load())

    // 调试日志真实缓冲快照（随刷新/清除动作更新）
    private val debugLogLinesFlow = MutableStateFlow(debugLogBuffer.snapshot())

    /**
     * TASK-12 整改：进阶偏好统一变更通道——内存 Flow 更新与持久化落盘原子完成，
     * 杜绝任何 setter 只改内存不落盘的「回显漂移」。
     */
    private fun updateExtended(transform: (ExtendedSettings) -> ExtendedSettings) {
        extendedSettingsFlow.update(transform)
        extendedSettingsStore.save(extendedSettingsFlow.value)
    }

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
        syncController.state,
        healthController.state,
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
            webdavRemotePath = syncState.webdavRemotePath,
            s3Endpoint = syncState.s3Endpoint,
            s3Bucket = syncState.s3Bucket,
            s3Region = syncState.s3Region,
            s3AccessKey = syncState.s3AccessKey,
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
            debugLogLines = debugLogLines
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    init {
        // TASK-12 整改：wifiOnlySync 持久化恢复（周期同步网络约束的消费方）
        syncController.updateWifiOnlySync(extendedSettingsStore.loadWifiOnlySync())
        syncController.restoreSyncCredentials()
        // 离线开关联动：冷启动时把默认/持久化的离线偏好传导至同步协调器
        syncCoordinator.setOfflineMode(extendedSettingsFlow.value.useOfflineCache)
        checkAndTriggerColdStartSync()

        // 动态订阅活动数据库，更新设置页数据库名称
        viewModelScope.launch {
            vaultRepository.getDatabases().collect { databases ->
                val active = databases.firstOrNull { it.isActive } ?: databases.firstOrNull()
                databaseConfigStateFlow.update {
                    it.copy(
                        databaseName = active?.name.orEmpty()
                    )
                }
            }
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

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDynamicColorEnabled(enabled)
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

    // ========== TASK-21 拆分：同步配置/动作委托 [SettingsSyncController] ==========

    fun setSyncProvider(provider: CloudSyncProvider) = syncController.setSyncProvider(provider)

    /**
     * Wave 15 整改：密码以 [CharArray] 借用语义提交（消费后立即擦除），明文不回写状态流；
     * 返回保存结果（https 校验拒绝或凭据封印失败时如实回传 false 并上浮反馈）。
     */
    fun updateWebDavConfig(
        url: String,
        username: String,
        password: CharArray,
        remotePath: String
    ): Boolean = syncController.updateWebDavConfig(url, username, password, remotePath)

    /** Wave 15 整改：SecretKey 以 [CharArray] 借用语义提交（语义同 [updateWebDavConfig]） */
    fun updateS3Config(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: String,
        secretKey: CharArray,
        objectKey: String,
        usePathStyle: Boolean = syncController.state.value.s3UsePathStyle
    ): Boolean = syncController.updateS3Config(endpoint, bucket, region, accessKey, secretKey, objectKey, usePathStyle)

    fun setAutoSyncEnabled(enabled: Boolean) = syncController.setAutoSyncEnabled(enabled)

    fun setWifiOnlySync(enabled: Boolean) {
        syncController.updateWifiOnlySync(enabled)
        // TASK-12 整改：持久化（原为纯内存回显）
        extendedSettingsStore.saveWifiOnlySync(enabled)
        // TASK-08 整改：网络约束变更即时生效（仅周期同步开启时）
        reschedulePeriodicSyncIfNeeded()
    }

    fun triggerSync() = syncController.triggerSync()

    fun testSyncConnection() = syncController.testSyncConnection()

    fun clearSyncFeedbackMessage() = syncController.clearSyncFeedbackMessage()

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

    /**
     * P0-3 整改：真实调用仓库修改当前数据库的主密钥
     */
    suspend fun changeMasterPassword(newPasswordChars: CharArray): com.keepasskey.core.result.KdbxResult<Unit> {
        return vaultRepository.changeMasterPassword(newPasswordChars)
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
                    errorMessage = t.message ?: strings.get(R.string.kdf_benchmark_failed)
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
        updateExtended { it.copy(lockWhenScreenOff = enabled) }
        viewModelScope.launch {
            settingsRepository.setLockWhenScreenOff(enabled)
        }
    }

    fun setLockWhenNavigateBack(enabled: Boolean) {
        updateExtended { it.copy(lockWhenNavigateBack = enabled) }
    }

    fun setClearPasswordOnLeave(enabled: Boolean) {
        updateExtended { it.copy(clearPasswordOnLeave = enabled) }
    }

    fun setRememberRecentFiles(enabled: Boolean) {
        updateExtended { it.copy(rememberRecentFiles = enabled) }
    }

    fun setRememberKeyFileLocation(enabled: Boolean) {
        updateExtended { it.copy(rememberKeyFileLocation = enabled) }
    }

    fun setShowKillAppOption(enabled: Boolean) {
        updateExtended { it.copy(showKillAppOption = enabled) }
    }

    // ========== KP2A 扩展：表单自动填充与体验 ==========
    fun setOfferSaveCredentials(enabled: Boolean) {
        updateExtended { it.copy(offerSaveCredentials = enabled) }
    }

    fun setInlineSuggestionsEnabled(enabled: Boolean) {
        updateExtended { it.copy(inlineSuggestionsEnabled = enabled) }
    }

    fun setAutoReturnFromQuery(enabled: Boolean) {
        updateExtended { it.copy(autoReturnFromQuery = enabled) }
    }

    fun setAutofillCopyTotp(enabled: Boolean) {
        updateExtended { it.copy(autofillCopyTotp = enabled) }
    }

    fun setAutofillShowTotpNotification(enabled: Boolean) {
        updateExtended { it.copy(autofillShowTotpNotification = enabled) }
    }

    fun setSkipDalVerification(enabled: Boolean) {
        updateExtended { it.copy(skipDalVerification = enabled) }
    }

    fun setOverrideNoAutofill(enabled: Boolean) {
        updateExtended { it.copy(overrideNoAutofill = enabled) }
    }

    // ========== TASK-44：自动填充黑名单（真实条目生命周期） ==========

    /**
     * 自动填充黑名单快照（按包名升序）。黑名单不进 [uiState] 的 combine 链——
     * 其更新频率与生命周期独立于设置项，单独下发可避免 5 流 combine 的元组膨胀。
     */
    val autofillBlockedPackages: StateFlow<List<String>> = autofillBlocklistStore.blockedPackages

    /**
     * 将应用加入黑名单。
     * @return true=新增成功；false=包名非法或已在黑名单中（调用方据此如实提示，不谎报成功）
     */
    fun blockAutofillPackage(packageName: String): Boolean = autofillBlocklistStore.add(packageName)

    /** 将应用移出黑名单（删除动作）。@return true=移除成功；false=包名非法或本就不在黑名单中 */
    fun unblockAutofillPackage(packageName: String): Boolean =
        autofillBlocklistStore.remove(packageName)

    // ========== KP2A 扩展：显示与外观交互 ==========
    fun setMaskPasswordsDefault(enabled: Boolean) {
        updateExtended { it.copy(maskPasswordsDefault = enabled) }
    }

    fun setMaskTotpDefault(enabled: Boolean) {
        updateExtended { it.copy(maskTotpDefault = enabled) }
    }

    fun setShowUnlockedNotification(enabled: Boolean) {
        updateExtended { it.copy(showUnlockedNotification = enabled) }
    }

    fun setShowGroupInSearchResult(enabled: Boolean) {
        updateExtended { it.copy(showGroupInSearchResult = enabled) }
    }

    fun setShowGroupInEntry(enabled: Boolean) {
        updateExtended { it.copy(showGroupInEntry = enabled) }
    }

    fun setListDensity(density: ListDensity) {
        updateExtended { it.copy(listDensity = density) }
    }

    fun setAutoActivateSearchOnOpen(enabled: Boolean) {
        updateExtended { it.copy(autoActivateSearchOnOpen = enabled) }
    }

    fun setIconSet(iconSet: IconSetOption) {
        updateExtended { it.copy(iconSet = iconSet) }
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
        updateExtended { it.copy(useOfflineCache = enabled) }
        // 离线开关联动：实时传导至同步引擎决策树（SyncEngine.isOffline）
        syncCoordinator.setOfflineMode(enabled)
    }

    fun setPeriodicBackgroundSyncEnabled(enabled: Boolean) {
        updateExtended { it.copy(periodicBackgroundSyncEnabled = enabled) }
        // TASK-08 整改：开关接入 WorkManager 唯一周期任务（开启注册 / 关闭取消）
        periodicSyncScheduler.reschedule(
            enabled = enabled,
            intervalMinutes = extendedSettingsFlow.value.periodicBackgroundSyncIntervalMinutes,
            wifiOnly = syncController.currentWifiOnlySync()
        )
    }

    fun setPeriodicBackgroundSyncInterval(minutes: Int) {
        updateExtended { it.copy(periodicBackgroundSyncIntervalMinutes = minutes) }
        // TASK-08 整改：间隔变更经 UPDATE 策略原子更新周期任务
        reschedulePeriodicSyncIfNeeded()
    }

    /** TASK-08：周期同步开启时按最新偏好重排任务（wifiOnly/间隔变更共用入口） */
    private fun reschedulePeriodicSyncIfNeeded() {
        val settings = extendedSettingsFlow.value
        if (settings.periodicBackgroundSyncEnabled) {
            periodicSyncScheduler.reschedule(
                enabled = true,
                intervalMinutes = settings.periodicBackgroundSyncIntervalMinutes,
                wifiOnly = syncController.currentWifiOnlySync()
            )
        }
    }

    fun setAllowedWifiSsids(ssids: String) {
        updateExtended { it.copy(allowedWifiSsids = ssids) }
    }

    fun setCreateBackupBeforeSave(enabled: Boolean) {
        updateExtended { it.copy(createBackupBeforeSave = enabled) }
    }

    fun setCheckRemoteChangesBeforeSave(enabled: Boolean) {
        updateExtended { it.copy(checkRemoteChangesBeforeSave = enabled) }
    }

    fun setConflictResolution(resolution: ConflictResolution) {
        updateExtended { it.copy(conflictResolution = resolution) }
    }

    fun setUseFileTransactions(enabled: Boolean) {
        updateExtended { it.copy(useFileTransactions = enabled) }
    }

    fun setWebdavChunkedUpload(enabled: Boolean) {
        updateExtended { it.copy(webdavChunkedUpload = enabled) }
    }

    fun setWebdavChunkSizeMb(sizeMb: Int) {
        updateExtended { it.copy(webdavChunkSizeMb = sizeMb) }
    }

    fun setPreloadDatabaseEnabled(enabled: Boolean) {
        updateExtended { it.copy(preloadDatabaseEnabled = enabled) }
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

    // ========== TASK-47：已泄露密码检测（联网，默认关闭） ==========

    /**
     * 开启 / 关闭已泄露密码检测（默认关闭）。
     *
     * 关闭态健康度扫描**不发起任何网络请求**，「已泄露密码」指标无值（UI 如实展示「未启用」）；
     * 开启后重新扫描才会向公开泄露库发起 k-匿名范围查询（仅上送密码 SHA-1 前 5 位）。
     */
    fun setBreachCheckEnabled(enabled: Boolean) {
        updateExtended { it.copy(breachCheckEnabled = enabled) }
    }

    // ========== KP2A 扩展：调试日志 ==========
    fun setDebugLogEnabled(enabled: Boolean) {
        updateExtended { it.copy(debugLogEnabled = enabled) }
    }

    fun setVerboseSyncLog(enabled: Boolean) {
        updateExtended { it.copy(verboseSyncLog = enabled) }
    }

    // ========== KP2A 扩展：调试日志（真实进程内缓冲） ==========
    fun refreshDebugLogs() {
        debugLogLinesFlow.value = debugLogBuffer.snapshot()
    }

    fun clearDebugLogs() {
        debugLogBuffer.clear()
        debugLogLinesFlow.value = emptyList()
    }

    // ========== TASK-21 拆分：导出/模板/调试日志委托 [SettingsExportController] ==========

    /** SAF 调试日志导出结果反馈（成功/失败），由 Screen 层消费后清除 */
    val debugExportFeedback: StateFlow<UiMessage?> get() = exportController.debugExportFeedback

    fun exportDebugLogs(targetUri: Uri) = exportController.exportDebugLogs(targetUri)

    fun clearDebugExportFeedback() = exportController.clearDebugExportFeedback()

    /** 导出/模板动作结果反馈（成功/失败），由 Screen 层消费后清除 */
    val exportFeedback: StateFlow<UiMessage?> get() = exportController.exportFeedback

    fun clearExportFeedback() = exportController.clearExportFeedback()

    /** 导出当前数据库为 KDBX 完整副本并写入 SAF 目标 Uri */
    fun exportKdbxTo(targetUri: Uri) = exportController.exportKdbxTo(targetUri)

    /** 导出当前数据库为 KeePass 2.x 兼容明文 XML 并写入 SAF 目标 Uri */
    fun exportVaultXmlTo(targetUri: Uri) = exportController.exportVaultXmlTo(targetUri)

    /** 导出会话绑定的密钥文件并写入 SAF 目标 Uri */
    fun exportKeyFileTo(targetUri: Uri) = exportController.exportKeyFileTo(targetUri)

    /** 安装条目模板库（真实创建「模板」分组与 5 个模板条目） */
    fun installEntryTemplates() = exportController.installEntryTemplates()

    // ========== TASK-21 拆分：健康检查委托 [SettingsHealthController] ==========

    fun rescanHealth() = healthController.rescanHealth()

    override fun onCleared() {
        // Wave 15 整改：ViewModel 销毁时擦除凭据预填通道中的明文驻留
        syncController.clearWebDavPasswordPrefill()
        syncController.clearS3SecretKeyPrefill()
        super.onCleared()
    }
}
