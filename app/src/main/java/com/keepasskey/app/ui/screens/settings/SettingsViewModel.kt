package com.keepasskey.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.childdb.ChildDatabaseSessionManager
import com.keepasskey.app.data.importer.ImportSource
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.ui.model.orFallback
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.app.ui.screens.importer.VaultImportController
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.AppThemePalette
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页面状态容器 ViewModel (涵盖 KeePass2Android 与 KeePassDX 2026 高保真全量偏好)
 *
 * ## 拆分说明
 * 原 1120 行巨型类已按职责拆为协作者，本类只保留「输入流编排 + 对外 API 门面」：
 * 纯投影 `SettingsUiStateProjection`；子库 / 偏好 / 进阶偏好 / KDF 基准 / 同步 / 健康 / 导出
 * 控制器；§280 再下沉导入+子库装配（`SettingsFeatureControllers`）与生物识别门
 * （`SettingsBiometricGate`）。公开 API 与状态输出零变化，敏感语义原样保留。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vaultRepository: VaultRepository,
    private val syncCredentialsStore: SyncCredentialsStore,
    private val syncCoordinator: SyncCoordinator,
    private val debugLogBuffer: DebugLogBuffer,
    // TASK-12：进阶偏好持久化仓库（冷启动不再静默回落默认值）
    private val extendedSettingsStore: com.keepasskey.app.data.repository.ExtendedSettingsStore,
    // TASK-08：周期后台同步调度器（设置变更即时生效）
    private val periodicSyncScheduler: com.keepasskey.app.sync.PeriodicSyncScheduler,
    // TASK-44：自动填充黑名单（真实包名条目）
    private val autofillBlocklistStore: com.keepasskey.app.data.repository.AutofillBlocklistStore,
    // ISSUE-P3-43 ③：保存侧独立黑名单（只禁保存提示，不影响填充）
    private val autofillSaveBlocklistStore: com.keepasskey.app.autofill.AutofillSaveBlocklistStore,
    // ISSUE-P3-43 ②：字段签名级屏蔽（写入方为手动选择器，本页仅计数与整体清除）
    private val autofillFieldBlocklistStore: com.keepasskey.app.autofill.AutofillFieldBlocklistStore,
    // TASK-47：已泄露密码检测（HIBP k-匿名范围查询，由 breachCheckEnabled 开关门控）
    private val breachCheckCoordinator: com.keepasskey.app.data.breach.BreachCheckCoordinator,
    // ISSUE-P2-11：会话级「保存前创建 .bak 备份」偏好下发；null 仅用于单测注入
    private val databaseSession: DatabaseSession? = null,
    // null 仅用于单测注入；生产 DI 注入 @ApplicationContext
    @ApplicationContext private val appContext: Context? = null,
    // TASK-21：非 Compose 层文案资源解析通道（单测可注入假实现）
    private val stringsProvider: StringsProvider? = null,
    // ISSUE-P3-19：明文导入控制器；缺失时 [importState] 恒 Idle，绝不产生假进度/假回执
    private val vaultImportController: VaultImportController? = null,
    // ISSUE-P3-20：子库挂载会话管理器；缺失时 `childDatabasesCount` 回落 0（不谎报）
    private val childDatabaseSessionManager: ChildDatabaseSessionManager? = null,
    // ISSUE-P3-20：SAF 持久化读授权 + 密钥文件字节读取通道；null 仅用于单测注入
    private val keyFileAccess: KeyFileAccess? = null,
    // CM 通道特权浏览器白名单；缺失时列表恒为空（如实「未检测到」）
    private val passkeyPrivilegedBrowserStore: com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore? = null,
    // ISSUE-P2-212：开启生物识别当场验证通道；缺失时开启动作 fail-closed
    private val biometricAuthManager: BiometricAuthManager? = null,
    // ISSUE-P2-212：封印凭据存在性判定（决定「立即可用」还是「下次解锁后登记」）
    private val biometricCredentialStorage: BiometricCredentialStorage? = null,
    // ISSUE-P2-378：外部修改漂移协调器（null 仅纯 JVM 单测；生产 Hilt 注入 @Singleton）
    private val vaultFileDriftCoordinator: com.keepasskey.app.security.VaultFileDriftCoordinator? = null
) : ViewModel() {

    companion object {
        /** UI 状态流停止订阅后的保活窗口（毫秒）：与既有 [uiState] 保持一致 */
        private const val STATE_SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }

    // 文案解析通道与领域控制器（同步/健康/导出），ViewModel 保留状态编排
    private val strings: StringsProvider = stringsProvider.orFallback(appContext)

    // ===== 明文导入（委托 [SettingsImportPresenter]） =====
    // §280：导入 / 子库 / 生物识别装配下沉 SettingsFeatureControllers / SettingsBiometricGate
    private val features = SettingsFeatureControllers(
        vaultImportController = vaultImportController,
        childDatabaseSessionManager = childDatabaseSessionManager,
        keyFileAccess = keyFileAccess,
        debugLogBuffer = debugLogBuffer,
        scope = viewModelScope,
        stateSubscribeTimeoutMillis = STATE_SUBSCRIBE_TIMEOUT_MILLIS,
        appContext = appContext
    )
    private val importPresenter = features.importPresenter
    // ISSUE-P3-395：浏览凭据回退注入——表单密码被保存流程清零后，与「测试连接」同源读已保存凭据
    private val remoteBrowseHost = SettingsRemoteBrowseHost(
        debugLogBuffer,
        viewModelScope,
        savedWebDavPassword = { syncCredentialsStore.loadWebDavConfig()?.password },
        loadS3Snapshot = {
            val s3 = syncCredentialsStore.loadS3Config()
            s3?.accessKey to s3?.secretKey
        }
    )

    /** 导入 / 并入 / 浏览状态（动作为成员方法，见下方）。 */
    val importState: StateFlow<ImportUiState> get() = importPresenter.state
    val mergeState: StateFlow<ImportUiState> get() = importPresenter.mergeState
    val remoteBrowseState: StateFlow<com.keepasskey.app.sync.RemoteBrowseUiState> get() = remoteBrowseHost.state

    fun startImport(source: ImportSource, uri: Uri) = importPresenter.start(source, uri)
    fun dismissImportReport() = importPresenter.dismissReport()
    fun cancelImport() = importPresenter.cancel()
    fun submitMergeCredentials(passwordChars: CharArray, keyFileData: ByteArray? = null) =
        importPresenter.submitMergeCredentials(passwordChars, keyFileData)
    fun submitMergeCredentials(passwordChars: CharArray, keyFileUri: android.net.Uri?) =
        importPresenter.submitMergeCredentials(passwordChars, keyFileUri)
    fun cancelMerge() = importPresenter.cancelMerge()
    fun dismissMergeReport() = importPresenter.dismissMergeReport()
    fun browseWebDav(url: String, username: String, password: CharArray, remotePath: String, cursor: String? = null) =
        remoteBrowseHost.browseWebDav(url, username, password, remotePath, cursor)
    fun browseS3(
        endpoint: String, bucket: String, region: String,
        accessKey: CharArray, secretKey: CharArray,
        objectKey: String, usePathStyle: Boolean, cursor: String? = null
    ) = remoteBrowseHost.browseS3(endpoint, bucket, region, accessKey, secretKey, objectKey, usePathStyle, cursor)
    fun dismissRemoteBrowse() = remoteBrowseHost.dismiss()

    // ===== 生物识别开关「开启前当场验证」 + 更换主密钥任务态（P2-354 / P2-398 重封印挂点） =====
    private val biometricGate = SettingsBiometricGate(
        scope = viewModelScope,
        settingsRepository = settingsRepository,
        biometricAuthManager = biometricAuthManager,
        biometricCredentialStorage = biometricCredentialStorage,
        strings = strings,
        debugLog = debugLogBuffer,
        // ISSUE-P2-398：改密重封印载荷的密钥文件因子来源（复用会话导出通道）
        sessionKeyFileBytes = { databaseSession?.exportKeyFileBytes() }
    )

    /** 开关动作即时状态（验证中 / 一次性反馈），经投影层并入 [uiState] */
    private val biometricToggleState = biometricGate.toggleState
    private val masterKeyChange =
        SettingsMasterKeyChangeController(vaultRepository, viewModelScope, biometricGate::resealAfterMasterKeyChange)

    /** P2-354/P2-398/P3-428 改密链路收口：提交（含密钥文件三态）直用控制器；读取通道见 features.keyFileReader */
    internal val masterKeyChangeController: SettingsMasterKeyChangeController get() = masterKeyChange
    internal val keyFileReader get() = features.keyFileReader

    /** ISSUE-P2-354 AC③：换密回执经 Snackbar 展示后清除（一次性消息语义）。 */
    fun clearMasterKeyChangeFeedback() = masterKeyChange.clearFeedback()

    // ===== 子库挂载（UI 接线，委托 [SettingsChildDatabaseController]） =====
    private val childDatabaseController = features.childDatabaseController

    /** 子库挂载面板状态；控制器缺失时如实 `available = false`。 */
    val childDatabaseState: StateFlow<ChildDatabaseUiState> get() = childDatabaseController.state

    fun mountChildDatabase(alias: String, sourceUri: String, passwordChars: CharArray, keyFileUri: String?) =
        childDatabaseController.mount(alias, sourceUri, passwordChars, keyFileUri)
    fun unlockChildDatabase(mountId: String, passwordChars: CharArray, keyFileUri: String?) =
        childDatabaseController.unlock(mountId, passwordChars, keyFileUri)
    fun unmountChildDatabase(mountId: String) = childDatabaseController.unmount(mountId)
    fun dismissChildDatabaseFeedback() = childDatabaseController.dismissFeedback()

    // ===== 同步 / 健康 / 导出控制器 =====
    private val syncController = SettingsSyncController(
        syncCredentialsStore, syncCoordinator, extendedSettingsStore, strings, viewModelScope
    )

    /**
     * ISSUE-P2-65：会话锁定 / 关闭时擦除同步凭据的明文预填通道
     * （WebDAV 口令 / S3 SecretKey / AccessKey 均为 CharArray 借用副本，锁定后不得继续驻留）。
     */
    private val sessionLockGuard = com.keepasskey.database.session.SessionLockGuard(databaseSession) {
        syncController.clearWebDavPasswordPrefill()
        syncController.clearS3SecretKeyPrefill()
        syncController.clearS3AccessKeyPrefill()
    }

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

    private val preferences = SettingsPreferencesController(
        settingsRepository = settingsRepository,
        vaultRepository = vaultRepository,
        autofillBlocklistStore = autofillBlocklistStore,
        autofillSaveBlocklistStore = autofillSaveBlocklistStore,
        autofillFieldBlocklistStore = autofillFieldBlocklistStore,
        debugLogBuffer = debugLogBuffer,
        // ISSUE-P2-19 / P3-59：活动库会话（真实文件头/默认用户名下发）；单测可为 null
        databaseSession = databaseSession,
        scope = viewModelScope
    )

    // ISSUE-P2-379 / P2-378 / P3-381 / P3-382 / P3-385：新增门面与外部修改待决状态
    // （声明在 preferences 之后，避免属性初始化顺序 NPE）
    private val issueWiring = SettingsIssueWiring(
        vaultRepository = vaultRepository,
        vaultFileDriftCoordinator = vaultFileDriftCoordinator,
        preferencesController = preferences
    )

    private val healthController = SettingsHealthController(
        vaultRepository = vaultRepository,
        breachCheckCoordinator = breachCheckCoordinator,
        strings = strings,
        breachCheckEnabled = { extendedPreferences.settings.value.breachCheckEnabled },
        scope = viewModelScope,
        // ISSUE-P3-257：开启泄露检测并就地扫描的偏好持久化回调（形态同 extendedPreferences 的 lambda 注入）
        setBreachCheckEnabled = { extendedPreferences.setBreachCheckEnabled(it) },
        // ISSUE-P3-382 / P3-409：库内重复条目——明细映射在 SettingsDatabaseMetaController，此处仅委托
        scanDuplicates = {
            preferences.databaseMetaController.scanDuplicateHealthIssues(strings)
        }
    )

    private val exportController = SettingsExportController(
        vaultRepository, debugLogBuffer, appContext, strings, viewModelScope
    )

    private val kdfBenchmarkController = SettingsKdfBenchmarkController(
        appContext = appContext, strings = strings, scope = viewModelScope
    )

    // ===== 同步状态流与凭据明文预填通道由 [SettingsSyncController] 承载 =====
    val webdavPasswordPrefill: StateFlow<CharArray?> get() = syncController.webdavPasswordPrefill
    val s3SecretKeyPrefill: StateFlow<CharArray?> get() = syncController.s3SecretKeyPrefill
    // ISSUE-P2-01：S3 AccessKey ID 一次性预填通道
    val s3AccessKeyPrefill: StateFlow<CharArray?> get() = syncController.s3AccessKeyPrefill

    /** Wave 15：用户开始编辑密码后终结预填通道生命周期（防旋转后旧值回写覆盖用户输入） */
    fun clearWebDavPasswordPrefill() = syncController.clearWebDavPasswordPrefill()
    fun clearS3SecretKeyPrefill() = syncController.clearS3SecretKeyPrefill()
    /** ISSUE-P2-01：用户开始编辑 AccessKey 后终结预填通道生命周期（语义同上） */
    fun clearS3AccessKeyPrefill() = syncController.clearS3AccessKeyPrefill()

    // 状态流装配（纯投影 + combine 编排在 SettingsUiStateProjection.kt）
    val uiState: StateFlow<SettingsUiState> = settingsUiStateFlow(
        scope = viewModelScope,
        timeoutMillis = STATE_SUBSCRIBE_TIMEOUT_MILLIS,
        flows = SettingsUiStateFlows(
            userSettings = settingsRepository.getSettings(),
            syncState = syncController.state,
            healthState = healthController.state,
            databaseConfigState = preferences.databaseConfigState,
            // ISSUE-P2-212：生物识别开关的验证中/一次性反馈状态
            biometricToggleState = biometricToggleState,
            extendedSettings = extendedPreferences.settings,
            debugLogLines = preferences.debugLogLines,
            // ISSUE-P3-20：子库已挂载计数（替代原先硬编码的 0）
            childDatabaseCount = childDatabaseController.countFlow,
            // ISSUE-P2-354 AC③：更换主密钥任务态（busy + 结果反馈）
            masterKeyChangeState = masterKeyChange.state
        ),
        strings = strings
    )

    private val coldStartSyncGate = SettingsColdStartSyncGate(
        settingsRepository = settingsRepository,
        onTriggerSync = { triggerSync() },
        scope = viewModelScope,
        isSyncConfigured = { syncController.isSyncConfigured() }
    )

    init {
        // ISSUE-P2-406：Keystore 解密/封印探测/wifi 回填不在 init 同步执行；见 hydrateSyncUi()
        // ISSUE-P2-65：注册会话锁定观察者（须在 [sessionLockGuard] 声明之后）
        sessionLockGuard.register()
        // 注意：useOfflineCache（离线缓存）≠ SyncEngine 强制离线。
        // 原先把缓存开关误接到 setOfflineMode(true) 会使同步引擎永不联网，
        // 首传被标成「首次同步上传云端失败」——已移除误接（2026-10-30）。
        coldStartSyncGate.checkAndTrigger()
        // ISSUE-P2-212：跟踪活动库（开启生物识别开关时据此判定封印凭据是否就绪）
        viewModelScope.launch {
            vaultRepository.getDatabases().collect { databases ->
                biometricGate.onActiveDatabaseChanged(
                    (databases.firstOrNull { it.isActive } ?: databases.firstOrNull())?.id
                )
            }
        }
        // ISSUE-P2-378：观察漂移待决提示并下发到 UI（全局三选对话框）
        vaultFileDriftCoordinator?.let { coord ->
            viewModelScope.launch {
                coord.pending.collect { issueWiring::publishPending }
            }
        }
    }

    // ===== 外观与语言（仓库直写） =====
    fun setAppLanguage(language: AppLanguage) = preferences.setAppLanguage(language)
    fun setThemeMode(themeMode: AppThemeMode) = preferences.setThemeMode(themeMode)
    fun setThemePalette(themePalette: AppThemePalette) = preferences.setThemePalette(themePalette)
    fun setOledBlackOptimization(enabled: Boolean) = preferences.setOledBlackOptimization(enabled)
    fun setDynamicColorEnabled(enabled: Boolean) = preferences.setDynamicColorEnabled(enabled)
    /**
     * ISSUE-P2-212：生物识别开关切换。**开启必须当场验证**（委托 [BiometricEnableCoordinator]），
     * 取消/失败/无强生物识别一律不写偏好；关闭落偏好并**撤销全部生物识别数据**（ISSUE-P2-253）。
     * @param activity 宿主 Activity（发起 `BiometricPrompt` 必需）；缺失时开启动作 fail-closed。
     */
    fun setBiometricEnabled(enabled: Boolean, activity: FragmentActivity? = null) =
        biometricGate.setEnabled(enabled, activity)
    fun setAutoLockBackground(enabled: Boolean) = preferences.setAutoLockBackground(enabled)
    /** ISSUE-P2-379：前台闲置自动锁定开关 */
    fun setAutoLockForegroundEnabled(enabled: Boolean) = issueWiring.setAutoLockForegroundEnabled(enabled)
    /** ISSUE-P2-379：前台闲置超时秒数 */
    fun setAutoLockForegroundTimeoutSeconds(seconds: Int) = issueWiring.setAutoLockForegroundTimeoutSeconds(seconds)
    /** ISSUE-P3-381：回前台 / 网络恢复远端探测开关 */
    fun setSyncProbeOnResumeEnabled(enabled: Boolean) = issueWiring.setSyncProbeOnResumeEnabled(enabled)
    /** ISSUE-P3-385：库级 Meta（库名 / 描述 / 默认用户名）编辑并立即落盘 */
    fun setDatabaseMeta(databaseName: String?, databaseDescription: String?, defaultUserName: String?) =
        issueWiring.setDatabaseMeta(databaseName, databaseDescription, defaultUserName)
    /** ISSUE-P3-382：库内重复条目只读扫描 */
    fun scanDuplicateEntries() = issueWiring.scanDuplicateEntries()
    /** ISSUE-P2-378：外部修改三选处置 */
    suspend fun applyExternalModificationChoice(
        choice: com.keepasskey.app.security.ExternalModificationChoice
    ) = issueWiring.applyExternalModificationChoice(choice)
    /** ISSUE-P2-378：待决外部修改提示（KeePasskeyApp 全局对话框消费） */
    val externalModificationPending: StateFlow<com.keepasskey.app.security.VaultFileDriftPrompt?>
        get() = issueWiring.pending
    /** ISSUE-P3-385：密码库设置页 Meta 编辑反馈 */
    val databaseMetaFeedback: StateFlow<String?> get() = issueWiring.databaseMetaFeedback
    fun clearDatabaseMetaFeedback() = issueWiring.clearDatabaseMetaFeedback()
    fun setFlagSecureEnabled(enabled: Boolean) = preferences.setFlagSecureEnabled(enabled)
    fun setAutoClearClipboard(enabled: Boolean) = preferences.setAutoClearClipboard(enabled)

    // ===== 同步配置 / 动作（委托 [SettingsSyncController]） =====
    fun setSyncProvider(provider: CloudSyncProvider) = syncController.setSyncProvider(provider)
    /**
     * Wave 15：密码以 [CharArray] 借用语义提交（消费后立即擦除），明文不回写状态流。
     * 返回保存结果（https 校验拒绝或凭据封印失败时如实回传 false 并上浮反馈）。
     */
    fun updateWebDavConfig(url: String, username: String, password: CharArray, remotePath: String): Boolean =
        syncController.updateWebDavConfig(url, username, password, remotePath)
    /** Wave 15：SecretKey / AccessKey ID 亦为 [CharArray] 借用语义（语义同 [updateWebDavConfig]）。 */
    fun updateS3Config(endpoint: String, bucket: String, region: String, accessKey: CharArray, secretKey: CharArray, objectKey: String, usePathStyle: Boolean = syncController.state.value.s3UsePathStyle): Boolean = syncController.updateS3Config(endpoint, bucket, region, accessKey, secretKey, objectKey, usePathStyle)
    fun setAutoSyncEnabled(enabled: Boolean) = syncController.setAutoSyncEnabled(enabled)
    fun setWifiOnlySync(enabled: Boolean) = extendedPreferences.setWifiOnlySync(enabled)
    fun triggerSync() = syncController.triggerSync()
    fun testSyncConnection() = syncController.testSyncConnection()
    /** ISSUE-P2-406：同步页水合（PD-23 单语句委托；体内后台派发器做 Keystore 解密） */
    fun hydrateSyncUi() = syncController.hydrate()

    /**
     * 「保存并同步」顺序编排：保存成功 →（未验证时先）测试连接 → 通过则同步。
     * 实现随 `ISSUE-P3-257` 迁于 [SettingsSyncController.verifyConnectionThenSync]。
     */
    fun verifyConnectionThenSync() = syncController.verifyConnectionThenSync()
    fun clearSyncFeedbackMessage() = syncController.clearSyncFeedbackMessage()

    fun setEncryptionAlgorithm(algorithm: String) = preferences.setEncryptionAlgorithm(algorithm)
    fun setKdfAlgorithm(kdf: String) = preferences.setKdfAlgorithm(kdf)
    fun setArgon2Parameters(iterations: Long, memoryMb: Long, parallelism: Int) = preferences.setArgon2Parameters(iterations, memoryMb, parallelism)
    fun noteWeakMasterPasswordConfirmed() {
        debugLogBuffer.warn("MasterPasswordPolicy", "用户显式确认使用低于强度门槛的主密码（改密）")
    }
    val kdfBenchmark: StateFlow<KdfBenchmarkUiState> get() = kdfBenchmarkController.state
    fun runKdfBenchmark() = kdfBenchmarkController.run()
    fun setAutoLockTimeout(seconds: Int) = preferences.setAutoLockTimeout(seconds)
    fun setClipboardTimeout(seconds: Int) = preferences.setClipboardTimeout(seconds)
    fun setUnlockThrottleEnabled(enabled: Boolean) = preferences.setUnlockThrottleEnabled(enabled)
    fun setUnlockLockoutMaxSeconds(seconds: Int) = preferences.setUnlockLockoutMaxSeconds(seconds)
    fun setCredentialProviderEnabled(enabled: Boolean) = extendedPreferences.setCredentialProviderEnabled(enabled)
    fun setPasskeySupportEnabled(enabled: Boolean) = extendedPreferences.setPasskeySupportEnabled(enabled)
    fun setAutofillServiceEnabled(enabled: Boolean) = extendedPreferences.setAutofillServiceEnabled(enabled)
    fun setAutofillLegacyAccessibilityEnabled(enabled: Boolean) =
        extendedPreferences.setAutofillLegacyAccessibilityEnabled(enabled)
    fun setRecycleBinEnabled(enabled: Boolean) = preferences.setRecycleBinEnabled(enabled)

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
    fun setRememberKeyFileLocation(enabled: Boolean) = extendedPreferences.setRememberKeyFileLocation(enabled)
    fun setShowKillAppOption(enabled: Boolean) = extendedPreferences.setShowKillAppOption(enabled)

    // ===== KP2A 扩展：表单自动填充与体验 =====
    fun setOfferSaveCredentials(enabled: Boolean) = extendedPreferences.setOfferSaveCredentials(enabled)
    fun setAutofillManualPickerEnabled(enabled: Boolean) = extendedPreferences.setAutofillManualPickerEnabled(enabled)
    fun setAutofillOfferCreateEntry(enabled: Boolean) = extendedPreferences.setAutofillOfferCreateEntry(enabled)
    fun setInlineSuggestionsEnabled(enabled: Boolean) = extendedPreferences.setInlineSuggestionsEnabled(enabled)
    fun setAutoReturnFromQuery(enabled: Boolean) = extendedPreferences.setAutoReturnFromQuery(enabled)
    fun setAutofillCopyTotp(enabled: Boolean) = extendedPreferences.setAutofillCopyTotp(enabled)
    fun setAutofillShowTotpNotification(enabled: Boolean) = extendedPreferences.setAutofillShowTotpNotification(enabled)
    fun setSkipDalVerification(enabled: Boolean) = extendedPreferences.setSkipDalVerification(enabled)
    fun setOverrideNoAutofill(enabled: Boolean) = extendedPreferences.setOverrideNoAutofill(enabled)
    fun setAutofillSessionGrantEnabled(enabled: Boolean) = extendedPreferences.setAutofillSessionGrantEnabled(enabled)

    // ===== TASK-44：自动填充黑名单 =====
    val autofillBlockedPackages: StateFlow<List<String>> = preferences.autofillBlockedPackages
    fun blockAutofillPackage(packageName: String): Boolean = preferences.blockAutofillPackage(packageName)
    fun unblockAutofillPackage(packageName: String): Boolean = preferences.unblockAutofillPackage(packageName)

    // ===== CM 通道：特权浏览器白名单 =====
    private val privilegedBrowserController = SettingsPrivilegedBrowserController(
        store = passkeyPrivilegedBrowserStore,
        scope = viewModelScope
    )
    val privilegedBrowsers: StateFlow<List<com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore.BrowserApp>>
        get() = privilegedBrowserController.privilegedBrowsers
    fun refreshPrivilegedBrowsers() = privilegedBrowserController.refresh()
    fun setPrivilegedBrowserEnabled(packageName: String, enabled: Boolean) =
        privilegedBrowserController.setEnabled(packageName, enabled)

    // ===== ISSUE-P3-43：保存侧黑名单 + 字段签名级屏蔽 =====
    val autofillSaveBlockedPackages: StateFlow<List<String>> = preferences.autofillSaveBlockedPackages
    fun blockSavePackage(packageName: String): Boolean = preferences.blockSavePackage(packageName)
    fun unblockSavePackage(packageName: String): Boolean = preferences.unblockSavePackage(packageName)
    val autofillBlockedFieldCount: StateFlow<Int> = preferences.autofillBlockedFieldCount
    fun clearBlockedFields() { preferences.clearBlockedFields() }

    // ===== KP2A 扩展：显示与外观交互 =====
    fun setMaskPasswordsDefault(enabled: Boolean) = extendedPreferences.setMaskPasswordsDefault(enabled)
    fun setMaskTotpDefault(enabled: Boolean) = extendedPreferences.setMaskTotpDefault(enabled)
    fun setShowUnlockedNotification(enabled: Boolean) = extendedPreferences.setShowUnlockedNotification(enabled)
    fun setShowGroupInSearchResult(enabled: Boolean) = extendedPreferences.setShowGroupInSearchResult(enabled)
    fun setShowGroupInEntry(enabled: Boolean) = extendedPreferences.setShowGroupInEntry(enabled)
    fun setListDensity(density: ListDensity) = extendedPreferences.setListDensity(density)
    fun setAutoActivateSearchOnOpen(enabled: Boolean) = extendedPreferences.setAutoActivateSearchOnOpen(enabled)
    fun setSearchMatchMode(mode: SearchMatchMode) = extendedPreferences.setSearchMatchMode(mode)

    // ===== KP2A 扩展：文件处理与高级同步策略 =====
    fun setUseOfflineCache(enabled: Boolean) = extendedPreferences.setUseOfflineCache(enabled)
    fun setPeriodicBackgroundSyncEnabled(enabled: Boolean) = extendedPreferences.setPeriodicBackgroundSyncEnabled(enabled)
    fun setPeriodicBackgroundSyncInterval(minutes: Int) = extendedPreferences.setPeriodicBackgroundSyncInterval(minutes)
    fun setCreateBackupBeforeSave(enabled: Boolean) = extendedPreferences.setCreateBackupBeforeSave(enabled)
    fun setCheckRemoteChangesBeforeSave(enabled: Boolean) = extendedPreferences.setCheckRemoteChangesBeforeSave(enabled)
    fun setConflictResolution(resolution: ConflictResolution) = extendedPreferences.setConflictResolution(resolution)
    fun setWebdavChunkedUpload(enabled: Boolean) = extendedPreferences.setWebdavChunkedUpload(enabled)
    fun setWebdavChunkSizeMb(sizeMb: Int) = extendedPreferences.setWebdavChunkSizeMb(sizeMb)

    // ===== KP2A 扩展：TOTP 规范映射 =====
    fun updateTotpFieldMapping(seedField: String, settingsField: String, stepSeconds: Int, digits: Int) =
        extendedPreferences.updateTotpFieldMapping(seedField, settingsField, stepSeconds, digits)

    // ===== TASK-47：已泄露密码检测（联网，默认关闭） =====
    fun setBreachCheckEnabled(enabled: Boolean) = extendedPreferences.setBreachCheckEnabled(enabled)
    fun enableBreachCheckAndScan() = healthController.enableBreachCheckAndScan()

    // ===== KP2A 扩展：调试日志 =====
    fun setDebugLogEnabled(enabled: Boolean) = extendedPreferences.setDebugLogEnabled(enabled)
    fun setVerboseSyncLog(enabled: Boolean) = extendedPreferences.setVerboseSyncLog(enabled)
    fun refreshDebugLogs() = preferences.refreshDebugLogs()
    fun clearDebugLogs() = preferences.clearDebugLogs()

    // ===== 导出/模板/调试日志委托 [SettingsExportController] =====
    val debugExportFeedback: StateFlow<UiMessage?> get() = exportController.debugExportFeedback
    fun exportDebugLogs(targetUri: Uri) = exportController.exportDebugLogs(targetUri)
    fun clearDebugExportFeedback() = exportController.clearDebugExportFeedback()
    val exportFeedback: StateFlow<UiMessage?> get() = exportController.exportFeedback
    fun clearExportFeedback() = exportController.clearExportFeedback()
    fun exportKdbxTo(targetUri: Uri) = exportController.exportKdbxTo(targetUri)
    fun exportVaultXmlTo(targetUri: Uri, ticket: ExportTicket) = exportController.exportVaultXmlTo(targetUri, ticket)
    fun exportVaultCsvTo(targetUri: Uri, ticket: ExportTicket) = exportController.exportVaultCsvTo(targetUri, ticket)
    fun exportKeyFileTo(targetUri: Uri, ticket: ExportTicket) = exportController.exportKeyFileTo(targetUri, ticket)
    fun installEntryTemplates() = exportController.installEntryTemplates()

    // ===== 健康检查委托 [SettingsHealthController] =====
    fun rescanHealth() = healthController.rescanHealth()

    override fun onCleared() {
        // Wave 15 整改：ViewModel 销毁时擦除凭据预填通道中的明文驻留
        syncController.clearWebDavPasswordPrefill()
        syncController.clearS3SecretKeyPrefill()
        // ISSUE-P2-01：AccessKey ID 预填通道随销毁一并擦除
        syncController.clearS3AccessKeyPrefill()
        sessionLockGuard.unregister()
        super.onCleared()
    }
}
