package com.keepasskey.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.childdb.ChildDatabaseSessionManager
import com.keepasskey.app.data.importer.ImportSource
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.RuntimeIntegrityDetector
import com.keepasskey.app.security.RuntimeIntegrityReport
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.app.ui.screens.importer.VaultImportController
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.AppThemePalette
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页面状态容器 ViewModel (涵盖 KeePass2Android 与 KeePassDX 2026 高保真全量偏好)
 *
 * ## ISSUE-P3-29 拆分说明
 *
 * 原 1120 行巨型类已按职责拆为五个协作者，本类只保留「输入流编排 + 对外 API 门面」：
 * - 纯投影与状态流装配：[buildSettingsUiState] / [settingsUiStateFlow]（`SettingsUiStateProjection.kt`）
 * - 子库挂载：[SettingsChildDatabaseController]
 * - 基础偏好与局部状态：[SettingsPreferencesController]
 * - 进阶偏好：[SettingsExtendedPreferencesController]
 * - KDF 基准：[SettingsKdfBenchmarkController]
 * （同步 / 健康 / 导出三个控制器为 TASK-21 既有拆分，本次未动）
 *
 * 拆分为**纯结构性**：公开 API 与状态输出零变化，敏感语义（凭据借用副本用毕清零、
 * 预填通道销毁擦除）原样保留。
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
    // ISSUE-P2-11 (ZT-16)：会话级「保存前创建 .bak 备份」偏好下发通道。
    // 生产 DI 注入由 DatabaseModule 提供的唯一会话实例；允许为 null 仅用于既有单测注入
    // （未提供时会话保持自身默认偏好 true，不影响其他断言）。
    private val databaseSession: DatabaseSession? = null,
    // 允许为 null 仅用于单测注入；生产 DI 注入 @ApplicationContext
    @ApplicationContext private val appContext: Context? = null,
    // TASK-21：非 Compose 层文案资源解析通道（生产经 appContext 转发；单测注入假实现）
    private val stringsProvider: StringsProvider? = null,
    // ISSUE-P2-08（ZT-13）：运行完整性扫描快照下发通道（UI 风险提示卡片）。
    // 允许为 null 仅用于既有单测注入；生产 DI 注入单例 RuntimeIntegrityDetector。
    private val runtimeIntegrityDetector: RuntimeIntegrityDetector? = null,
    // ISSUE-P3-19（ZT-43d）：明文导入控制器（@Singleton，自带 StateFlow，内部完成
    // 「SAF 读字节 → 解析 → 落库 → 出报告」全链路）。允许为 null 仅用于既有单测注入；
    // 缺失时 [importState] 恒为 Idle —— 即不呈现任何导入反馈，绝不产生假进度/假回执。
    private val vaultImportController: VaultImportController? = null,
    // ISSUE-P3-20：子库挂载会话管理器（@Singleton，核心层已完成）。允许为 null 仅用于既有
    // 单测注入；缺失时 `childDatabasesCount` 回落 0（**不谎报**），子库对话框如实禁用全部控件。
    private val childDatabaseSessionManager: ChildDatabaseSessionManager? = null,
    // ISSUE-P3-20：SAF 持久化读授权 + 密钥文件字节读取通道（复用解锁特性既有契约，
    // 生产 DI 经 KeyFileAccessModule 注入 SafKeyFileAccess）。允许为 null 仅用于既有单测注入。
    private val keyFileAccess: KeyFileAccess? = null
) : ViewModel() {

    companion object {
        /** UI 状态流停止订阅后的保活窗口（毫秒）：与既有 [uiState] 保持一致 */
        private const val STATE_SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }

    // TASK-21 拆分：文案解析通道与领域控制器（同步/健康/导出），ViewModel 保留状态编排
    private val strings: StringsProvider = stringsProvider
        ?: appContext?.let { ctx -> StringsProvider { id, args -> ctx.getString(id, *args) } }
        ?: StringsProvider { _, _ -> "" }

    // ===== ISSUE-P3-19：明文导入（委托 [SettingsImportPresenter]，缺失控制器时恒 Idle） =====
    private val importPresenter = SettingsImportPresenter(vaultImportController)

    /** 导入状态（Idle / Parsing / Done / Failed）。无控制器时恒为 Idle，UI 不渲染任何导入反馈。 */
    val importState: StateFlow<ImportUiState> get() = importPresenter.state

    /** 按数据源 + SAF Uri 启动一次导入：解析 → 落库 → 出报告，全部由控制器负责。 */
    fun startImport(source: ImportSource, uri: Uri) = importPresenter.start(source, uri)

    /** 关闭导入结果报告对话框。 */
    fun dismissImportReport() = importPresenter.dismissReport()

    // ===== ISSUE-P3-20：子库挂载（UI 接线，委托 [SettingsChildDatabaseController]） =====
    private val childDatabaseController = SettingsChildDatabaseController(
        manager = childDatabaseSessionManager,
        keyFileAccess = keyFileAccess,
        debugLogBuffer = debugLogBuffer,
        scope = viewModelScope,
        stateSubscribeTimeoutMillis = STATE_SUBSCRIBE_TIMEOUT_MILLIS
    )

    /** 子库挂载面板状态；控制器缺失时如实 `available = false`（UI 整体禁用），不呈现假入口。 */
    val childDatabaseState: StateFlow<ChildDatabaseUiState> get() = childDatabaseController.state

    fun mountChildDatabase(alias: String, sourceUri: String, passwordChars: CharArray, keyFileUri: String?) =
        childDatabaseController.mount(alias, sourceUri, passwordChars, keyFileUri)
    fun unlockChildDatabase(mountId: String, passwordChars: CharArray, keyFileUri: String?) =
        childDatabaseController.unlock(mountId, passwordChars, keyFileUri)
    fun unmountChildDatabase(mountId: String) = childDatabaseController.unmount(mountId)
    fun dismissChildDatabaseFeedback() = childDatabaseController.dismissFeedback()

    // ===== TASK-21：同步 / 健康 / 导出控制器 =====
    private val syncController = SettingsSyncController(
        syncCredentialsStore, syncCoordinator, extendedSettingsStore, strings, viewModelScope
    )

    private val extendedPreferences = SettingsExtendedPreferencesController(
        extendedSettingsStore = extendedSettingsStore,
        syncCoordinator = syncCoordinator,
        periodicSyncScheduler = periodicSyncScheduler,
        databaseSession = databaseSession,
        currentWifiOnlySync = { syncController.currentWifiOnlySync() },
        updateWifiOnlySync = { syncController.updateWifiOnlySync(it) },
        persistLockWhenScreenOff = { enabled ->
            viewModelScope.launch { settingsRepository.setLockWhenScreenOff(enabled) }
        }
    )

    private val healthController = SettingsHealthController(
        vaultRepository = vaultRepository,
        breachCheckCoordinator = breachCheckCoordinator,
        strings = strings,
        breachCheckEnabled = { extendedPreferences.settings.value.breachCheckEnabled },
        scope = viewModelScope
    )

    private val exportController = SettingsExportController(
        vaultRepository, debugLogBuffer, appContext, strings, viewModelScope
    )

    private val preferences = SettingsPreferencesController(
        settingsRepository = settingsRepository,
        vaultRepository = vaultRepository,
        autofillBlocklistStore = autofillBlocklistStore,
        debugLogBuffer = debugLogBuffer,
        scope = viewModelScope
    )

    private val kdfBenchmarkController = SettingsKdfBenchmarkController(
        appContext = appContext, strings = strings, scope = viewModelScope
    )

    // TASK-21 拆分：同步状态流与凭据明文预填通道由 [SettingsSyncController] 承载
    val webdavPasswordPrefill: StateFlow<CharArray?> get() = syncController.webdavPasswordPrefill
    val s3SecretKeyPrefill: StateFlow<CharArray?> get() = syncController.s3SecretKeyPrefill
    // ISSUE-P2-01：S3 AccessKey ID 一次性预填通道（明文不进 UiState）
    val s3AccessKeyPrefill: StateFlow<CharArray?> get() = syncController.s3AccessKeyPrefill

    /** Wave 15 整改：用户开始编辑密码后终结预填通道生命周期（防旋转后旧值回写覆盖用户输入） */
    fun clearWebDavPasswordPrefill() = syncController.clearWebDavPasswordPrefill()
    fun clearS3SecretKeyPrefill() = syncController.clearS3SecretKeyPrefill()
    /** ISSUE-P2-01：用户开始编辑 AccessKey 后终结预填通道生命周期（语义同上） */
    fun clearS3AccessKeyPrefill() = syncController.clearS3AccessKeyPrefill()

    /**
     * ISSUE-P2-08：完整性扫描快照流。未注入检测器时恒为 null（UI 不渲染风险卡片），
     * 绝不回填「安全」假值误导用户。
     */
    private val integrityReportFlow: Flow<RuntimeIntegrityReport?> =
        runtimeIntegrityDetector?.report ?: MutableStateFlow<RuntimeIntegrityReport?>(null)

    // 状态流装配（纯投影 + combine 编排已拆至 SettingsUiStateProjection.kt）
    val uiState: StateFlow<SettingsUiState> = settingsUiStateFlow(
        scope = viewModelScope,
        timeoutMillis = STATE_SUBSCRIBE_TIMEOUT_MILLIS,
        userSettings = settingsRepository.getSettings(),
        syncState = syncController.state,
        healthState = healthController.state,
        autofillState = preferences.autofillState,
        databaseConfigState = preferences.databaseConfigState,
        securityTimeoutState = preferences.securityTimeoutState,
        extendedSettings = extendedPreferences.settings,
        debugLogLines = preferences.debugLogLines,
        integrityReport = integrityReportFlow,
        // ISSUE-P3-20：子库已挂载计数（替代原先硬编码的 0）
        childDatabaseCount = childDatabaseController.countFlow,
        strings = strings
    )

    private val coldStartSyncGate = SettingsColdStartSyncGate(
        settingsRepository = settingsRepository,
        onTriggerSync = { triggerSync() },
        scope = viewModelScope
    )

    init {
        // TASK-12 整改：wifiOnlySync 持久化恢复（周期同步网络约束的消费方）
        syncController.updateWifiOnlySync(extendedSettingsStore.loadWifiOnlySync())
        syncController.restoreSyncCredentials()
        // 离线开关联动：冷启动时把默认/持久化的离线偏好传导至同步协调器
        syncCoordinator.setOfflineMode(extendedPreferences.settings.value.useOfflineCache)
        coldStartSyncGate.checkAndTrigger()
    }

    // ===== 外观与语言（仓库直写） =====
    fun setAppLanguage(language: AppLanguage) = preferences.setAppLanguage(language)
    fun setThemeMode(themeMode: AppThemeMode) = preferences.setThemeMode(themeMode)
    fun setThemePalette(themePalette: AppThemePalette) = preferences.setThemePalette(themePalette)
    fun setOledBlackOptimization(enabled: Boolean) = preferences.setOledBlackOptimization(enabled)
    fun setDynamicColorEnabled(enabled: Boolean) = preferences.setDynamicColorEnabled(enabled)
    fun setBiometricEnabled(enabled: Boolean) = preferences.setBiometricEnabled(enabled)
    fun setAutoLockBackground(enabled: Boolean) = preferences.setAutoLockBackground(enabled)
    fun setFlagSecureEnabled(enabled: Boolean) = preferences.setFlagSecureEnabled(enabled)
    fun setAutoClearClipboard(enabled: Boolean) = preferences.setAutoClearClipboard(enabled)

    // ===== 同步配置 / 动作（委托 [SettingsSyncController]） =====
    fun setSyncProvider(provider: CloudSyncProvider) = syncController.setSyncProvider(provider)
    /**
     * Wave 15 整改：密码以 [CharArray] 借用语义提交（消费后立即擦除），明文不回写状态流；
     * 返回保存结果（https 校验拒绝或凭据封印失败时如实回传 false 并上浮反馈）。
     */
    fun updateWebDavConfig(url: String, username: String, password: CharArray, remotePath: String): Boolean =
        syncController.updateWebDavConfig(url, username, password, remotePath)
    /**
     * Wave 15 整改：SecretKey 以 [CharArray] 借用语义提交（语义同 [updateWebDavConfig]）；
     * ISSUE-P2-01：AccessKey ID 亦改为 [CharArray] 借用语义（消费后即擦除，不驻留状态流）
     */
    fun updateS3Config(
        endpoint: String, bucket: String, region: String, accessKey: CharArray, secretKey: CharArray,
        objectKey: String, usePathStyle: Boolean = syncController.state.value.s3UsePathStyle
    ): Boolean = syncController.updateS3Config(endpoint, bucket, region, accessKey, secretKey, objectKey, usePathStyle)
    fun setAutoSyncEnabled(enabled: Boolean) = syncController.setAutoSyncEnabled(enabled)
    fun setWifiOnlySync(enabled: Boolean) = extendedPreferences.setWifiOnlySync(enabled)
    fun triggerSync() = syncController.triggerSync()
    fun testSyncConnection() = syncController.testSyncConnection()
    fun clearSyncFeedbackMessage() = syncController.clearSyncFeedbackMessage()

    // ===== 密码库与加密配置 =====
    fun setEncryptionAlgorithm(algorithm: String) = preferences.setEncryptionAlgorithm(algorithm)
    fun setKdfAlgorithm(kdf: String) = preferences.setKdfAlgorithm(kdf)
    fun setArgon2Parameters(iterations: Long, memoryMb: Long, parallelism: Int) =
        preferences.setArgon2Parameters(iterations, memoryMb, parallelism)
    /** P0-3 整改：真实调用仓库修改当前数据库的主密钥 */
    suspend fun changeMasterPassword(newPasswordChars: CharArray): KdbxResult<Unit> =
        vaultRepository.changeMasterPassword(newPasswordChars)

    // ===== M6 整改：KDF 设备自适应基准真实接线 =====
    /** KDF 基准实时状态（运行中 / 推荐参数 / 失败原因） */
    val kdfBenchmark: StateFlow<KdfBenchmarkUiState> get() = kdfBenchmarkController.state
    /** 运行真实 KDF 基准测试（Dispatchers.Default，不阻塞主线程） */
    fun runKdfBenchmark() = kdfBenchmarkController.run()
    fun setAutoLockTimeout(seconds: Int) = preferences.setAutoLockTimeout(seconds)
    fun setClipboardTimeout(seconds: Int) = preferences.setClipboardTimeout(seconds)
    fun setCredentialProviderEnabled(enabled: Boolean) = preferences.setCredentialProviderEnabled(enabled)
    fun setPasskeySupportEnabled(enabled: Boolean) = preferences.setPasskeySupportEnabled(enabled)
    fun setAutofillServiceEnabled(enabled: Boolean) = preferences.setAutofillServiceEnabled(enabled)
    fun setRecycleBinEnabled(enabled: Boolean) = preferences.setRecycleBinEnabled(enabled)
    fun setTanExpiresOnUse(enabled: Boolean) = preferences.setTanExpiresOnUse(enabled)
    fun setCheckForDuplicateUuids(enabled: Boolean) = preferences.setCheckForDuplicateUuids(enabled)

    // ===== 列表显示（仓库直写） =====
    fun setShowUsernameInList(enabled: Boolean) = preferences.setShowUsernameInList(enabled)
    fun setShowOtpInList(enabled: Boolean) = preferences.setShowOtpInList(enabled)
    fun setShowPasskeyBadge(enabled: Boolean) = preferences.setShowPasskeyBadge(enabled)
    fun setShowUrlInList(enabled: Boolean) = preferences.setShowUrlInList(enabled)
    fun setHideFabOnScroll(enabled: Boolean) = preferences.setHideFabOnScroll(enabled)
    fun setHapticFeedbackEnabled(enabled: Boolean) = preferences.setHapticFeedbackEnabled(enabled)
    fun setSyncOnColdStart(enabled: Boolean) = preferences.setSyncOnColdStart(enabled)
    fun setShowAuthenticatorTab(enabled: Boolean) = preferences.setShowAuthenticatorTab(enabled)
    fun setShowGeneratorTab(enabled: Boolean) = preferences.setShowGeneratorTab(enabled)

    // ===== 安全锁定规则控制 =====
    fun setLockWhenScreenOff(enabled: Boolean) = extendedPreferences.setLockWhenScreenOff(enabled)
    fun setLockWhenNavigateBack(enabled: Boolean) = extendedPreferences.setLockWhenNavigateBack(enabled)
    fun setClearPasswordOnLeave(enabled: Boolean) = extendedPreferences.setClearPasswordOnLeave(enabled)
    fun setRememberRecentFiles(enabled: Boolean) = extendedPreferences.setRememberRecentFiles(enabled)
    fun setRememberKeyFileLocation(enabled: Boolean) = extendedPreferences.setRememberKeyFileLocation(enabled)
    fun setShowKillAppOption(enabled: Boolean) = extendedPreferences.setShowKillAppOption(enabled)

    // ===== KP2A 扩展：表单自动填充与体验 =====
    fun setOfferSaveCredentials(enabled: Boolean) = extendedPreferences.setOfferSaveCredentials(enabled)
    fun setInlineSuggestionsEnabled(enabled: Boolean) = extendedPreferences.setInlineSuggestionsEnabled(enabled)
    fun setAutoReturnFromQuery(enabled: Boolean) = extendedPreferences.setAutoReturnFromQuery(enabled)
    fun setAutofillCopyTotp(enabled: Boolean) = extendedPreferences.setAutofillCopyTotp(enabled)
    fun setAutofillShowTotpNotification(enabled: Boolean) =
        extendedPreferences.setAutofillShowTotpNotification(enabled)

    fun setSkipDalVerification(enabled: Boolean) = extendedPreferences.setSkipDalVerification(enabled)
    fun setOverrideNoAutofill(enabled: Boolean) = extendedPreferences.setOverrideNoAutofill(enabled)
    // ISSUE-P3-42：会话授权宽限开关
    fun setAutofillSessionGrantEnabled(enabled: Boolean) =
        extendedPreferences.setAutofillSessionGrantEnabled(enabled)

    // ===== TASK-44：自动填充黑名单（真实条目生命周期） =====
    /** 自动填充黑名单快照（按包名升序）；独立于 [uiState] 单独下发，避免 combine 元组膨胀。 */
    val autofillBlockedPackages: StateFlow<List<String>> = preferences.autofillBlockedPackages

    /** 加入黑名单。@return true=新增成功；false=包名非法或已存在（如实提示，不谎报成功） */
    fun blockAutofillPackage(packageName: String): Boolean = preferences.blockAutofillPackage(packageName)

    /** 移出黑名单。@return true=移除成功；false=包名非法或本就不在黑名单中 */
    fun unblockAutofillPackage(packageName: String): Boolean = preferences.unblockAutofillPackage(packageName)

    // ===== KP2A 扩展：显示与外观交互 =====
    fun setMaskPasswordsDefault(enabled: Boolean) = extendedPreferences.setMaskPasswordsDefault(enabled)
    fun setMaskTotpDefault(enabled: Boolean) = extendedPreferences.setMaskTotpDefault(enabled)
    fun setShowUnlockedNotification(enabled: Boolean) = extendedPreferences.setShowUnlockedNotification(enabled)
    fun setShowGroupInSearchResult(enabled: Boolean) = extendedPreferences.setShowGroupInSearchResult(enabled)
    fun setShowGroupInEntry(enabled: Boolean) = extendedPreferences.setShowGroupInEntry(enabled)
    fun setListDensity(density: ListDensity) = extendedPreferences.setListDensity(density)
    fun setAutoActivateSearchOnOpen(enabled: Boolean) = extendedPreferences.setAutoActivateSearchOnOpen(enabled)
    fun setIconSet(iconSet: IconSetOption) = extendedPreferences.setIconSet(iconSet)

    // ===== KP2A 扩展：文件处理与高级同步策略 =====
    fun setUseOfflineCache(enabled: Boolean) = extendedPreferences.setUseOfflineCache(enabled)
    fun setPeriodicBackgroundSyncEnabled(enabled: Boolean) =
        extendedPreferences.setPeriodicBackgroundSyncEnabled(enabled)

    fun setPeriodicBackgroundSyncInterval(minutes: Int) =
        extendedPreferences.setPeriodicBackgroundSyncInterval(minutes)

    fun setAllowedWifiSsids(ssids: String) = extendedPreferences.setAllowedWifiSsids(ssids)
    fun setCreateBackupBeforeSave(enabled: Boolean) = extendedPreferences.setCreateBackupBeforeSave(enabled)
    fun setCheckRemoteChangesBeforeSave(enabled: Boolean) =
        extendedPreferences.setCheckRemoteChangesBeforeSave(enabled)

    fun setConflictResolution(resolution: ConflictResolution) =
        extendedPreferences.setConflictResolution(resolution)

    fun setUseFileTransactions(enabled: Boolean) = extendedPreferences.setUseFileTransactions(enabled)
    fun setWebdavChunkedUpload(enabled: Boolean) = extendedPreferences.setWebdavChunkedUpload(enabled)
    fun setWebdavChunkSizeMb(sizeMb: Int) = extendedPreferences.setWebdavChunkSizeMb(sizeMb)
    fun setPreloadDatabaseEnabled(enabled: Boolean) = extendedPreferences.setPreloadDatabaseEnabled(enabled)

    // ===== KP2A 扩展：TOTP 规范映射 =====
    fun updateTotpFieldMapping(seedField: String, settingsField: String, stepSeconds: Int, digits: Int) =
        extendedPreferences.updateTotpFieldMapping(seedField, settingsField, stepSeconds, digits)

    // ===== TASK-47：已泄露密码检测（联网，默认关闭） =====
    fun setBreachCheckEnabled(enabled: Boolean) = extendedPreferences.setBreachCheckEnabled(enabled)

    // ===== KP2A 扩展：调试日志 =====
    fun setDebugLogEnabled(enabled: Boolean) = extendedPreferences.setDebugLogEnabled(enabled)
    fun setVerboseSyncLog(enabled: Boolean) = extendedPreferences.setVerboseSyncLog(enabled)
    fun refreshDebugLogs() = preferences.refreshDebugLogs()
    fun clearDebugLogs() = preferences.clearDebugLogs()

    // ===== TASK-21 拆分：导出/模板/调试日志委托 [SettingsExportController] =====
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

    // ===== TASK-21 拆分：健康检查委托 [SettingsHealthController] =====
    fun rescanHealth() = healthController.rescanHealth()

    override fun onCleared() {
        // Wave 15 整改：ViewModel 销毁时擦除凭据预填通道中的明文驻留
        syncController.clearWebDavPasswordPrefill()
        syncController.clearS3SecretKeyPrefill()
        // ISSUE-P2-01：AccessKey ID 预填通道随销毁一并擦除
        syncController.clearS3AccessKeyPrefill()
        super.onCleared()
    }
}
