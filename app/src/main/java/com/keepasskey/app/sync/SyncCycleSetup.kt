package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncRollbackGuard
import com.keepasskey.sync.merge.SyncConflictStrategy
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import java.io.File

/**
 * 同步周期的**前置装配**（ISSUE-P3-305 自 [SyncCycleRunner] 同包下沉，函数体逐行未改）。
 *
 * 形态沿用同包既有先例 `SyncCycleRemoteOutcomes.kt`：门面类的装配段移为**同包 `internal`
 * 扩展函数**，使 `SyncCycleRunner` 只保留「取锁 + 装配调用 + 决策分派」的编排面。
 * 可见性代价：`context` / `providerResolver` / `preferences` / `vaultBindingStore` /
 * `syncIntegrityMac` / `rollbackStateDir` / `changes` 七个成员由 `private` 放宽为 `internal`
 * （仅同模块可见，公开 API 与行为零变化）。
 */

/**
 * [setupCycleContext] 的产出：本次同步周期的上下文（引擎、缓存、路径与内容快照）。
 */
internal data class SyncCycleContext(
    val provider: SyncProvider,
    val syncEngine: SyncEngine,
    val syncCache: SyncCache,
    val remotePath: String,
    val isCached: Boolean,
    val settings: ExtendedSettings,
    val conflictStrategy: SyncConflictStrategy,
    val isDirty: Boolean,
    val localBytes: ByteArray,
    val baseSnapshotBytes: ByteArray?,
    val hasLocalContentChanged: Boolean,
    val currentDb: KdbxDatabase
)

/**
 * 周期前置装配（原 [SyncCycleRunner.runSyncCycle] 主体前段逐行搬运）：Provider 解析、引擎与缓存构造、
 * 偏好快照、基线内容读取与本地字节获取。
 *
 * @return [SyncOutcome] 表示前置步骤即有结论（错误早退）；null 表示装配完成，见 [outcome]。
 */
internal data class CycleSetup(val outcome: SyncOutcome?, val context: SyncCycleContext? = null)

/**
 * 装配本次同步周期的全部前置条件。
 *
 * ISSUE-P3-305：原 105 行的单体装配按「引擎面 / 内容面」拆为两段——本函数负责
 * Provider 解析、库身份绑定闸与引擎构造，内容快照交由 [buildCycleContext]。
 * 两段之间的调用点即原内联体的物理分界，早退语义与求值顺序逐行未变。
 */
internal suspend fun SyncCycleRunner.setupCycleContext(
    activeFile: File,
    currentDb: KdbxDatabase
): CycleSetup {
    val provider = try {
        session.testSyncProvider ?: providerResolver.resolveProvider()
    } catch (e: SyncException.InvalidEndpointError) {
        return CycleSetup(SyncOutcome.Error(e.message ?: strings.get(R.string.sync_error_invalid_endpoint)))
    } ?: return CycleSetup(SyncOutcome.Error(strings.get(R.string.sync_error_no_sync_credentials)))

    val remotePath = session.testRemotePath ?: providerResolver.resolveRemotePath(activeFile.name)

    // ISSUE-P2-291 AC①：库身份（根分组 UUID，建库随机生成、跨保存稳定）参与
    // 缓存 / 基线 / 防回滚的键——同一 remotePath 被不同库共用时，各库的同步状态
    // 物理隔离（「换库即视为新配置」）。绑定登记缺失（手工装配路径）时沿用旧键。
    val vaultScope = vaultBindingStore?.let { currentDb.rootGroup.id.toHexString() }
    val cacheScope = vaultScope.orEmpty()

    // ISSUE-P3-03 (43a)：本次同步周期使用的偏好快照与冲突策略（周期内恒定，避免中途偏好漂移）
    val settings = preferences.currentSettings()
    val conflictStrategy = settings.conflictResolution.toSyncStrategy()

    // ISSUE-P1-07：目录名与 SyncCacheEvictor 共用同一常量，杜绝两处字面量漂移
    val syncDir = File(context.cacheDir, SyncCache.CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }
    val syncCache = if (vaultScope == null) {
        syncCacheFactory?.invoke(syncDir) ?: SyncCache(syncDir)
    } else {
        SyncCache(syncDir, cacheScope)
    }
    // F-23 整改：防回滚状态**不得**与可丢弃缓存同目录——此前它落在 cacheDir/sync，
    // 而 SyncCache.clear() 把它列入删除清单且由锁库 / 凭据清空触发，导致「用户锁定一次
    // 即可被云端重放旧库」。现注入 filesDir 下的持久目录（跨锁定保留），
    // 状态仅含 SHA-256 摘要 + Keystore HMAC（无明文）。
    // 注入缺失（手动装配路径）时按同一落点惰性兜底，保证两种装配方式落点一致。
    val rollbackGuard = SyncRollbackGuard(
        rollbackStateDir ?: File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME),
        syncIntegrityMac,
        cacheScope
    )

    // ISSUE-P2-291 AC②：库身份绑定闸——在任何网络写（含 getMetadata）之前裁决。
    // 未登记：当前库即本配置的创建绑定者（登记并把旧无命名空间键迁移到库身份键下，
    // 单库老用户零感知）；登记一致：放行；登记为**另一库**：中止，须用户显式确认整库覆盖。
    if (vaultBindingStore != null && vaultScope != null) {
        when (val bound = vaultBindingStore.loadBinding(remotePath)) {
            null -> {
                vaultBindingStore.saveBinding(remotePath, vaultScope)
                syncCache.adoptLegacyKeysIfPresent(remotePath)
                rollbackGuard.adoptLegacyKeyIfPresent(remotePath)
            }
            vaultScope -> Unit
            else -> {
                preferences.verbose(settings, "库身份绑定拦截：remotePath 归属另一库（bound=${bound.take(8)}…），中止同步")
                return CycleSetup(SyncOutcome.VaultBindingMismatch(remotePath))
            }
        }
    }
    val syncEngine = SyncEngine(provider, syncCache, rollbackGuard)
    // 离线开关联动：设置页开关传导至引擎决策树
    syncEngine.isOffline = session.isOfflineMode
    // ISSUE-P3-03 (43a)：关闭「同步前检查远程变更」= 上传前不比对方版本，本地修改直接覆盖远端
    syncEngine.overwriteRemoteWithoutPrecondition = !settings.checkRemoteChangesBeforeSave
    session.lastSyncEngine = syncEngine

    return buildCycleContext(
        provider = provider,
        syncEngine = syncEngine,
        syncCache = syncCache,
        remotePath = remotePath,
        settings = settings,
        conflictStrategy = conflictStrategy,
        currentDb = currentDb
    )
}

/**
 * 内容快照段（ISSUE-P3-305：自 [setupCycleContext] 逐行搬出）：缓存命中判定、基线内容读取、
 * 本地字节获取与 [SyncCycleContext] 组装。序列化失败的早退语义与拆分前一致。
 */
private suspend fun SyncCycleRunner.buildCycleContext(
    provider: SyncProvider,
    syncEngine: SyncEngine,
    syncCache: SyncCache,
    remotePath: String,
    settings: ExtendedSettings,
    conflictStrategy: SyncConflictStrategy,
    currentDb: KdbxDatabase
): CycleSetup {
    val isCached = syncCache.isCached(remotePath)
    val cachedSnapshotBytes = if (isCached) syncCache.readCache(remotePath) else null
    preferences.verbose(
        settings,
        "同步周期开始: cached=$isCached, dirty=${databaseSession.state.value}, " +
            "远端比对=${settings.checkRemoteChangesBeforeSave}, 冲突策略=$conflictStrategy, " +
            "分块上传=${settings.webdavChunkedUpload}(${settings.webdavChunkSizeMb}MB)"
    )
    // A2 整改：三方合并的 base 必须取"最后确认与远端一致"的独立内容快照（basecache）。
    // 本地缓存会被工作副本反复覆盖，绝不能再兼任 base 内容来源——
    // 否则冲突会话中断后 base 会被本地修改版污染，后续合并退化为远端全胜
    // ISSUE-P2-309：缺失即以 null 交给 `resolveTrustedBase`（退化为空库并集，宁多冲突不丢数据）。
    // 原 `?: cachedSnapshotBytes` 兜底正是本注释明令禁止的那条路，且 `resolveTrustedBase` 的
    // 字节级污染判据救不了它——KDBX4 每次保存重生成 masterSeed / IV / KDF salt，
    // 同一内容的两次序列化字节必然不同，`contentEquals` 无从命中。
    val baseSnapshotBytes = syncCache.readBaseContent(remotePath)
    val hasLocalContentChanged = changes.resolveLocalContentChanged(currentDb, cachedSnapshotBytes)

    // 1. 获取本地数据库字节：若无内容变更且已缓存，复用缓存规避 KDBX4 随机 IV 导致的不必要哈希漂移；否则序列化并写缓存
    val localBytes = if (!isCached || hasLocalContentChanged) {
        val bytes = codec.serializeLocalDatabase(currentDb)
            ?: return CycleSetup(SyncOutcome.Error(strings.get(R.string.sync_error_local_serialize_failed)))
        if (isCached) {
            syncCache.writeCache(remotePath, bytes)
        }
        bytes
    } else {
        cachedSnapshotBytes ?: codec.serializeLocalDatabase(currentDb)!!
    }

    return CycleSetup(
        outcome = null,
        context = SyncCycleContext(
            provider = provider,
            syncEngine = syncEngine,
            syncCache = syncCache,
            remotePath = remotePath,
            isCached = isCached,
            settings = settings,
            conflictStrategy = conflictStrategy,
            isDirty = databaseSession.state.value == DatabaseSession.SessionState.DIRTY,
            localBytes = localBytes,
            baseSnapshotBytes = baseSnapshotBytes,
            hasLocalContentChanged = hasLocalContentChanged,
            currentDb = currentDb
        )
    )
}
