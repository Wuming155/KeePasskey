package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ActiveDatabaseIdStore
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCacheEvent
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.merge.FieldResolutionChoice
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.model.cleanEtag
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用同步串行编排、StateFlow 与测试入口；同步周期由 [SyncCycleRunner]、冲突由 [SyncConflictController] 承担。
 * Provider / 编解码 / 变化判定 / 偏好 / 跨周期状态分别由 [SyncProviderResolver]、[SyncDatabaseCodec]、
 * [SyncContentChangeDetector]、[SyncPreferences]、[SyncSessionState] 协作完成。
 * 敏感凭据借用后显式擦除；加密合并走 Dispatchers.Default，网络与文件 IO 走 Dispatchers.IO。
 * 既有公共 API 保持兼容；完整依赖拓扑见 `docs/architecture/ARCHITECTURE.md`。
 */
@Singleton
open class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseSession: DatabaseSession,
    private val syncCredentialsStore: SyncCredentialsStore,
    private val debugLog: DebugLogBuffer,
    // TASK-21：用户可见错误消息经 StringsProvider 资源解析（P3-23；单测注入假实现）
    private val strings: StringsProvider? = null,
    /**
     * ISSUE-P3-03 (43a)：进阶同步偏好源（分块上传、上传前远端比对、冲突策略、详细日志）。
     * 可空 + 默认 null 仅为保持既有单测构造点兼容——null 时全部按 [ExtendedSettings] 默认值
     * 处理（即接线前的行为），生产路径由 Hilt 注入真实偏好源。
     */
    private val extendedSettingsStore: ExtendedSettingsStore? = null,
    // ISSUE-P3-25：拆分出的协作方。生产路径由 Hilt 构造注入（各协作方均为 @Singleton，
    // 与协调器同生命周期并共享同一 SyncSessionState）；可空 + 默认 null 仅为保持既有
    // 4/5 参单测构造点兼容，null 时按下方形参与 Hilt 完全等价地就地装配（行为不变）。
    syncSession: SyncSessionState? = null,
    syncPreferences: SyncPreferences? = null,
    syncProviderResolver: SyncProviderResolver? = null,
    syncDatabaseCodec: SyncDatabaseCodec? = null,
    syncContentChanges: SyncContentChangeDetector? = null,
    syncConflicts: SyncConflictController? = null,
    syncCycle: SyncCycleRunner? = null,
    // ISSUE-P2-291：库身份绑定登记（生产 Hilt 注入；既有单测 4/5 参构造点为 null，
    // 就地装配时同步关闭绑定闸，行为与整改前一致）
    syncVaultBindingStore: SyncVaultBindingStore? = null,
    // ISSUE-P3-528：主凭据轮换后的「云端副本待替换」持久标记（生产 Hilt 注入；
    // 既有单测构造点为 null，就地装配时本机制整体旁路，行为与整改前一致）
    syncCredentialRotationStore: SyncCredentialRotationStore? = null,
    // ISSUE-P2-536：「本地库文件自上次成功同步以来未被重写」进程内检查点（同上口径）
    syncLocalFileCheckpoint: SyncLocalFileCheckpoint? = null,
    // ISSUE-P3-366 AC②：同步（含三方合并与落盘）期间挂锁。可空 + 默认 null 仅为保持
    // 既有单测构造点兼容（同 strings / extendedSettingsStore 先例），生产路径由 Hilt 注入
    // @Singleton 守护——与保存挂点（RealVaultRepository）共享同一挂锁闸
    private val autoLockGuard: AutoLockSessionGuard? = null,
    // ISSUE-P2-627：持久化非秘密锁态元数据探测结果；可空仅兼容既有 JVM 构造点。
    private val periodicSyncProbeStore: PeriodicSyncProbeStore? = null
) : SessionLockObserver {
    private val effectiveStrings: StringsProvider =
        strings ?: StringsProvider { id, args -> context.getString(id, *args) }

    private val session: SyncSessionState = syncSession ?: SyncSessionState()
    private val activeDatabaseIdStore by lazy { ActiveDatabaseIdStore(context) }
    private val probeVaultNamespace by lazy {
        ActiveVaultSyncNamespace(
            filesDir = { context.filesDir },
            sessionPathIdentifier = { databaseSession.currentPathIdentifier },
            sessionFilePath = { databaseSession.currentFile?.absolutePath },
            persistedActiveId = { activeDatabaseIdStore.load() }
        )
    }
    private val preferences: SyncPreferences = syncPreferences
        ?: SyncPreferences(debugLog, extendedSettingsStore)
    private val providers: SyncProviderResolver = syncProviderResolver
        ?: SyncProviderResolver(syncCredentialsStore, preferences, debugLog)
    private val codec: SyncDatabaseCodec = syncDatabaseCodec
        ?: SyncDatabaseCodec(databaseSession, debugLog)
    private val conflicts: SyncConflictController = syncConflicts
        ?: SyncConflictController(databaseSession, codec, effectiveStrings, session, debugLog)
    private val changes: SyncContentChangeDetector = syncContentChanges
        ?: SyncContentChangeDetector(codec, session)
    /** `ISSUE-P3-528`：主凭据轮换标记表（null = 手工装配路径 / 既有单测，本机制整体旁路）。 */
    private val credentialRotation: SyncCredentialRotationStore? = syncCredentialRotationStore
    private val cycle: SyncCycleRunner = syncCycle
        ?: SyncCycleRunner(
            context = context,
            databaseSession = databaseSession,
            session = session,
            providerResolver = providers,
            codec = codec,
            conflicts = conflicts,
            changes = changes,
            preferences = preferences,
            strings = effectiveStrings,
            vaultBindingStore = syncVaultBindingStore,
            credentialRotationStore = syncCredentialRotationStore,
            localFileCheckpoint = syncLocalFileCheckpoint,
            autoLockGuard = autoLockGuard
        )

    init {
        // ISSUE-P1-07：本协调器是单例，lastSyncedDb / pendingLocalDb / pendingRemoteDb 持有
        // 整棵 KdbxDatabase 树（含全部 ProtectedString）。锁库只销毁会话内的树，
        // 若不在此同步释放，这些副本会跨锁定周期长期驻留——内存侧的生命周期同样需要终止点。
        // 磁盘侧缓存（cacheDir/sync 的 .cache/.basecache）由 SyncCacheEvictor 独立负责。
        databaseSession.addLockObserver(this)
    }

    /** 当前待决冲突清单（ISSUE-P3-25：状态下沉 [SyncConflictController]，对外实例语义不变）。 */
    open val conflictFlow: StateFlow<List<ConflictedEntryPair>> get() = conflicts.conflictFlow

    // ICacheSupervisor 六事件接线：每次同步周期结束后把引擎事件发布给上层订阅者
    private val _syncEvents = MutableSharedFlow<SyncCacheEvent>(extraBufferCapacity = 64)
    open val syncEvents: SharedFlow<SyncCacheEvent> = _syncEvents.asSharedFlow()

    /**
     * 最近一次同步周期的结果（ISSUE-P3-298 ④：后台失败可见性的数据源）。
     *
     * 此前 [syncEvents] 全仓无订阅者、[SyncOutcome] 又只经返回值交付——周期任务
     * （`PeriodicSyncWorker` 返回 `Result.success()`）把失败整条吞掉，用户对
     * 「库已连续数周未同步」零感知。现在每个同步周期收尾处写入本流，
     * 由 [SyncFailureNotifier] 订阅：`Error` → 静默失败通知；其余结果 → 撤下通知。
     * null = 本进程尚无同步周期。
     */
    private val _lastOutcome = MutableStateFlow<SyncOutcome?>(null)
    open val lastOutcome: StateFlow<SyncOutcome?> = _lastOutcome.asStateFlow()

    private val _recentSyncEvents = MutableStateFlow<List<SyncCacheEvent>>(emptyList())
    open val recentSyncEvents: StateFlow<List<SyncCacheEvent>> = _recentSyncEvents.asStateFlow()

    /** ETag 只可与建立它时的同一非秘密目标身份比较。 */
    @Volatile
    private var probeBaselineTargetIdentity: String? = null

    /**
     * 离线模式开关：开启后同步引擎直接读本地缓存，不触碰网络。
     *
     * ISSUE-P3-25 拆分：取值委托给 [SyncSessionState]（`@Volatile` 保证不变）。
     * 本属性刻意声明为**只读 `val`**：若写成带 `private set` 的 `var`，Kotlin 会为
     * `isXxx` 形式的布尔属性生成 JVM 方法 `setOfflineMode(Z)V`，与下方公开的
     * [setOfflineMode] 构成 Platform declaration clash（编译期失败）。
     * 对外可变性本就不存在（原实现的 setter 即为 `private`），故改 `val` 不改变任何公开 API。
     */
    val isOfflineMode: Boolean
        get() = session.isOfflineMode

    // 允许单元测试注入模拟 Provider 与测试路径（禁止生产代码赋值）
    @androidx.annotation.VisibleForTesting
    var testSyncProvider: SyncProvider?
        get() = session.testSyncProvider
        set(value) {
            session.testSyncProvider = value
        }

    @androidx.annotation.VisibleForTesting
    var testRemotePath: String?
        get() = session.testRemotePath
        set(value) {
            session.testRemotePath = value
        }

    /**
     * ISSUE-P1-07：会话终止（锁定/关闭/换库前置释放）时释放本协调器持有的全部数据库树副本。
     * 回调在会话锁内同步触发，此处不做任何阻塞操作。
     *
     * 两个持有者的收口方式不同（`ISSUE-P3-235` G2 起明确）：
     * - [SyncSessionState.clear]：`lastSyncedDb` 恒为**会话活动树的引用**（全仓赋值点右侧均为
     *   `databaseSession.databaseFlow.value`），其擦除由 `DatabaseSession.lock()/close()` 对活动树
     *   的 `clearSensitiveData()` 承担，故此处**只丢引用**即为正确形态；
     * - [SyncConflictController.clearPendingConflictSession]：冲突待决期的 `pendingRemoteDb`
     *   （独立解析树）与 `pendingMergedRoot` 的合并器新建节点**不属于**活动树 ——
     *   此时活动树已被 [DatabaseSession] 先行擦除并置空（存活侧为空）⇒ 在该方法内**全量擦除**，
     *   不留给 GC（契约 `docs/architecture/敏感缓冲所有权契约.md` §3 R3 / R5）。
     */
    override fun onSessionLocked() {
        session.clear()
        probeBaselineTargetIdentity = null
        conflicts.clearPendingConflictSession()
        _recentSyncEvents.value = emptyList()
    }

    /**
     * H4-断点11 整改：解锁后自动重同步前判断是否已配置云同步（不触发网络）。
     * 读取凭据存储在未配置存储环境（如 JVM 单测的空壳 Context）下可能失败，
     * 此时按「未配置」处理并记录日志，绝不向上抛出。
     */
    fun isSyncConfigured(): Boolean {
        return try {
            providers.hasConfiguredProvider()
        } catch (e: Exception) {
            debugLog.warn(SYNC_LOG_TAG, "探测同步配置失败，按未配置处理: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * 设置离线模式（由设置页「使用离线缓存」开关驱动）
     */
    fun setOfflineMode(enabled: Boolean) {
        session.isOfflineMode = enabled
    }

    /** 成功换密后登记本地重加密事实；未配置同步则空操作，不触发网络。 */
    suspend fun markLocalVaultRecrypted(): Boolean = markCredentialRotationTarget(captureCredentialRotationTarget())

    /** 受理时同步捕获当前凭据票据对应的非秘密远端路径快照。 */
    fun captureCredentialRotationTargetSnapshot(): String? {
        if (credentialRotation == null) return null
        val path = databaseSession.currentPathIdentifier ?: return null
        val fileName = if (path.startsWith("content://")) null else java.io.File(path).name
        val defaultName = fileName?.takeIf(String::isNotBlank)
            ?: databaseSession.databaseFlow.value?.let { "${it.databaseName}.kdbx" }
            ?: return null
        return session.testRemotePath
            ?: syncCredentialsStore.captureRotationRemotePath(path, defaultName)
    }

    /** 轮换前固定远端身份，提交后不能按已切换的活动库登记同步事实。 */
    suspend fun captureCredentialRotationTarget(): String? = withContext(Dispatchers.IO) {
        captureCredentialRotationTargetSnapshot()
    }

    /** 仅写入已捕获目标的本地标记，不触发网络或重读活动库。 */
    suspend fun markCredentialRotationTarget(remotePath: String?): Boolean = withContext(Dispatchers.IO) {
        val store = credentialRotation ?: return@withContext false
        if (remotePath == null) return@withContext false
        try {
            store.markRecrypted(remotePath)
            debugLog.info(SYNC_LOG_TAG, "主凭据已轮换：已登记云端副本待替换")
            true
        } catch (e: Exception) { // cancel-n/a: 非挂起偏好登记
            debugLog.warn(SYNC_LOG_TAG, "登记云端副本待替换失败: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * 执行全量即时同步
     *
     * ISSUE-P2-625：挂锁由 [SyncCycleRunner] 在取得同步互斥锁后，仅包围本地密文快照准备段；
     * 网络请求与重试期间不持有挂锁，熄屏/后台锁定可立即清理会话和主凭据。
     */
    suspend fun syncNow(): SyncOutcome {
        debugLog.info(SYNC_LOG_TAG, "手动/自动同步开始")
        val probeTargetBefore = if (periodicSyncProbeStore != null && databaseSession.databaseFlow.value != null) {
            resolveProbeTarget()
        } else {
            null
        }
        val outcome = cycle.runSyncCycle()
        publishSyncEvents()
        _lastOutcome.value = outcome
        if (probeTargetBefore != null) {
            persistProbeStateAfterSync(probeTargetBefore, outcome)
        }
        // ISSUE-P3-381：远端 ETag 探测基线不在本周期直接读引擎 cache（internal）；
        // 锁态 Worker 与 ResumeSyncProbeCoordinator 共用 P2-627 的持久化元数据基线。
        debugLog.info(SYNC_LOG_TAG, "同步结束: ${syncOutcomeDescription(outcome)}")
        return outcome
    }

    /** 周期 Worker 分流：开库走完整 KDBX 周期，锁库/冷启仅探测元数据。 */
    internal suspend fun runPeriodicSync(): PeriodicSyncWorkResult {
        if (databaseSession.databaseFlow.value != null) {
            return PeriodicSyncWorkResult.FullSync(syncNow())
        }
        val probeResult = session.mutex.withLock {
            if (databaseSession.databaseFlow.value != null) null else probeLockedRemoteMetadata()
        }
        return probeResult ?: PeriodicSyncWorkResult.FullSync(syncNow())
    }

    private suspend fun probeLockedRemoteMetadata(): PeriodicSyncWorkResult {
        val store = periodicSyncProbeStore
            ?: return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PROBE_STATE_CORRUPT)
        val vaultIdentity = currentProbeVaultIdentity()
            ?: return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.ACTIVE_VAULT_UNKNOWN)
        val defaultFileName = probeDefaultFileName(vaultIdentity)

        val providerResult = try {
            providers.useProviderWithTarget(
                defaultFileName = defaultFileName,
                testOverride = session.testSyncProvider
            ) { provider, configuredTarget ->
                val target = configuredTarget ?: session.testRemotePath?.let { testPath ->
                    SyncProviderTarget(testPath, probeTargetIdentity(vaultIdentity, testPath))
                }
                if (target == null || isUnresolvedDefaultPath(target.remotePath, vaultIdentity)) {
                    return@useProviderWithTarget PeriodicSyncWorkResult.NeedsUnlock(
                        PeriodicSyncNeedsUnlockReason.TARGET_UNAVAILABLE
                    )
                }
                if (currentProbeVaultIdentity() != vaultIdentity ||
                    databaseSession.databaseFlow.value != null
                ) {
                    return@useProviderWithTarget PeriodicSyncWorkResult.NeedsUnlock(
                        PeriodicSyncNeedsUnlockReason.TARGET_CHANGED
                    )
                }

                val targetIdentity = probeTargetIdentity(vaultIdentity, target.identity)
                when (val preflight = PeriodicSyncProbePolicy.preflight(
                    store.read(vaultIdentity),
                    targetIdentity
                )) {
                    is PeriodicSyncProbePolicy.Preflight.NeedsUnlock ->
                        return@useProviderWithTarget PeriodicSyncWorkResult.NeedsUnlock(preflight.reason)

                    is PeriodicSyncProbePolicy.Preflight.RequestMetadata -> {
                        val metadata = try {
                            provider.getMetadata(target.remotePath)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }

                        if (currentProbeVaultIdentity() != vaultIdentity ||
                            databaseSession.databaseFlow.value != null
                        ) {
                            return@useProviderWithTarget PeriodicSyncWorkResult.NeedsUnlock(
                                PeriodicSyncNeedsUnlockReason.TARGET_CHANGED
                            )
                        }
                        if (configuredTarget != null) {
                            val currentConfiguredTarget = providers.configuredTarget(defaultFileName)
                            if (currentConfiguredTarget?.identity != configuredTarget.identity ||
                                currentConfiguredTarget.remotePath != configuredTarget.remotePath
                            ) {
                                return@useProviderWithTarget PeriodicSyncWorkResult.NeedsUnlock(
                                    PeriodicSyncNeedsUnlockReason.TARGET_CHANGED
                                )
                            }
                        }

                        val failure = metadata.exceptionOrNull()
                        if (failure != null) {
                            when (classifyLockedProbeFailure(failure)) {
                                LockedProbeFailure.AUTH -> PeriodicSyncWorkResult.AuthenticationRequired
                                LockedProbeFailure.TEMPORARY -> PeriodicSyncWorkResult.TemporaryNetworkFailure
                                LockedProbeFailure.MISSING -> writePendingProbeState(
                                    store,
                                    vaultIdentity,
                                    targetIdentity,
                                    observedEtag = "",
                                    PeriodicSyncNeedsUnlockReason.REMOTE_MISSING
                                )

                                LockedProbeFailure.REJECTED ->
                                    PeriodicSyncWorkResult.RemoteRejected(failure.javaClass.simpleName)
                            }
                        } else {
                            val currentEtag = cleanEtag(metadata.getOrNull()?.etag)
                            when (val observation = PeriodicSyncProbePolicy.observe(
                                preflight.previousEtag,
                                currentEtag
                            )) {
                                PeriodicSyncProbePolicy.Observation.Unchanged ->
                                    PeriodicSyncWorkResult.MetadataUnchanged

                                is PeriodicSyncProbePolicy.Observation.NeedsUnlock -> writePendingProbeState(
                                    store,
                                    vaultIdentity,
                                    targetIdentity,
                                    currentEtag,
                                    observation.reason
                                )
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return PeriodicSyncWorkResult.RemoteRejected(failure.javaClass.simpleName)
        }

        if (providerResult != null) return providerResult

        // Provider 构造失败可能是凭据无法解封。这里只读取非秘密目标描述，以区分未配置和认证失败。
        return try {
            if (providers.configuredTarget(defaultFileName) == null) {
                PeriodicSyncWorkResult.NotConfigured
            } else {
                PeriodicSyncWorkResult.AuthenticationRequired
            }
        } catch (_: Exception) {
            PeriodicSyncWorkResult.ConfigurationInvalid
        }
    }

    private fun writePendingProbeState(
        store: PeriodicSyncProbeStore,
        vaultIdentity: String,
        targetIdentity: String,
        observedEtag: String,
        reason: PeriodicSyncNeedsUnlockReason
    ): PeriodicSyncWorkResult {
        val written = store.write(
            vaultIdentity,
            PeriodicSyncProbeRecord(
                targetIdentity = targetIdentity,
                observedEtag = observedEtag,
                needsUnlock = true
            )
        )
        return PeriodicSyncWorkResult.NeedsUnlock(
            if (written) reason else PeriodicSyncNeedsUnlockReason.PROBE_STATE_WRITE_FAILED
        )
    }

    private suspend fun persistProbeStateAfterSync(targetBefore: SyncProviderTarget, outcome: SyncOutcome) {
        val store = periodicSyncProbeStore ?: return
        val vaultIdentity = currentProbeVaultIdentity() ?: return
        val targetAfter = resolveProbeTarget() ?: return
        if (targetAfter.identity != targetBefore.identity || targetAfter.remotePath != targetBefore.remotePath) return
        val targetIdentity = targetAfter.identity
        val existing = (store.read(vaultIdentity) as? PeriodicSyncProbeRead.Stored)?.record
            ?.takeIf { it.targetIdentity == targetIdentity }
        val etag = cleanEtag(lastRemoteEtagForProbe(targetIdentity))
            .ifBlank { existing?.observedEtag.orEmpty() }
        val fullyConverged = when (outcome) {
            SyncOutcome.UpToDate, SyncOutcome.UploadedLocal, SyncOutcome.MergedAndUploaded -> true
            SyncOutcome.LocalPendingChanges,
            is SyncOutcome.ConflictNeedsUser,
            is SyncOutcome.VaultBindingMismatch -> false
            else -> return
        }
        if (fullyConverged && etag.isBlank()) {
            // No trustworthy post-sync ETag is available. Clear stale pending state; the next
            // locked probe establishes a fresh observation and never treats it as content proof.
            withContext(Dispatchers.IO) { store.clear(vaultIdentity) }
            return
        }
        withContext(Dispatchers.IO) {
            store.write(vaultIdentity, PeriodicSyncProbeRecord(targetIdentity, etag, needsUnlock = !fullyConverged))
        }
    }

    private fun currentProbeVaultIdentity(): String? = probeVaultNamespace.currentId()

    private fun probeDefaultFileName(vaultIdentity: String): String {
        databaseSession.currentFile?.name?.takeIf(String::isNotBlank)?.let { return it }
        databaseSession.databaseFlow.value?.databaseName?.takeIf(String::isNotBlank)?.let { return "$it.kdbx" }
        if (!vaultIdentity.startsWith(CONTENT_URI_PREFIX)) {
            File(vaultIdentity).name.takeIf(String::isNotBlank)?.let { return it }
        }
        // 锁定时无法从 SAF URI 还原 KDBX 名称。该哨兵路径不会发送请求；依赖此回退名时按安全失败处理。
        return UNKNOWN_DEFAULT_REMOTE_FILE_NAME
    }

    private fun isUnresolvedDefaultPath(remotePath: String, vaultIdentity: String): Boolean {
        val normalized = remotePath.trim().trimStart('/')
        if (normalized == UNKNOWN_DEFAULT_REMOTE_FILE_NAME) return true
        // SAF 冷启动 URI 无法还原 KDBX 文件名。Provider 默认路径即使语法有效也可能指向错误文件，
        // 因此只有明确配置的非默认路径才允许探测。
        return vaultIdentity.startsWith(CONTENT_URI_PREFIX) &&
            normalized == WebDavDefaults.DEFAULT_REMOTE_PATH
    }

    private fun classifyLockedProbeFailure(failure: Throwable): LockedProbeFailure = when {
        failure is SyncException.AuthenticationError -> LockedProbeFailure.AUTH
        failure is SyncException.ProtocolError && failure.statusCode in AUTH_STATUS_CODES -> LockedProbeFailure.AUTH
        failure is SyncException.ProtocolError &&
            (failure.statusCode in TRANSIENT_STATUS_CODES || failure.statusCode >= HTTP_SERVER_ERROR) ->
            LockedProbeFailure.TEMPORARY
        failure is SyncException.NetworkError -> LockedProbeFailure.TEMPORARY
        failure is SyncException.FileNotFound -> LockedProbeFailure.MISSING
        else -> LockedProbeFailure.REJECTED
    }

    private enum class LockedProbeFailure { AUTH, TEMPORARY, MISSING, REJECTED }

    /**
     * `ISSUE-P2-291` AC②：用户在绑定不符对话框中显式确认「整库覆盖并绑定当前库」后
     * 的执行入口（语义见 [SyncCycleRunner.takeoverVaultBinding]——改绑归属 + 无预条件
     * 整库上传，云端原属另一库的副本被整体替换）。与 [syncNow] 共享同一互斥锁。
     */
    suspend fun confirmVaultBindingTakeover(): SyncOutcome {
        debugLog.info(SYNC_LOG_TAG, "库身份改绑确认：整库覆盖上传开始")
        val outcome = cycle.takeoverVaultBinding()
        publishSyncEvents()
        _lastOutcome.value = outcome
        debugLog.info(SYNC_LOG_TAG, "库身份改绑确认结束: ${syncOutcomeDescription(outcome)}")
        return outcome
    }

    /**
     * `ISSUE-P2-529` AC①：把当前云端副本另存为本地独立库；失败即 Failure，调用方据此**中止覆盖**。
     * 下载期间允许自动锁定；若锁定导致后续解密无法完成，操作失败且正式库仍不变。
     */
    suspend fun backupCloudVaultCopy(): KdbxResult<String> {
        return cycle.backupRemoteVaultCopy()
    }

    /**
     * 解决冲突并执行最终提交回写（ISSUE-P3-25：决策逻辑下沉 [SyncConflictController]）。
     *
     * 串行化边界与拆分前一致：整个决策-提交过程在 [SyncSessionState.mutex] 内执行
     * （与同步周期互斥）。条目级/字段级决策的语义详见
     * [SyncConflictController.resolveConflicts]。
     */
    open suspend fun resolveConflicts(
        resolutions: Map<String, ConflictResolutionChoice>,
        fieldResolutions: Map<String, Map<String, FieldResolutionChoice>> = emptyMap()
    ): SyncOutcome {
        // ISSUE-P2-625：等待同步互斥锁期间不挂起安全锁定；只在取得锁后保护本地裁决段。
        var holdoffActive = false
        return try {
            session.mutex.withLock {
                autoLockGuard?.beginLongTask()
                holdoffActive = autoLockGuard != null
                conflicts.resolveConflicts(
                    resolutions,
                    fieldResolutions,
                    releaseLocalHoldoff = {
                        if (holdoffActive) {
                            holdoffActive = false
                            autoLockGuard?.endLongTask()
                        }
                    },
                    autoLockGuard = autoLockGuard
                )
            }
        } finally {
            if (holdoffActive) autoLockGuard?.endLongTask()
        }
    }

    /**
     * 测试当前云端同步配置连接连通性
     */
    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            providers.useProvider(testSyncProvider) { provider ->
                provider.testConnection()
            } ?: return@withContext Result.failure(
                    IllegalStateException(effectiveStrings.get(R.string.sync_error_test_no_credentials))
                )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * ISSUE-P3-381：回前台 / 网络恢复探测用——解析远端库路径（未配置时返回 null）。
     * 与 [SyncProviderResolver.resolveRemotePath] 同源；默认文件名取活动库名或常量兜底。
     */
    internal fun resolveProbeTarget(): SyncProviderTarget? {
        // §411（ISSUE-P3-447 局部收口）：SAF 库 currentFile 为 null 时以库名兜底，
        // 与 runSyncCycle / takeoverVaultBinding 的远端路径解析同口径
        val activeName = databaseSession.currentFile?.name
            ?: databaseSession.databaseFlow.value?.databaseName?.let { "$it.kdbx" }
            ?: return null
        return try {
            val target = providers.configuredTarget(activeName) ?: return null
            val vaultIdentity = databaseSession.currentPathIdentifier
                ?: databaseSession.currentFile?.absolutePath
                ?: databaseSession.databaseFlow.value?.databaseName.orEmpty()
            target.copy(identity = probeTargetIdentity(vaultIdentity, target.identity))
        } catch (_: Exception) {
            null
        }
    }

    private fun probeTargetIdentity(vaultIdentity: String, targetIdentity: String): String {
        val bytes = "$vaultIdentity\u0000$targetIdentity".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    /** 回前台探测通过同一拥有式 Provider 作用域执行；测试覆写只作为借用接口实现。 */
    internal suspend fun <T> useProviderForProbe(block: suspend (SyncProvider) -> T): T? =
        providers.useProvider(session.testSyncProvider, block)

    /** ISSUE-P3-381：取回同一库、同一目标的进程内 ETag 基线；目标变化时视为首次探测。 */
    fun lastRemoteEtagForProbe(targetIdentity: String): String {
        return if (probeBaselineTargetIdentity == targetIdentity) session.lastRemoteEtag else ""
    }

    /** 当前目标首次探测完成后建立进程内基线；锁库后随会话清理。 */
    fun rememberRemoteEtagForProbe(etag: String, targetIdentity: String) {
        session.lastRemoteEtag = etag
        probeBaselineTargetIdentity = targetIdentity
    }

    /**
     * 把最近一次同步周期内引擎发射的 ICacheSupervisor 六事件发布给上层订阅者。
     * 引擎使用 replay SharedFlow 且每次同步创建新实例，事后抽取 replayCache 即可无损回放，
     * 无需为单次同步挂载临时收集协程。
     */
    private fun publishSyncEvents() {
        val engine = session.lastSyncEngine ?: return
        val recent = engine.events.replayCache
        if (recent.isEmpty()) return
        _recentSyncEvents.value = recent
        val settings = preferences.currentSettings()
        recent.forEach { event ->
            _syncEvents.tryEmit(event)
            debugLog.info(SYNC_LOG_TAG, "缓存监督事件: ${describeCacheEvent(event)}")
            preferences.verbose(settings, "缓存监督事件明细: ${describeCacheEventVerbose(event)}")
        }
    }

    /** ISSUE-P3-03 (43f)：详细模式下追加事件类型与远端路径（普通模式只记摘要行）。 */
    private fun describeCacheEventVerbose(event: SyncCacheEvent): String = when (event) {
        is SyncCacheEvent.CouldntSaveToRemote -> "CouldntSaveToRemote path=${event.remotePath} cause=${event.cause?.javaClass?.simpleName}"
        is SyncCacheEvent.CouldntOpenFromRemote -> "CouldntOpenFromRemote path=${event.remotePath} cause=${event.cause?.javaClass?.simpleName}"
        else -> describeCacheEvent(event)
    }

    private fun describeCacheEvent(event: SyncCacheEvent): String = when (event) {
        is SyncCacheEvent.UpdatedCachedFileOnLoad -> "UpdatedCachedFileOnLoad path=${event.remotePath}"
        is SyncCacheEvent.UpdatedRemoteFileOnLoad -> "UpdatedRemoteFileOnLoad path=${event.remotePath}"
        is SyncCacheEvent.OpenedFromLocalDueToConflict -> "OpenedFromLocalDueToConflict path=${event.remotePath}"
        is SyncCacheEvent.LoadedFromRemoteInSync -> "LoadedFromRemoteInSync path=${event.remotePath}"
        is SyncCacheEvent.CouldntSaveToRemote -> "CouldntSaveToRemote path=${event.remotePath}"
        is SyncCacheEvent.CouldntOpenFromRemote -> "CouldntOpenFromRemote path=${event.remotePath}"
    }

    private companion object {
        const val CONTENT_URI_PREFIX = "content://"
        const val UNKNOWN_DEFAULT_REMOTE_FILE_NAME = "__keepasskey_unknown_remote_name__"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
        val AUTH_STATUS_CODES = setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN)
        val TRANSIENT_STATUS_CODES = setOf(HTTP_REQUEST_TIMEOUT, HTTP_TOO_MANY_REQUESTS)
    }
}
