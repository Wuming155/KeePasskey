package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.sync.applyResolvedEntriesToGroup
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import com.keepasskey.sync.merge.MergeResult

/** 漂移处置日志标签（`ISSUE-P3-550`：异常细节只进日志，UI 侧只留错误码映射） */
internal const val DRIFT_LOG_TAG = "VaultFileDriftResolve"

/** `ISSUE-P1-631`：冲突裁决提示中最多列出多少个条目标题（总数仍按真实值展示）。 */
private const val MAX_CONFLICT_TITLES = 5

/**
 * `ISSUE-P1-631`：漂移合并的冲突裁决策略（由 [ExternalModificationChoice] 的 `MERGE_KEEP_*`
 * 取值映射；命中同一合并实现，仅冲突条目的取舍不同）。
 */
internal enum class DriftMergeConflictPolicy {
    /** 冲突条目以本地（内存树）版本为准 */
    KEEP_LOCAL,

    /** 冲突条目以磁盘版本为准 */
    KEEP_DISK,

    /** 双方保留：磁盘侧版本以新 UUID 生成「磁盘冲突副本」 */
    KEEP_BOTH
}

internal val ExternalModificationChoice.mergePolicy: DriftMergeConflictPolicy?
    get() = when (this) {
        ExternalModificationChoice.MERGE_KEEP_LOCAL -> DriftMergeConflictPolicy.KEEP_LOCAL
        ExternalModificationChoice.MERGE_KEEP_DISK -> DriftMergeConflictPolicy.KEEP_DISK
        ExternalModificationChoice.MERGE_KEEP_BOTH -> DriftMergeConflictPolicy.KEEP_BOTH
        else -> null
    }

/** `ISSUE-P1-631`：可信基线的解析产物（[revision] 供提交前修订校验配对） */
internal class TrustedBaseline(
    val revision: Long,
    val database: KdbxDatabase
)

/** `ISSUE-P1-631`：`KdbxDatabase` → 合并镜像（三项同形，禁逐处手抄字段清单） */
internal fun KdbxDatabase.toMergeLite(): KdbxDatabaseLite =
    KdbxDatabaseLite(rootGroup, deletedObjects, customIcons)

/**
 * `ISSUE-P1-631`：漂移合并的**真实基线解析与冲突裁决**支持（自 `VaultFileDriftResolve` 拆出，
 * 控行数门禁）。职责只有两件：
 *
 * 1. **真实共同基线**——取最后确认持久化版本快照（`DatabaseSession.persistedBaselineSnapshot`），
 *    经「存在 / 绑定库身份 / 可解析」三重可信检验后作为三方合并底版；任一不满足返回 null，
 *    调用方按空底版**双方并集**语义合并（禁静默做删除裁决）；
 * 2. **冲突裁决**——合并发现同字段双侧修改时不静默取一侧：无策略时置待裁决提示并令调用方停止
 *    落盘；有策略（本地 / 磁盘 / 双方保留）时按策略把冲突条目落回合并树。
 *
 * 快照字节无论成败都在本类内清零（`PersistedDatabaseBaseline.wipe`）；解析成功的树归调用方，
 * 由 `VaultFileDriftResolve` 的 finally 按身份集合判定回收。
 */
internal class VaultFileDriftMergeSupport(
    private val databaseSession: DatabaseSession,
    private val strings: StringsProvider,
    private val driftCoordinator: VaultFileDriftCoordinator?
) {

    /**
     * 解析**可信**的真实共同基线；任一条件不满足返回 null（调用方走空底版并集语义）。
     *
     * 可信条件：
     * 1. 存在最后确认持久化版本快照（打开 / 重载 / 新建 / 保存 / Meta 提交 / 换密六类确认写盘点登记）；
     * 2. **绑定库身份**：快照路径与当前会话路径一致（换库后旧基线不得借用）；
     * 3. **可解析**：以当前会话凭据解析成功（换密后基线未同步前移的窗口内解析失败 ⇒ 不可信）。
     */
    suspend fun resolveTrustedBaseline(pathId: String): TrustedBaseline? {
        val snapshot = databaseSession.persistedBaselineSnapshot()
            ?: return null
        try {
            if (snapshot.pathIdentifier != pathId) {
                AppLog.w(DRIFT_LOG_TAG, "持久化基线不属于当前库，按无可信基线合并")
                return null
            }
            val parsed = databaseSession.parseExternalDatabase(snapshot.bytes).getOrNull()
            if (parsed == null) {
                AppLog.w(DRIFT_LOG_TAG, "持久化基线解析失败，按无可信基线合并")
                return null
            }
            return TrustedBaseline(revision = snapshot.revision, database = parsed)
        } finally {
            snapshot.wipe()
        }
    }

    /**
     * 空底版（同步侧 `resolveTrustedBase` 同口径）：三方合并退化为双方并集语义，
     * 不产生推断删除；同字段分叉进入冲突清单（宁多冲突不静默丢数据）。
     */
    fun baselineLiteOf(base: KdbxDatabase?): KdbxDatabaseLite =
        base?.toMergeLite() ?: KdbxDatabaseLite(KdbxGroup(name = ""), emptyList())

    /**
     * 冲突裁决前置——无冲突直接返回合并根；有策略按策略落定冲突条目；
     * 无策略时置待裁决提示并返回 null（调用方不落盘、如实上浮待裁决结果）。
     */
    fun resolveConflictsBeforeAdopt(
        merged: MergeResult,
        policy: DriftMergeConflictPolicy?,
        pathId: String
    ): KdbxGroup? {
        if (merged.conflicts.isEmpty()) return merged.mergedRoot
        if (policy == null) {
            // 同字段双侧修改不静默取一侧——置待裁决提示并**不落盘**；
            // 裁决（本地 / 磁盘 / 双方保留）驱动同一合并实现重算后提交
            driftCoordinator?.requestMergeConflictPrompt(
                pathIdentifier = pathId,
                conflictCount = merged.conflicts.size,
                titles = conflictTitles(merged.conflicts)
            )
            AppLog.w(DRIFT_LOG_TAG, "漂移合并存在待裁决冲突: count=${merged.conflicts.size}")
            return null
        }
        return applyConflictPolicy(merged.mergedRoot, merged.conflicts, policy)
    }

    /** 待裁决条目的展示用标题（本地优先，空则取磁盘侧）；仅取前 [MAX_CONFLICT_TITLES] 条 */
    private fun conflictTitles(conflicts: List<ConflictedEntryPair>): List<String> =
        conflicts.take(MAX_CONFLICT_TITLES).map { pair ->
            pair.localEntry.displayTitle().ifBlank { pair.remoteEntry.displayTitle() }
        }

    /**
     * 按用户裁决把冲突条目落回合并树（复用同步裁决的**单趟落树**原语，避免两份实现漂移）。
     * 非冲突条目保持三方合并产物原样。
     */
    private fun applyConflictPolicy(
        mergedRoot: KdbxGroup,
        conflicts: List<ConflictedEntryPair>,
        policy: DriftMergeConflictPolicy
    ): KdbxGroup {
        val byParent = mutableMapOf<KdbxUuid, MutableList<KdbxEntry>>()
        for (pair in conflicts) {
            val resolved = when (policy) {
                DriftMergeConflictPolicy.KEEP_LOCAL -> listOf(pair.localEntry)
                DriftMergeConflictPolicy.KEEP_DISK -> listOf(pair.remoteEntry)
                DriftMergeConflictPolicy.KEEP_BOTH -> KdbxMerger.resolveConflict(
                    pair = pair,
                    choice = ConflictResolutionChoice.DUPLICATE_BOTH,
                    // 本项中 remote = 磁盘版本，副本标题不得沿用同步侧「云端」口径
                    duplicateTitleSuffix = strings.get(R.string.ext_mod_merge_duplicate_suffix)
                )
            }
            for (entry in resolved) {
                byParent.getOrPut(entry.parentGroupId ?: mergedRoot.id) { mutableListOf() }.add(entry)
            }
        }
        return applyResolvedEntriesToGroup(mergedRoot, byParent)
    }
}
