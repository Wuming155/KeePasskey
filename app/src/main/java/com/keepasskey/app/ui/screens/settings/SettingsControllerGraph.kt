package com.keepasskey.app.ui.screens.settings

import android.content.Context
import com.keepasskey.app.data.breach.BreachCheckCoordinator
import com.keepasskey.app.data.childdb.ChildDatabaseSessionManager
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.autofill.AutofillFieldBlocklistStore
import com.keepasskey.app.autofill.AutofillSaveBlocklistStore
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.KeyFileVaultCopyStore
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.sync.PeriodicSyncScheduler
import com.keepasskey.app.sync.ResumeSyncProbeCoordinator
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.ui.model.orFallback
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.importer.VaultImportController
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/**
 * 设置页的**控制器装配图**（`ISSUE-P2-645`：自 [SettingsViewModel] 拆分，纯结构性）。
 *
 * 拆前这批控制器的构造与 `init` 观察接线全部堆在 ViewModel 属性区（约 170 行），
 * 且存在「必须声明在 preferences 之后」的初始化顺序约束。现在顺序只在本类内成立，
 * ViewModel 退化为「构造一次 + 逐项转发」，公开 API 与状态输出零变化。
 *
 * 初始化顺序（不得调换）：features → syncController → sessionLockGuard → extendedPreferences
 * → preferences → issueWiring → healthController → … → uiState → coldStartSyncGate → init。
 */
internal class SettingsControllerGraph(
    private val scope: CoroutineScope,
    private val features: SettingsFeatureControllers,
    private val settingsRepository: SettingsRepository,
    private val vaultRepository: VaultRepository,
    private val syncCredentialsStore: SyncCredentialsStore,
    private val syncCoordinator: SyncCoordinator,
    private val debugLogBuffer: DebugLogBuffer,
    private val extendedSettingsStore: ExtendedSettingsStore,
    private val periodicSyncScheduler: PeriodicSyncScheduler,
    private val autofillBlocklistStore: AutofillBlocklistStore,
    private val autofillSaveBlocklistStore: AutofillSaveBlocklistStore,
    private val autofillFieldBlocklistStore: AutofillFieldBlocklistStore,
    private val breachCheckCoordinator: BreachCheckCoordinator,
    private val databaseSession: DatabaseSession?,
    private val appContext: Context?,
    private val strings: StringsProvider,
    private val vaultImportController: VaultImportController?,
    private val childDatabaseSessionManager: ChildDatabaseSessionManager?,
    private val keyFileAccess: KeyFileAccess?,
    private val passkeyPrivilegedBrowserStore: PasskeyPrivilegedBrowserStore?,
    private val biometricAuthManager: BiometricAuthManager?,
    private val biometricCredentialStorage: BiometricCredentialStorage?,
    private val vaultFileDriftCoordinator: VaultFileDriftCoordinator?,
    private val keyFileVaultCopyStore: KeyFileVaultCopyStore?,
    private val resumeSyncProbeCoordinator: ResumeSyncProbeCoordinator?,
    private val credentialRotationRuntime: CredentialRotationRuntime?,
    private val credentialRotationCompletion: CredentialRotationCompletion?,
    private val stateSubscribeTimeoutMillis: Long,
    private val onTriggerSync: () -> Unit
) {
    // ===== 生物识别开关「开启前当场验证」 + 更换主密钥任务态（P2-354 / P2-398 重封印挂点） =====
    internal val biometricGate = SettingsBiometricGate(
        scope = scope,
        settingsRepository = settingsRepository,
        biometricAuthManager = biometricAuthManager,
        biometricCredentialStorage = biometricCredentialStorage,
        strings = strings,
        debugLog = debugLogBuffer,
        // 仅改绑密钥文件时的密码快照由协调器擦除；封印载荷只含主密码。
        sessionPasswordChars = { databaseSession?.passwordSnapshot() }
    )
    internal val biometricToggleState = biometricGate.toggleState
    internal val masterKeyChange = masterKeyChangeControllerOf(
        vaultRepository, scope, biometricGate::resealAfterMasterKeyChange, keyFileAccess,
        keyFileVaultCopyStore, strings,
        residualBackupProbe = { databaseSession?.let { s -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { s.rollingBackupExists() } } ?: false },
        onCredentialsRotated = { syncCoordinator.markLocalVaultRecrypted() },
        runtime = credentialRotationRuntime, completion = credentialRotationCompletion
    ) // §411（P3-452/P3-448）改密链路收口：提交（含密钥文件三态与留空密码）直用控制器
    internal val masterKeyChangeController: SettingsMasterKeyChangeController get() = masterKeyChange
    internal val keyFileReader get() = features.keyFileReader // ISSUE-P3-428：改密对话框 SAF 读取通道（全仓唯一）

    /** ISSUE-P2-354 AC③：换密回执经 Snackbar 展示后清除（一次性消息语义）。 */
    fun clearMasterKeyChangeFeedback() = masterKeyChange.clearFeedback()

    // ===== 子库挂载（UI 接线，委托 [SettingsChildDatabaseController]） =====
    internal val childDatabaseController = features.childDatabaseController

    /** 子库挂载面板状态；控制器缺失时如实 `available = false`。 */
    val childDatabaseState: StateFlow<ChildDatabaseUiState> get() = childDatabaseController.state

    fun mountChildDatabase(alias: String, sourceUri: String, passwordChars: CharArray, keyFileUri: String?) =
        childDatabaseController.mount(alias, sourceUri, passwordChars, keyFileUri)
    fun unlockChildDatabase(mountId: String, passwordChars: CharArray, keyFileUri: String?) =
        childDatabaseController.unlock(mountId, passwordChars, keyFileUri)
    fun unmountChildDatabase(mountId: String) = childDatabaseController.unmount(mountId)
    fun dismissChildDatabaseFeedback() = childDatabaseController.dismissFeedback()

    // ===== 同步 / 健康 / 导出控制器 =====
    internal val syncController = SettingsSyncController(
        syncCredentialsStore, syncCoordinator, extendedSettingsStore, strings, scope
    )

    /**
     * ISSUE-P2-65：会话锁定 / 关闭时擦除同步凭据的明文预填通道（WebDAV 口令 / S3 SecretKey / AccessKey 均为 CharArray 借用副本，锁定后不得继续驻留）。
     */
    internal val sessionLockGuard = com.keepasskey.database.session.SessionLockGuard(databaseSession) {
        syncController.clearWebDavPasswordPrefill()
        syncController.clearS3SecretKeyPrefill()
        syncController.clearS3AccessKeyPrefill()
    }

    internal val extendedPreferences = SettingsExtendedPreferencesController( // ISSUE-P3-444：放宽为 internal 供设置页直取（同 masterKeyChangeController 口径）
        extendedSettingsStore = extendedSettingsStore,
        periodicSyncScheduler = periodicSyncScheduler,
        databaseSession = databaseSession,
        currentWifiOnlySync = { syncController.currentWifiOnlySync() },
        updateWifiOnlySync = { syncController.updateWifiOnlySync(it) },
        persistLockWhenScreenOff = { enabled ->
            scope.launch { settingsRepository.setLockWhenScreenOff(enabled) }
        }
    )

    internal val preferences = SettingsPreferencesController(
        settingsRepository = settingsRepository,
        vaultRepository = vaultRepository,
        autofillBlocklistStore = autofillBlocklistStore,
        autofillSaveBlocklistStore = autofillSaveBlocklistStore,
        autofillFieldBlocklistStore = autofillFieldBlocklistStore,
        debugLogBuffer = debugLogBuffer,
        // ISSUE-P2-19 / P3-59：活动库会话（真实文件头/默认用户名下发）；单测可为 null
        databaseSession = databaseSession,
        scope = scope
    )

    // ISSUE-P2-379 / P2-378 / P3-381 / P3-382 / P3-385：新增门面与外部修改待决状态（声明在 preferences 之后，避免属性初始化顺序 NPE）
    internal val issueWiring = SettingsIssueWiring(
        vaultRepository = vaultRepository,
        vaultFileDriftCoordinator = vaultFileDriftCoordinator,
        preferencesController = preferences
    )

    internal val healthController = SettingsHealthController(
        vaultRepository = vaultRepository,
        breachCheckCoordinator = breachCheckCoordinator,
        strings = strings,
        breachCheckEnabled = { extendedPreferences.settings.value.breachCheckEnabled },
        scope = scope,
        // ISSUE-P3-257：开启泄露检测并就地扫描的偏好持久化回调（形态同 extendedPreferences 的 lambda 注入）
        setBreachCheckEnabled = { extendedPreferences.setBreachCheckEnabled(it) },
        // ISSUE-P3-382 / P3-409：库内重复条目——明细映射在 SettingsDatabaseMetaController，此处仅委托
        scanDuplicates = {
            preferences.databaseMetaController.scanDuplicateHealthIssues(strings)
        }
    )

    internal val exportController = SettingsExportController(
        vaultRepository, debugLogBuffer, appContext, strings, scope
    )

    internal val kdfBenchmarkController = SettingsKdfBenchmarkController(
        appContext = appContext, strings = strings, scope = scope
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
    internal val uiState: StateFlow<SettingsUiState> = settingsUiStateFlow(
        scope = scope,
        timeoutMillis = stateSubscribeTimeoutMillis,
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
            masterKeyChangeState = masterKeyChange.state,
            syncProbeNotice = resumeSyncProbeCoordinator?.notice ?: kotlinx.coroutines.flow.MutableStateFlow(null)
        ),
        strings = strings
    )

    internal val coldStartSyncGate = SettingsColdStartSyncGate(
        settingsRepository = settingsRepository,
        onTriggerSync = onTriggerSync,
        scope = scope,
        isSyncConfigured = { syncController.isSyncConfigured() }
    )

    init {
        // ISSUE-P2-406：Keystore 解密/封印探测/wifi 回填不在 init 同步执行；见 hydrateSyncUi()
        // ISSUE-P2-65：注册会话锁定观察者（须在 [sessionLockGuard] 声明之后）
        sessionLockGuard.register()
        // ISSUE-P2-497：「离线缓存」假开关已整链移除（PD-75）——本页不得再把该缓存开关
        // 误接到引擎「强制离线」（该误接曾使同步引擎永不联网、首传被误报为
        // 「首次同步上传云端失败」，2026-10-30 已拆；现字段本身下架）。
        coldStartSyncGate.checkAndTrigger()
        // ISSUE-P2-212：跟踪活动库（开启生物识别开关时据此判定封印凭据是否就绪）
        scope.launch {
            vaultRepository.getDatabases().collect { databases ->
                biometricGate.onActiveDatabaseChanged(
                    (databases.firstOrNull { it.isActive } ?: databases.firstOrNull())?.id
                )
            }
        }
        // ISSUE-P2-378：观察漂移待决提示并下发到 UI（全局三选对话框）
        vaultFileDriftCoordinator?.let { coord ->
            scope.launch {
                coord.pending.collect { issueWiring::publishPending }
            }
            // ISSUE-P1-631：观察漂移合并冲突待决裁决状态并下发到 UI（与三选互斥呈现）
            scope.launch {
                coord.mergeConflict.collect { issueWiring::publishMergeConflict }
            }
        }
    }
    internal val privilegedBrowserController = SettingsPrivilegedBrowserController(
        store = passkeyPrivilegedBrowserStore,
        scope = scope
    )
}
