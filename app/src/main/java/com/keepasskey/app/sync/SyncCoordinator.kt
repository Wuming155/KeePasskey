package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCacheEvent
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.provider.SyncProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用级同步编排协调器 (Wave 3-E P0-5 app 侧接线)。
 * 遵循安全与架构铁律：
 * 1. 串联 DatabaseSession 状态机、KDBX4 序列化与三哈希字节级 SyncEngine；
 * 2. 借出凭据 useCredentials 用完在 finally 显式擦除清零；
 * 3. 严格划分协程调度边界：加密与合并在 Dispatchers.Default，网络与文件写盘在 Dispatchers.IO。
 *
 * ISSUE-P3-25 拆分：本类保留**对外公开 API 与串行化外壳**（状态流、测试钩子、
 * [syncNow] / [resolveConflicts] / [testConnection] / [onSessionLocked]），
 * 三职责按依赖倒置下沉为 Hilt 构造注入的协作方：
 * - 同步周期编排 → [SyncCycleRunner]（步骤 2/3/4 各自成方法）；
 * - 冲突决策 → [SyncConflictController]（含待决冲突会话状态）；
 * - Provider 构建 → [SyncProviderResolver]；另拆出 [SyncDatabaseCodec]（KDBX4 编解码）、
 *   [SyncContentChangeDetector]（内容变化判据）、[SyncPreferences]（进阶偏好与详细日志）、
 *   [SyncSessionState]（跨周期共享状态与互斥锁）。
 * 公开可见性与签名保持不变（[SyncOutcome] 仍为本包顶层 sealed class）。
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
    syncCycle: SyncCycleRunner? = null
) : SessionLockObserver {
    private val effectiveStrings: StringsProvider =
        strings ?: StringsProvider { id, args -> context.getString(id, *args) }

    private val session: SyncSessionState = syncSession ?: SyncSessionState()
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
            strings = effectiveStrings
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

    private val _recentSyncEvents = MutableStateFlow<List<SyncCacheEvent>>(emptyList())
    open val recentSyncEvents: StateFlow<List<SyncCacheEvent>> = _recentSyncEvents.asStateFlow()

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
     * ISSUE-P1-07：会话终止（锁定/关闭）时释放本协调器持有的全部数据库树副本。
     * 回调在会话锁内同步触发，此处仅置空引用，不做任何阻塞操作。
     */
    override fun onSessionLocked() {
        session.clear()
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
            testSyncProvider != null || providers.resolveProvider() != null
        } catch (e: Exception) {
            debugLog.warn(SYNC_LOG_TAG, "探测同步配置失败，按未配置处理: ${e.message}")
            false
        }
    }

    /**
     * 设置离线模式（由设置页「使用离线缓存」开关驱动）
     */
    fun setOfflineMode(enabled: Boolean) {
        session.isOfflineMode = enabled
    }

    /**
     * 执行全量即时同步
     */
    suspend fun syncNow(): SyncOutcome {
        debugLog.info(SYNC_LOG_TAG, "手动/自动同步开始")
        val outcome = cycle.runSyncCycle()
        publishSyncEvents()
        debugLog.info(SYNC_LOG_TAG, "同步结束: ${describeOutcome(outcome)}")
        return outcome
    }

    private fun describeOutcome(outcome: SyncOutcome): String = when (outcome) {
        is SyncOutcome.UpToDate -> "UpToDate(与云端一致)"
        is SyncOutcome.UploadedLocal -> "UploadedLocal(本地已上传)"
        is SyncOutcome.MergedAndUploaded -> "MergedAndUploaded(合并后已上传)"
        is SyncOutcome.ConflictNeedsUser -> "ConflictNeedsUser(条目冲突数=${outcome.conflicts.size})"
        is SyncOutcome.Offline -> "Offline(离线/网络不可达)"
        is SyncOutcome.Error -> "Error(${outcome.message})"
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
        fieldResolutions: Map<String, Map<String, ConflictResolutionChoice>> = emptyMap()
    ): SyncOutcome = session.mutex.withLock {
        conflicts.resolveConflicts(resolutions, fieldResolutions)
    }

    /**
     * 测试当前云端同步配置连接连通性
     */
    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val provider = testSyncProvider ?: providers.resolveProvider()
                ?: return@withContext Result.failure(
                    IllegalStateException(effectiveStrings.get(R.string.sync_error_test_no_credentials))
                )
            provider.testConnection()
        } catch (e: Exception) {
            Result.failure(e)
        }
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
}
