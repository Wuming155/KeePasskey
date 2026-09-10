package com.keepasskey.app.ui.screens.settings

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.childdb.ChildDatabaseLimits
import com.keepasskey.app.data.childdb.ChildDatabaseMountState
import com.keepasskey.app.data.childdb.ChildDatabaseSessionManager
import com.keepasskey.app.data.importer.ImportSource
import com.keepasskey.app.data.logger.DebugLogBuffer
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
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.crypto.kdf.KdfBenchmark
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
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
        private const val TAG = "SettingsViewModel"

        /** ActivityManager 不可得时的兜底应用堆上限（MiB） */
        private const val DEFAULT_HEAP_MB = 128

        /** UI 状态流停止订阅后的保活窗口（毫秒）：与既有 [uiState] 保持一致 */
        private const val STATE_SUBSCRIBE_TIMEOUT_MILLIS = 5_000L

        /** 敏感序列擦除填充值（项目既有约定：`CharArray` 填 `'0'`、`ByteArray` 填 `0`） */
        private const val ZERO_CHAR: Char = '0'
        private const val ZERO_BYTE: Byte = 0

        // 标记当前应用进程生命周期内是否已执行过冷启动同步检测
        // 当软件被彻底杀死重启时，该静态字段重新变为 false，从而再次自动触发云端同步
        @Volatile
        private var hasCheckedColdStartSync = false
    }

    // TASK-21 拆分：文案解析通道与领域控制器（同步/健康/导出），ViewModel 保留状态编排
    private val strings: StringsProvider = stringsProvider
        ?: appContext?.let { ctx -> StringsProvider { id, args -> ctx.getString(id, *args) } }
        ?: StringsProvider { _, _ -> "" }

    // ===== ISSUE-P3-19：明文导入状态与入口（全部委托 [VaultImportController]，本层不做业务） =====

    /** 导入状态（Idle / Parsing / Done / Failed）。无控制器时恒为 Idle，UI 不渲染任何导入反馈。 */
    val importState: StateFlow<ImportUiState> =
        vaultImportController?.uiState ?: MutableStateFlow(ImportUiState.Idle)

    /** 按数据源 + SAF Uri 启动一次导入：解析 → 落库 → 出报告，全部由控制器负责。 */
    fun startImport(source: ImportSource, uri: Uri) {
        vaultImportController?.startImport(source, uri)
    }

    /** 关闭导入结果报告对话框。 */
    fun dismissImportReport() {
        vaultImportController?.reset()
    }

    // ===================== ISSUE-P3-20：子库挂载（UI 接线） =====================

    /**
     * 已挂载子库计数（[SettingsUiState.childDatabasesCount] 的**真实数据源**），
     * 替代原 `DatabaseConfigUiState` 中硬编码的 `0`。
     *
     * 语义 = **已挂载**（注册表长度，含未解锁者），非「已解锁数」；控制器缺失时恒为 0
     * —— 回落而非谎报（不注入控制器时 UI 也同时被禁用，两者自洽）。
     */
    private val childDatabaseCountFlow: Flow<Int> =
        childDatabaseSessionManager?.mountedCount ?: MutableStateFlow(0)

    /** 子库操作反馈（挂载/解锁/卸载的成功与失败文案），由对话框消费后清除 */
    private val childDatabaseFeedbackFlow = MutableStateFlow<ChildDatabaseFeedback?>(null)

    /**
     * 子库挂载面板状态：核心层挂载记录 + 运行时状态 → 展示态（无凭据、无条目明文）。
     *
     * 控制器缺失时如实下发 `available = false`（UI 整体禁用），不呈现任何假入口。
     */
    val childDatabaseState: StateFlow<ChildDatabaseUiState> =
        childDatabaseSessionManager?.let { manager ->
            combine(
                manager.mounts,
                manager.mountStates,
                childDatabaseFeedbackFlow
            ) { mounts, states, feedback ->
                ChildDatabaseUiState(
                    available = true,
                    mounts = mounts.map { mount ->
                        // 未纳入状态表一律回落 Closed（与 ChildDatabaseSessionManager.stateOf 同一口径）
                        val state = states[mount.id] ?: ChildDatabaseMountState.Closed
                        ChildDatabaseMountUiState(
                            mountId = mount.id,
                            alias = mount.alias,
                            status = ChildDatabaseStatusText.of(state),
                            canRetryWithCredentials = ChildDatabaseStatusText.allowsCredentialRetry(state)
                        )
                    },
                    feedback = feedback
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STATE_SUBSCRIBE_TIMEOUT_MILLIS),
                initialValue = ChildDatabaseUiState(available = true)
            )
        } ?: MutableStateFlow(ChildDatabaseUiState(available = false))

    /**
     * 挂载并首次打开一个子库（核心层原子语义：只有真实解密成功才登记挂载）。
     *
     * [passwordChars] / [keyFileUri] 为**借用语义**：密码数组在本次回调返回后即由输入组件擦除，
     * 故本层先落自有副本并在用毕清零；密钥文件字节经 [keyFileAccess] 读取，同样用毕清零。
     * `content://` 来源在 SAF 选择后**立即申请持久化读授权**，否则进程重启后核心层只能如实报
     * `SOURCE_UNAVAILABLE`（授权申请失败不阻断本次挂载，但必须留痕，不静默）。
     */
    fun mountChildDatabase(
        alias: String,
        sourceUri: String,
        passwordChars: CharArray,
        keyFileUri: String?
    ) {
        val password = passwordChars.copyOf()
        viewModelScope.launch {
            var keyFileBytes: ByteArray? = null
            try {
                val manager = childDatabaseManagerOrReport() ?: return@launch
                if (sourceUri.startsWith(ChildDatabaseLimits.CONTENT_SCHEME)) {
                    persistChildSourcePermission(sourceUri)
                }
                keyFileBytes = readChildKeyFileBytes(keyFileUri)
                if (keyFileUri != null && keyFileBytes == null) return@launch
                when (val result = manager.mount(alias, sourceUri, password, keyFileBytes)) {
                    is KdbxResult.Success ->
                        childDatabaseFeedbackFlow.value = childDatabaseMountedFeedback()

                    is KdbxResult.Failure ->
                        childDatabaseFeedbackFlow.value = childDatabaseFailureFeedback(result.error)
                }
            } finally {
                // 借用副本用毕即清零（无论成功、失败还是提前返回）
                password.fill(ZERO_CHAR)
                keyFileBytes?.fill(ZERO_BYTE)
            }
        }
    }

    /**
     * 为已挂载子库**重新提供凭据**解锁。
     *
     * 覆盖三类真实场景：进程重启 / 根库锁定后凭据已被清零（须重新输入，属有意的安全语义）、
     * 凭据被拒后重试、来源恢复后重试。挂载记录始终保留（非敏感配置不因解密失败丢失）。
     */
    fun unlockChildDatabase(
        mountId: String,
        passwordChars: CharArray,
        keyFileUri: String?
    ) {
        val password = passwordChars.copyOf()
        viewModelScope.launch {
            var keyFileBytes: ByteArray? = null
            try {
                val manager = childDatabaseManagerOrReport() ?: return@launch
                keyFileBytes = readChildKeyFileBytes(keyFileUri)
                if (keyFileUri != null && keyFileBytes == null) return@launch
                when (val result = manager.open(mountId, password, keyFileBytes)) {
                    // 成功：清空反馈，条目数由状态文案（Opened 快照）如实呈现
                    is KdbxResult.Success -> childDatabaseFeedbackFlow.value = null

                    is KdbxResult.Failure ->
                        childDatabaseFeedbackFlow.value = childDatabaseFailureFeedback(result.error)
                }
            } finally {
                password.fill(ZERO_CHAR)
                keyFileBytes?.fill(ZERO_BYTE)
            }
        }
    }

    /** 卸载子库：终止会话、清零其凭据并摘除登记（**不删除**来源文件，文案已如实说明） */
    fun unmountChildDatabase(mountId: String) {
        viewModelScope.launch {
            val manager = childDatabaseManagerOrReport() ?: return@launch
            when (val result = manager.unmount(mountId)) {
                is KdbxResult.Success -> childDatabaseFeedbackFlow.value = null
                is KdbxResult.Failure ->
                    childDatabaseFeedbackFlow.value = childDatabaseFailureFeedback(result.error)
            }
        }
    }

    /** 清除子库操作反馈（对话框关闭或用户已读） */
    fun dismissChildDatabaseFeedback() {
        childDatabaseFeedbackFlow.value = null
    }

    /** 取核心层控制器；缺失（仅单测 / 异常装配）时上浮统一失败反馈并返回 null */
    private fun childDatabaseManagerOrReport(): ChildDatabaseSessionManager? {
        val manager = childDatabaseSessionManager
        if (manager == null) {
            childDatabaseFeedbackFlow.value = ChildDatabaseFeedback(
                UiMessage(R.string.dbset_child_db_err_unknown),
                isError = true
            )
        }
        return manager
    }

    /**
     * 申请来源的持久化读授权（SAF 选择后立即执行）。
     *
     * 失败**不阻断**本次挂载（本次会话仍可读，核心层已读到字节），但必须留痕：
     * 授权失效只会在进程重启后才暴露为 `SOURCE_UNAVAILABLE`，静默会让该现象无法追溯。
     * 日志不含 Uri（避免来源定位信息外泄）。
     */
    private suspend fun persistChildSourcePermission(sourceUri: String) {
        val access = keyFileAccess
        if (access == null) {
            debugLogBuffer.warn(TAG, "子库来源持久化读授权通道缺失：进程重启后需重新选择来源")
            return
        }
        if (!access.persistReadPermission(sourceUri)) {
            debugLogBuffer.warn(TAG, "子库来源提供方不支持持久化读授权：仅本次会话可读")
        }
    }

    /**
     * 读取可选密钥文件字节：未选择返回 null；读取失败（含通道缺失）上浮反馈并返回 null，
     * 调用方据此**中止**本次挂载 —— 绝不静默降级为「仅主密码」，那只会得到误导性的「凭据被拒」。
     */
    private suspend fun readChildKeyFileBytes(keyFileUri: String?): ByteArray? {
        val requested = keyFileUri?.takeIf { it.isNotBlank() } ?: return null
        val access = keyFileAccess
        if (access == null) {
            debugLogBuffer.warn(TAG, "子库密钥文件读取通道缺失，拒绝以缺失密钥文件继续")
            childDatabaseFeedbackFlow.value = childDatabaseKeyFileFeedback()
            return null
        }
        return when (val result = access.read(requested)) {
            is KeyFileReadResult.Success -> result.bytes

            else -> {
                // 仅留痕分型名（Empty / TooLarge / Unreadable），不外传 Uri 与异常 message
                debugLogBuffer.warn(TAG, "子库密钥文件不可用: ${result.javaClass.simpleName}")
                childDatabaseFeedbackFlow.value = childDatabaseKeyFileFeedback()
                null
            }
        }
    }

    /** 密钥文件不可用（未选/读不到/空文件/超限）的统一反馈：复用解锁特性既有文案 */
    private fun childDatabaseKeyFileFeedback(): ChildDatabaseFeedback =
        ChildDatabaseFeedback(UiMessage(R.string.unlock_keyfile_read_failed), isError = true)

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

    // ISSUE-P2-01：S3 AccessKey ID 一次性预填通道（明文不进 UiState）
    val s3AccessKeyPrefill: StateFlow<CharArray?> get() = syncController.s3AccessKeyPrefill

    /** Wave 15 整改：用户开始编辑密码后终结预填通道生命周期（防旋转后旧值回写覆盖用户输入） */
    fun clearWebDavPasswordPrefill() = syncController.clearWebDavPasswordPrefill()

    fun clearS3SecretKeyPrefill() = syncController.clearS3SecretKeyPrefill()

    /** ISSUE-P2-01：用户开始编辑 AccessKey 后终结预填通道生命周期（语义同上） */
    fun clearS3AccessKeyPrefill() = syncController.clearS3AccessKeyPrefill()

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
            checkForDuplicateUuids = true
            // ISSUE-P3-20：childDatabasesCount 字段已整体移除——它原先承载的硬编码 0
            // 会与真实挂载数冲突；真实值改由 childDatabaseCountFlow（核心层 mountedCount）下发
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
        val checkForDuplicateUuids: Boolean
    )

    private data class SecurityTimeoutUiState(
        val autoLockTimeoutSeconds: Int
    )

    /**
     * ISSUE-P2-08：完整性扫描快照流。未注入检测器时恒为 null（UI 不渲染风险卡片），
     * 绝不回填「安全」假值误导用户。
     */
    private val integrityReportFlow: Flow<RuntimeIntegrityReport?> =
        runtimeIntegrityDetector?.report ?: MutableStateFlow<RuntimeIntegrityReport?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.getSettings(),
        syncController.state,
        healthController.state,
        combine(autofillStateFlow, databaseConfigStateFlow) { af, db -> Pair(af, db) },
        combine(
            combine(securityTimeoutStateFlow, extendedSettingsFlow, debugLogLinesFlow) { sec, ext, logs ->
                Triple(sec, ext, logs)
            },
            integrityReportFlow,
            // ISSUE-P3-20：子库已挂载计数并入同一条 combine（替代原先硬编码的 0）
            childDatabaseCountFlow
        ) { securityState, integrityReport, mountedChildDatabases ->
            Triple(securityState, integrityReport, mountedChildDatabases)
        }
    ) { userSettings, syncState, healthState, (autofillState, dbState), (securityState, integrityReport, mountedChildDatabases) ->
        val (secState, extState, debugLogLines) = securityState
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
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_SUBSCRIBE_TIMEOUT_MILLIS),
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

    /**
     * Wave 15 整改：SecretKey 以 [CharArray] 借用语义提交（语义同 [updateWebDavConfig]）；
     * ISSUE-P2-01：AccessKey ID 亦改为 [CharArray] 借用语义（消费后即擦除，不驻留状态流）
     */
    fun updateS3Config(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
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
        // ISSUE-P2-11 (ZT-16)：设置即下发到唯一会话实例，下一次写盘立即遵循新偏好
        // （关闭时不再生成 .bak，并清理历史遗留 .bak）。
        databaseSession?.createBackupBeforeSave = enabled
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
        // ISSUE-P2-01：AccessKey ID 预填通道随销毁一并擦除
        syncController.clearS3AccessKeyPrefill()
        super.onCleared()
    }
}
