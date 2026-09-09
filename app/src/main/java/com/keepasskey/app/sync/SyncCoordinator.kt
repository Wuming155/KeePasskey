package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncCacheEvent
import com.keepasskey.sync.engine.SyncCommitResult
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncOpenResult
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.model.cleanEtag
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.sync.s3.S3SyncProvider
import com.keepasskey.sync.webdav.WebDavSyncProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云同步结果状态模型 (Wave 3-E P0-5)
 */
sealed class SyncOutcome {
    /** 数据库已与云端保持最新同步 */
    data object UpToDate : SyncOutcome()

    /** 本地修改赢并已成功上传云端 */
    data object UploadedLocal : SyncOutcome()

    /** 三方自动合并成功并已同步回写云端与本地 */
    data object MergedAndUploaded : SyncOutcome()

    /** 发生条目同字段冲突，需用户在冲突界面决策 */
    data class ConflictNeedsUser(val conflicts: List<ConflictedEntryPair>) : SyncOutcome()

    /** 离线模式或网络不可达，保留本地安全副本 */
    data object Offline : SyncOutcome()

    /** 同步过程发生错误 */
    data class Error(val message: String) : SyncOutcome()
}

/**
 * 应用级同步编排协调器 (Wave 3-E P0-5 app 侧接线)。
 * 遵循安全与架构铁律：
 * 1. 串联 DatabaseSession 状态机、KDBX4 序列化与三哈希字节级 SyncEngine；
 * 2. 借出凭据 useCredentials 用完在 finally 显式擦除清零；
 * 3. 严格划分协程调度边界：加密与合并在 Dispatchers.Default，网络与文件写盘在 Dispatchers.IO。
 */
@Singleton
open class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseSession: DatabaseSession,
    private val syncCredentialsStore: SyncCredentialsStore,
    private val debugLog: DebugLogBuffer,
    // TASK-21：用户可见错误消息经 StringsProvider 资源解析（P3-23；单测注入假实现）
    private val strings: com.keepasskey.app.ui.model.StringsProvider? = null
) : SessionLockObserver {
    private val effectiveStrings: com.keepasskey.app.ui.model.StringsProvider =
        strings ?: com.keepasskey.app.ui.model.StringsProvider { id, args -> context.getString(id, *args) }
    private val mutex = Mutex()

    init {
        // ISSUE-P1-07：本协调器是单例，lastSyncedDb / pendingLocalDb / pendingRemoteDb 持有
        // 整棵 KdbxDatabase 树（含全部 ProtectedString）。锁库只销毁会话内的树，
        // 若不在此同步释放，这些副本会跨锁定周期长期驻留——内存侧的生命周期同样需要终止点。
        // 磁盘侧缓存（cacheDir/sync 的 .cache/.basecache）由 SyncCacheEvictor 独立负责。
        databaseSession.addLockObserver(this)
    }

    private val _conflictFlow = MutableStateFlow<List<ConflictedEntryPair>>(emptyList())
    open val conflictFlow: StateFlow<List<ConflictedEntryPair>> = _conflictFlow.asStateFlow()

    // ICacheSupervisor 六事件接线：每次同步周期结束后把引擎事件发布给上层订阅者
    private val _syncEvents = MutableSharedFlow<SyncCacheEvent>(extraBufferCapacity = 64)
    open val syncEvents: SharedFlow<SyncCacheEvent> = _syncEvents.asSharedFlow()

    private val _recentSyncEvents = MutableStateFlow<List<SyncCacheEvent>>(emptyList())
    open val recentSyncEvents: StateFlow<List<SyncCacheEvent>> = _recentSyncEvents.asStateFlow()

    /** 离线模式开关：开启后同步引擎直接读本地缓存，不触碰网络 */
    @Volatile
    var isOfflineMode: Boolean = false
        private set

    // 最近一次同步周期使用的引擎实例，用于事后抽取事件 replayCache（每次 syncNow 都会创建新引擎）
    private var lastSyncEngine: SyncEngine? = null

    // 缓存发生冲突时的上下文，供用户确认合并后提交
    private var pendingRemoteEngine: SyncEngine? = null
    private var pendingRemotePath: String? = null
    private var pendingLocalDb: KdbxDatabase? = null
    private var pendingRemoteDb: KdbxDatabase? = null

    // A1 整改：三方合并产物必须保存到用户决策时刻——resolveConflicts 需以
    // mergedRoot（含远端新增条目/分组与非冲突字段级合并）为底版应用用户选择，
    // 若从纯 localDb 重建，合并产物将全部丢失
    private var pendingMergedRoot: KdbxGroup? = null
    private var pendingMergedTombstones: List<DeletedObject> = emptyList()

    // E2 整改：冲突发生时刻的远端 ETag。resolveConflicts 的 If-Match 期望值必须用
    // 该值而非重新探测的当前值，否则用户决策期间远端的再次更新会被静默覆盖
    private var pendingRemoteEtag: String = ""
    private var lastSyncedDb: KdbxDatabase? = null

    // 允许单元测试注入模拟 Provider 与测试路径（禁止生产代码赋值）
    @androidx.annotation.VisibleForTesting
    var testSyncProvider: SyncProvider? = null
    @androidx.annotation.VisibleForTesting
    var testRemotePath: String? = null

    /**
     * ISSUE-P1-07：会话终止（锁定/关闭）时释放本协调器持有的全部数据库树副本。
     * 回调在会话锁内同步触发，此处仅置空引用，不做任何阻塞操作。
     */
    override fun onSessionLocked() {
        lastSyncedDb = null
        lastSyncEngine = null
        clearPendingConflictSession()
        _recentSyncEvents.value = emptyList()
    }

    /**
     * H4-断点11 整改：解锁后自动重同步前判断是否已配置云同步（不触发网络）。
     * 读取凭据存储在未配置存储环境（如 JVM 单测的空壳 Context）下可能失败，
     * 此时按「未配置」处理并记录日志，绝不向上抛出。
     */
    fun isSyncConfigured(): Boolean {
        return try {
            testSyncProvider != null || resolveProvider() != null
        } catch (e: Exception) {
            debugLog.warn(TAG, "探测同步配置失败，按未配置处理: ${e.message}")
            false
        }
    }

    /**
     * 设置离线模式（由设置页「使用离线缓存」开关驱动）
     */
    fun setOfflineMode(enabled: Boolean) {
        isOfflineMode = enabled
    }

    /**
     * 执行全量即时同步
     */
    suspend fun syncNow(): SyncOutcome {
        debugLog.info(TAG, "手动/自动同步开始")
        val outcome = runSyncCycle()
        publishSyncEvents()
        debugLog.info(TAG, "同步结束: ${describeOutcome(outcome)}")
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

    private suspend fun runSyncCycle(): SyncOutcome = mutex.withLock {
        val activeFile = databaseSession.currentFile
            ?: return@withLock SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_no_open_vault_file))

        val currentDb = databaseSession.databaseFlow.value
            ?: return@withLock SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_vault_not_unlocked))

        // Wave 14 全站强制 HTTPS：遗留的 http:// 端点在 Provider 构造期被拒，
        // 此处将类型化错误上浮为用户可理解的同步失败反馈
        val provider = try {
            testSyncProvider ?: resolveProvider()
        } catch (e: SyncException.InvalidEndpointError) {
            return@withLock SyncOutcome.Error(e.message ?: effectiveStrings.get(R.string.sync_error_invalid_endpoint))
        } ?: return@withLock SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_no_sync_credentials))

        // ISSUE-P1-06 整改：同步周期结束后显式擦除 S3 凭据 CharArray，
        // 杜绝 Provider 实例被 GC 前凭据长期驻留堆内存（try-finally 保证任何退出路径均擦除）
        try {
        val remotePath = testRemotePath ?: resolveRemotePath(activeFile.name)

        // ISSUE-P1-07：目录名与 SyncCacheEvictor 共用同一常量，杜绝两处字面量漂移
        val syncDir = File(context.cacheDir, SyncCache.CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }
        val syncCache = SyncCache(syncDir)
        val syncEngine = SyncEngine(provider, syncCache)
        // 离线开关联动：设置页开关传导至引擎决策树
        syncEngine.isOffline = isOfflineMode
        lastSyncEngine = syncEngine

        val isCached = syncCache.isCached(remotePath)
        val cachedSnapshotBytes = if (isCached) syncCache.readCache(remotePath) else null
        // A2 整改：三方合并的 base 必须取"最后确认与远端一致"的独立内容快照（basecache）。
        // 本地缓存会被工作副本反复覆盖，绝不能再兼任 base 内容来源——
        // 否则冲突会话中断后 base 会被本地修改版污染，后续合并退化为远端全胜
        val baseSnapshotBytes = syncCache.readBaseContent(remotePath) ?: cachedSnapshotBytes
        val hasLocalContentChanged = resolveLocalContentChanged(currentDb, cachedSnapshotBytes)

        // 1. 获取本地数据库字节：若无内容变更且已缓存，复用缓存规避 KDBX4 随机 IV 导致的不必要哈希漂移；否则序列化并写缓存
        val localBytes = if (!isCached || hasLocalContentChanged) {
            val bytes = serializeLocalDatabase(currentDb)
                ?: return@withLock SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_local_serialize_failed))
            if (isCached) {
                syncCache.writeCache(remotePath, bytes)
            }
            bytes
        } else {
            cachedSnapshotBytes ?: serializeLocalDatabase(currentDb)!!
        }

        val isDirty = databaseSession.state.value == DatabaseSession.SessionState.DIRTY

        // 2. 首次同步且尚未缓存：若远端尚未创建该文件，直接上传本地库建立基线
        if (!isCached) {
            val metaResult = provider.getMetadata(remotePath)
            if (metaResult.isFailure) {
                val ex = metaResult.exceptionOrNull()
                if (ex is com.keepasskey.sync.model.SyncException.FileNotFound) {
                    val uploadResult = syncEngine.commitLocal(remotePath, localBytes)
                    return@withLock when (uploadResult) {
                        is SyncCommitResult.Uploaded -> {
                            lastSyncedDb = databaseSession.databaseFlow.value
                            SyncOutcome.UploadedLocal
                        }
                        else -> SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_first_upload_failed))
                    }
                } else {
                    return@withLock SyncOutcome.Offline
                }
            }
        }

        // 3. 若本地为未落盘的修改态且本地已存在历史缓存基线，尝试快速提交
        if (isDirty && syncCache.isCached(remotePath)) {
            when (val commitResult = syncEngine.commitLocal(remotePath, localBytes)) {
                is SyncCommitResult.Uploaded -> {
                    // H3 整改：缓存已上传云端但本地正式文件保存失败时如实报错，不再静默
                    val saveResult = databaseSession.save()
                    if (saveResult is KdbxResult.Failure) {
                        return@withLock SyncOutcome.Error(
                            effectiveStrings.get(R.string.sync_error_remote_updated_local_save_failed, saveResult.message)
                        )
                    }
                    lastSyncedDb = databaseSession.databaseFlow.value
                    return@withLock SyncOutcome.UploadedLocal
                }
                is SyncCommitResult.ConflictNeedsMerge -> {
                    // R3 整改：本地未同步修改必须先落盘正式库文件——冲突会话可能在
                    // 用户退出/进程被杀时中断，仅存于缓存与内存的本地修改会随重启丢失
                    databaseSession.save()
                    return@withLock handleConflictMerge(
                        syncEngine = syncEngine,
                        syncCache = syncCache,
                        remotePath = remotePath,
                        localBytes = localBytes,
                        remoteBytes = commitResult.remoteBytes,
                        baseSnapshotBytes = baseSnapshotBytes,
                        remoteEtag = commitResult.remoteEtag
                    )
                }
                is SyncCommitResult.RemoteUnreachable -> {
                    return@withLock SyncOutcome.Offline
                }
            }
        }

        // 4. 执行 openRemote 同步状态机决策
        return@withLock try {
            when (val openResult = syncEngine.openRemote(remotePath)) {
                is SyncOpenResult.RemoteSynced -> {
                    val isIdentical = openResult.remoteBytes.contentEquals(localBytes)
                    if (isIdentical) {
                        lastSyncedDb = databaseSession.databaseFlow.value
                        SyncOutcome.UpToDate
                    } else if (isDirty || hasLocalContentChanged) {
                        // F1 修复：本地存在未同步修改（缓存被回收导致步骤 3 快速提交被跳过时
                        // 尤其危险——Android 官方文档明确 cacheDir 会在存储不足时被系统自动
                        // 回收，读取前必须检查存在性）。判据用 isDirty || hasLocalContentChanged：
                        // 前者覆盖「内存修改未落盘」，后者覆盖「已落盘但尚未同步」（更常见，
                        // isDirty 在 save() 后即复位，绝不能只看它）。严禁以远端整体覆盖会话，
                        // 否则本地未上传修改将不可恢复地丢失：先按 R3 落盘本地会话，
                        // 再转三方合并（base 缺失时按 F2 修复退化为双方并集合并）
                        val preSave = databaseSession.save()
                        if (preSave is KdbxResult.Failure) {
                            return@withLock SyncOutcome.Error(
                                effectiveStrings.get(R.string.sync_error_conflict_presave_failed, preSave.message)
                            )
                        }
                        handleConflictMerge(
                            syncEngine = syncEngine,
                            syncCache = syncCache,
                            remotePath = remotePath,
                            localBytes = localBytes,
                            remoteBytes = openResult.remoteBytes,
                            baseSnapshotBytes = baseSnapshotBytes,
                            remoteEtag = openResult.etag
                        )
                    } else {
                        val applied = loadAndApplyRemoteBytes(openResult.remoteBytes)
                        if (!applied) return@withLock SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_load_remote_failed))
                        lastSyncedDb = databaseSession.databaseFlow.value
                        SyncOutcome.UpToDate
                    }
                }
                is SyncOpenResult.LocalWinAutoUploaded -> {
                    lastSyncedDb = databaseSession.databaseFlow.value
                    SyncOutcome.UploadedLocal
                }
                is SyncOpenResult.RemoteLostRestored -> {
                    lastSyncedDb = databaseSession.databaseFlow.value
                    SyncOutcome.UploadedLocal
                }
                is SyncOpenResult.CacheHitOffline -> {
                    SyncOutcome.Offline
                }
                is SyncOpenResult.RemoteUnreachableUsingCache -> {
                    SyncOutcome.Offline
                }
                is SyncOpenResult.ConflictDetected -> {
                    // R3 整改：同 commitLocal 冲突路径，先落盘本地会话再进入合并
                    val preSave = databaseSession.save()
                    if (preSave is KdbxResult.Failure) {
                        return@withLock SyncOutcome.Error(
                            effectiveStrings.get(R.string.sync_error_conflict_presave_failed, preSave.message)
                        )
                    }
                    handleConflictMerge(
                        syncEngine = syncEngine,
                        syncCache = syncCache,
                        remotePath = remotePath,
                        localBytes = openResult.localBytes,
                        remoteBytes = openResult.remoteBytes,
                        baseSnapshotBytes = baseSnapshotBytes,
                        remoteEtag = openResult.remoteEtag
                    )
                }
            }
        } catch (e: com.keepasskey.sync.model.SyncException.NetworkError) {
            SyncOutcome.Offline
        } catch (e: Exception) {
            SyncOutcome.Error(e.message ?: effectiveStrings.get(R.string.sync_error_unknown))
        }
        } finally {
            // ISSUE-P1-06：同步周期结束（无论成功/失败/异常），显式擦除 S3 凭据 CharArray。
            // WebDAV 侧密码已在 resolveProvider() 构造完成后即时擦除（passwordChars 借用语义），
            // S3 侧因 Provider 需在整个同步周期内多次签名复用，故延迟至此处统一擦除。
            (provider as? S3SyncProvider)?.clearCredentials()
        }
    }

    /**
     * 解决冲突并执行最终提交回写。
     * [resolutions] 为条目级决策（默认兜底）；[fieldResolutions]（TASK-30 整改）为字段级
     * 决策——键为条目 ID，值为「字段键 → 选择」映射，非空时该条目按字段粒度合并
     * （本地为底版、远端仅覆写用户钦点字段），取代整条目二选一的塌缩行为。
     */
    open suspend fun resolveConflicts(
        resolutions: Map<String, ConflictResolutionChoice>,
        fieldResolutions: Map<String, Map<String, ConflictResolutionChoice>> = emptyMap()
    ): SyncOutcome = mutex.withLock {
        val engine = pendingRemoteEngine ?: return@withLock SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_no_pending_conflict))
        val path = pendingRemotePath ?: return@withLock SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_conflict_path_invalid))
        val localDb = pendingLocalDb ?: return@withLock SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_local_snapshot_lost))
        val remoteDb = pendingRemoteDb ?: return@withLock SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_remote_snapshot_lost))

        return@withLock withContext(Dispatchers.Default) {
            val conflicts = _conflictFlow.value
            // A1 整改：以三方合并产物为底版应用用户决策。mergedRoot 含远端新增
            // 条目/分组、非冲突字段级合并与标签并集；若从纯 localDb 重建，
            // 这些合并成果将随冲突决策一并丢失（静默数据丢失）
            var updatedRoot = pendingMergedRoot ?: localDb.rootGroup

            for (pair in conflicts) {
                val choice = resolutions[pair.entryId] ?: ConflictResolutionChoice.KEEP_LOCAL
                val fieldChoice = fieldResolutions[pair.entryId]
                // TASK-30：字段级决策优先——本地为底版、远端仅覆写用户钦点的字段；
                // 无字段级决策时回退整条目二选一
                val resolvedEntries = if (fieldChoice != null) {
                    listOf(KdbxMerger.resolveConflictByFields(pair, fieldChoice))
                } else {
                    KdbxMerger.resolveConflict(pair, choice)
                }
                // 替换当前分组树中的条目
                for (resolved in resolvedEntries) {
                    updatedRoot = applyResolvedEntryToGroup(updatedRoot, resolved)
                }
            }

            val mergedDb = localDb.copy(
                rootGroup = updatedRoot,
                deletedObjects = pendingMergedTombstones
            )
            val mergedBytes = serializeLocalDatabase(mergedDb)
                ?: return@withContext SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_conflict_serialize_failed))

            // E2 整改：If-Match 期望值取冲突发生时刻的远端 ETag——用户决策期间
            // 远端若被再次修改，上传将 412 失败并暴露新冲突，而不是静默覆盖他端更新
            val uploadResult = engine.markResolvedAndUpload(
                path,
                mergedBytes,
                expectedEtag = pendingRemoteEtag.ifEmpty { null }
            )
            if (uploadResult.isSuccess) {
                databaseSession.updateDatabaseMeta { mergedDb }
                // H3 整改：云端已接收合并版本，本地落盘失败必须如实暴露
                val saveResult = databaseSession.save()
                clearPendingConflictSession()
                if (saveResult is KdbxResult.Failure) {
                    SyncOutcome.Error(
                        effectiveStrings.get(R.string.sync_error_merged_upload_local_save_failed, saveResult.message)
                    )
                } else {
                    SyncOutcome.MergedAndUploaded
                }
            } else {
                val ex = uploadResult.exceptionOrNull()
                if (ex is com.keepasskey.sync.model.SyncException.ConflictError) {
                    // 远端在决策期间再次更新：放弃本次解决会话（本地会话未被改写，
                    // 未同步修改仍保留在缓存中），用户重新 syncNow 将以最新远端重新检测合并
                    clearPendingConflictSession()
                    SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_remote_changed_during_resolve))
                } else {
                    SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_upload_resolved_failed, ex?.message))
                }
            }
        }
    }

    private fun clearPendingConflictSession() {
        _conflictFlow.value = emptyList()
        pendingRemoteEngine = null
        pendingRemotePath = null
        pendingLocalDb = null
        pendingRemoteDb = null
        pendingMergedRoot = null
        pendingMergedTombstones = emptyList()
        pendingRemoteEtag = ""
    }

    /**
     * 测试当前云端同步配置连接连通性
     */
    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val provider = testSyncProvider ?: resolveProvider()
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
        val engine = lastSyncEngine ?: return
        val recent = engine.events.replayCache
        if (recent.isEmpty()) return
        _recentSyncEvents.value = recent
        recent.forEach { event ->
            _syncEvents.tryEmit(event)
            debugLog.info(TAG, "缓存监督事件: ${describeCacheEvent(event)}")
        }
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
        const val TAG = "SyncCoordinator"

    }

    private suspend fun handleConflictMerge(
        syncEngine: SyncEngine,
        syncCache: SyncCache,
        remotePath: String,
        localBytes: ByteArray,
        remoteBytes: ByteArray,
        baseSnapshotBytes: ByteArray? = null,
        remoteEtag: String = ""
    ): SyncOutcome = withContext(Dispatchers.Default) {
        val localDb = parseKdbxBytes(localBytes)
            ?: return@withContext SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_decrypt_local_conflict_failed))
        val remoteDb = parseKdbxBytes(remoteBytes)
            ?: return@withContext SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_decrypt_remote_conflict_failed))

        // F2 修复：base 快照必须通过三重可信检验——存在、可解析、且内容与本地字节不同
        // （本地工作副本污染判定：KDBX4 随机 IV 使同一内容的两次序列化字节必然不同，
        // 字节级 contentEquals 命中相同即证明 base 已被本地内容顶替）。
        // 不可信时严禁以本地充当 base——那会把「本地新建」误判为「远端已删除且本地未修改」
        // 而确认删除且不留墓碑，同时远端修改全胜；改为以空库充当 base，
        // 三方合并退化为双方并集语义：单侧新建保留、同 UUID 条目字段级合并、
        // 同字段分叉进入冲突清单交用户决策（宁多冲突不静默丢数据）。
        val parsedBase = baseSnapshotBytes?.let { parseKdbxBytes(it) }
        val trustedBase = parsedBase?.takeIf { !baseSnapshotBytes.contentEquals(localBytes) }

        val baseLite = trustedBase
            ?.let { KdbxDatabaseLite(it.rootGroup, it.deletedObjects) }
            ?: KdbxDatabaseLite(KdbxGroup(name = ""), emptyList())
        val localLite = KdbxDatabaseLite(localDb.rootGroup, localDb.deletedObjects)
        val remoteLite = KdbxDatabaseLite(remoteDb.rootGroup, remoteDb.deletedObjects)

        val mergeResult = KdbxMerger.mergeDatabases(baseLite, localLite, remoteLite)

        if (mergeResult.conflicts.isNotEmpty()) {
            _conflictFlow.value = mergeResult.conflicts
            pendingRemoteEngine = syncEngine
            pendingRemotePath = remotePath
            pendingLocalDb = localDb
            pendingRemoteDb = remoteDb
            pendingMergedRoot = mergeResult.mergedRoot
            pendingMergedTombstones = mergeResult.mergedDeletedObjects
            pendingRemoteEtag = cleanEtag(remoteEtag)
            SyncOutcome.ConflictNeedsUser(mergeResult.conflicts)
        } else {
            // 无条目级冲突，自动合并
            val mergedDb = localDb.copy(
                rootGroup = mergeResult.mergedRoot,
                deletedObjects = mergeResult.mergedDeletedObjects
            )
            val mergedBytes = serializeLocalDatabase(mergedDb)
                ?: return@withContext SyncOutcome.Error(effectiveStrings.get(R.string.sync_error_serialize_merged_failed))

            val uploadResult = syncEngine.markResolvedAndUpload(remotePath, mergedBytes)
            if (uploadResult.isSuccess) {
                databaseSession.updateDatabaseMeta { mergedDb }
                val saveResult = databaseSession.save()
                if (saveResult is KdbxResult.Failure) {
                    SyncOutcome.Error(
                        effectiveStrings.get(R.string.sync_error_merged_upload_local_save_failed, saveResult.message)
                    )
                } else {
                    SyncOutcome.MergedAndUploaded
                }
            } else {
                SyncOutcome.Error(
                    effectiveStrings.get(R.string.sync_error_upload_merged_failed, uploadResult.exceptionOrNull()?.message)
                )
            }
        }
    }

    private suspend fun serializeLocalDatabase(db: KdbxDatabase): ByteArray? = withContext(Dispatchers.Default) {
        databaseSession.useCredentials { pwd, key ->
            val pwdClone = pwd?.clone()
            val keyClone = key?.clone()
            try {
                val baos = ByteArrayOutputStream()
                // P1-10：pwdClone 为 null 表示仅密钥文件会话（无主密码分量），直接透传
                KdbxFile.save(baos, db, pwdClone, keyClone)
                baos.toByteArray()
            } catch (e: Exception) {
                // P3-31 整改：序列化失败不再静默吞掉，至少落调试日志保留异常细节
                debugLog.warn(TAG, "本地数据库序列化失败（合并上传中断）: ${e.message}")
                null
            } finally {
                pwdClone?.let { Arrays.fill(it, '0') }
                keyClone?.let { Arrays.fill(it, 0.toByte()) }
            }
        }
    }

    private suspend fun parseKdbxBytes(bytes: ByteArray): KdbxDatabase? = withContext(Dispatchers.Default) {
        databaseSession.useCredentials { pwd, key ->
            val pwdClone = pwd?.clone()
            val keyClone = key?.clone()
            try {
                // P1-10：pwdClone 为 null 表示仅密钥文件会话（无主密码分量），直接透传
                KdbxFile.load(ByteArrayInputStream(bytes), pwdClone, keyClone)
            } catch (e: Exception) {
                // P3-31 整改：解析失败不再静默吞掉，至少落调试日志保留异常细节
                debugLog.warn(TAG, "远端数据库字节解析失败: ${e.message}")
                null
            } finally {
                pwdClone?.let { Arrays.fill(it, '0') }
                keyClone?.let { Arrays.fill(it, 0.toByte()) }
            }
        }
    }

    private suspend fun loadAndApplyRemoteBytes(remoteBytes: ByteArray): Boolean {
        val remoteDb = parseKdbxBytes(remoteBytes) ?: return false
        databaseSession.updateDatabaseMeta { remoteDb }
        return databaseSession.save() is KdbxResult.Success
    }

    private fun resolveProvider(): SyncProvider? {
        return when (syncCredentialsStore.loadProvider()) {
            CloudSyncProvider.WEBDAV -> {
                val cfg = syncCredentialsStore.loadWebDavConfig() ?: return null
                if (cfg.url.isBlank()) return null
                try {
                    WebDavSyncProvider(
                        serverUrl = cfg.url,
                        username = cfg.username,
                        // Wave 15：cfg.password 已是 CharArray（借用语义），构造完成后由本方统一擦除
                        passwordChars = cfg.password,
                        // Wave 14 传输安全：TLS-only + 显式超时；证书固定已整体移除，
                        // 证书验证完全依赖系统默认 CA 链（客户端由 sync 模块工厂构建）
                        networkOptions = SyncNetworkOptions()
                    )
                } finally {
                    cfg.password.fill('0')
                }
            }
            CloudSyncProvider.S3_COMPATIBLE -> {
                val cfg = syncCredentialsStore.loadS3Config() ?: return null
                if (cfg.endpoint.isBlank() || cfg.bucket.isBlank()) return null
                // ISSUE-P1-06 整改：凭据以 CharArray clone 传入 Provider（借用语义转移），
                // Provider 持有期间可多次签名复用，同步周期结束后由 runSyncCycle 调用
                // clearCredentials() 显式擦除——杜绝旧版 String(cfg.accessKey) 物化后
                // 与 Provider 同生命周期、结构性不可擦除的缺陷。
                val accessKeyClone = cfg.accessKey.clone()
                val secretKeyClone = cfg.secretKey.clone()
                try {
                    S3SyncProvider(
                        endpoint = cfg.endpoint,
                        bucketName = cfg.bucket,
                        region = cfg.region,
                        accessKeyId = accessKeyClone,
                        secretAccessKey = secretKeyClone,
                        usePathStyle = cfg.usePathStyle,
                        networkOptions = SyncNetworkOptions(),
                        // TASK-45（P2-14）：恢复上次持久化的服务端时钟偏移，启动即补偿；
                        // 同步期间每次响应携带 Date 头即经回调刷新落盘（跨进程保留）
                        initialClockOffsetMillis = syncCredentialsStore.loadS3ClockOffsetMillis(),
                        clockOffsetUpdater = { offset ->
                            try {
                                syncCredentialsStore.saveS3ClockOffsetMillis(offset)
                            } catch (e: Exception) {
                                // 持久化失败仅丢失跨进程记忆：本会话内存偏移仍即时生效，
                                // 下次同步按 fail-closed 重新学习，不阻断本次同步
                                debugLog.warn(TAG, "S3 时钟偏移持久化失败: ${e.message}")
                            }
                        }
                    )
                } catch (e: Exception) {
                    // 构造失败（如端点校验拒绝）时擦除已 clone 的凭据副本，防泄漏
                    accessKeyClone.fill('0')
                    secretKeyClone.fill('0')
                    throw e
                } finally {
                    // 原始 cfg 数组由 loadS3Config 借用语义管理，此处擦除防止残留
                    cfg.accessKey.fill('0')
                    cfg.secretKey.fill('0')
                }
            }
        }
    }

    private fun resolveRemotePath(defaultFileName: String): String {
        return when (syncCredentialsStore.loadProvider()) {
            CloudSyncProvider.WEBDAV -> {
                val cfg = syncCredentialsStore.loadWebDavConfig()
                val path = cfg?.remotePath?.trim()
                if (!path.isNullOrBlank()) {
                    if (path.startsWith("/")) path else "/$path"
                } else {
                    "/$defaultFileName"
                }
            }
            CloudSyncProvider.S3_COMPATIBLE -> {
                val cfg = syncCredentialsStore.loadS3Config()
                cfg?.objectKey?.trim()?.ifBlank { defaultFileName } ?: defaultFileName
            }
        }
    }

    private fun applyResolvedEntryToGroup(
        group: com.keepasskey.core.model.KdbxGroup,
        entry: KdbxEntry
    ): com.keepasskey.core.model.KdbxGroup {
        val targetParentId = entry.parentGroupId ?: group.id
        if (group.id == targetParentId) {
            val idx = group.entries.indexOfFirst { it.id == entry.id }
            val newEntries = group.entries.toMutableList()
            if (idx >= 0) {
                newEntries[idx] = entry
            } else {
                newEntries.add(entry)
            }
            return group.copy(entries = newEntries)
        }
        val newSubs = group.subgroups.map { applyResolvedEntryToGroup(it, entry) }
        return group.copy(subgroups = newSubs)
    }

    /**
     * 判定本地会话库是否存在未同步的内容变更（F5 修复）。
     *
     * - 进程内存基线（lastSyncedDb）可用时按全字段内容比较；
     * - 冷启动后内存基线缺失时，绝不能直接判定「有变化」并重序列化覆盖缓存——
     *   KDBX4 随机 IV 使重序列化字节必然漂移，刷新 version 后引擎将把「无修改」
     *   误判为「本地赢」，触发无意义重传并前移远端 ETag，成为多设备协同的噪音源。
     *   正确做法：把缓存快照解析为数据库后做内容级比较；
     * - 缓存解析失败（损坏）按「有变化」保守处理：以会话库重建缓存——
     *   会话库即本地真相，重建内容不会偏离用户数据。
     */
    private suspend fun resolveLocalContentChanged(
        currentDb: KdbxDatabase,
        cachedSnapshotBytes: ByteArray?
    ): Boolean {
        val reference = lastSyncedDb
        if (reference != null) {
            return hasDatabaseContentChanged(currentDb, reference)
        }
        if (cachedSnapshotBytes != null) {
            val cachedDb = parseKdbxBytes(cachedSnapshotBytes) ?: return true
            return hasDatabaseContentChanged(currentDb, cachedDb)
        }
        return true
    }

    /**
     * 全字段递归内容比较（C1 整改）。
     * 原实现仅比较 title/userName/password/url/notes 五项：仅修改 tags、自定义字段、
     * 附件、图标、分组结构等内容的编辑会被误判为"无变化"，导致复用过期缓存并把
     * 旧字节上传到云端（本地编辑与云端静默分叉）。
     * ProtectedString.equals 为字节数组内容比较，整字段比较不会物化明文密码。
     */
    private fun hasDatabaseContentChanged(current: KdbxDatabase, reference: KdbxDatabase?): Boolean {
        if (reference == null) return true
        if (current.deletedObjects != reference.deletedObjects) return true
        return isGroupContentChanged(current.rootGroup, reference.rootGroup)
    }

    private fun isGroupContentChanged(a: KdbxGroup, b: KdbxGroup): Boolean {
        if (a.id != b.id || a.name != b.name || a.notes != b.notes ||
            a.iconId != b.iconId || a.customIconId != b.customIconId ||
            a.parentGroupId != b.parentGroupId ||
            // P1-8 配套：分组 tags / customData 已为一等持久化字段，纳入变化检测，
            // 防止仅修改分组标签的编辑被误判为「无变化」而把旧字节上传云端
            a.tags != b.tags || a.customData != b.customData
        ) {
            return true
        }
        if (a.entries.size != b.entries.size) return true
        val bEntries = b.entries.associateBy { it.id }
        for (entry in a.entries) {
            val ref = bEntries[entry.id] ?: return true
            if (isEntryContentChanged(entry, ref)) return true
        }
        if (a.subgroups.size != b.subgroups.size) return true
        val bSubgroups = b.subgroups.associateBy { it.id }
        for (sub in a.subgroups) {
            val ref = bSubgroups[sub.id] ?: return true
            if (isGroupContentChanged(sub, ref)) return true
        }
        return false
    }

    private fun isEntryContentChanged(a: KdbxEntry, b: KdbxEntry): Boolean {
        return a.fields != b.fields ||
                a.customFields != b.customFields ||
                a.tags != b.tags ||
                a.attachments != b.attachments ||
                a.iconId != b.iconId ||
                a.customIconId != b.customIconId ||
                a.overrideUrl != b.overrideUrl ||
                a.qualityCheck != b.qualityCheck ||
                a.parentGroupId != b.parentGroupId ||
                a.previousParentGroup != b.previousParentGroup ||
                a.customData != b.customData ||
                a.autoType != b.autoType ||
                a.backgroundColor != b.backgroundColor ||
                a.foregroundColor != b.foregroundColor ||
                a.history.size != b.history.size
    }
}
