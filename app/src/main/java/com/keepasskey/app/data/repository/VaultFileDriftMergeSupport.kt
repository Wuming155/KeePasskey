package com.keepasskey.app.data.repository

import android.content.Context
import com.keepasskey.app.R
import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileBaseline
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.security.VaultFileDriftPolicy
import com.keepasskey.app.security.VaultFileMetadataProbe
import com.keepasskey.app.sync.applyResolvedEntriesToGroup
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import com.keepasskey.sync.merge.MergeResult
import java.io.File

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
     *
     * `ISSUE-P1-631`（裁决范围收口）：裁决**只重裁差异字段**——以三方合并产物为底版
     * （`KdbxMerger.resolveConflictFieldsOnly`），冲突条目上**非冲突字段的合并结果保持不变**。
     * 整改前若整条目取一侧，会把对侧对该条目其它字段的就近修改一并丢弃（合并器已在双侧修改
     * 分支做过字段级合并，那些改动本已在产物中）。「双方保留」= 本地侧字段版本 + 磁盘侧字段
     * 版本（换新 UUID、标题加后缀），两份都建在合并产物之上。
     *
     * 防御：合并产物中按 id 找不到该冲突条目时（理论不可达），退回以本地条目为底版执行同一
     * 字段级重裁——语义仍为「只重裁差异字段」，不放大为整树替换。
     */
    private fun applyConflictPolicy(
        mergedRoot: KdbxGroup,
        conflicts: List<ConflictedEntryPair>,
        policy: DriftMergeConflictPolicy
    ): KdbxGroup {
        val mergedById = mergedRoot.allEntries().associateBy { it.id.toHexString() }
        val byParent = mutableMapOf<KdbxUuid, MutableList<KdbxEntry>>()
        for (pair in conflicts) {
            val mergedEntry = mergedById[pair.entryId] ?: pair.localEntry
            val resolved = when (policy) {
                DriftMergeConflictPolicy.KEEP_LOCAL -> listOf(
                    KdbxMerger.resolveConflictFieldsOnly(
                        merged = mergedEntry,
                        pair = pair,
                        choice = ConflictResolutionChoice.KEEP_LOCAL
                    )
                )
                DriftMergeConflictPolicy.KEEP_DISK -> listOf(
                    KdbxMerger.resolveConflictFieldsOnly(
                        merged = mergedEntry,
                        pair = pair,
                        choice = ConflictResolutionChoice.KEEP_REMOTE
                    )
                )
                DriftMergeConflictPolicy.KEEP_BOTH -> {
                    val localSide = KdbxMerger.resolveConflictFieldsOnly(
                        merged = mergedEntry,
                        pair = pair,
                        choice = ConflictResolutionChoice.KEEP_LOCAL
                    )
                    val diskSide = KdbxMerger.resolveConflictFieldsOnly(
                        merged = mergedEntry,
                        pair = pair,
                        choice = ConflictResolutionChoice.KEEP_REMOTE
                    )
                    // 副本必须换新 UUID：KDBX 要求 UUID 全局唯一，且同 UUID 副本在应用回分组树时
                    // 会与本地原条目命中同一槽位而互相覆盖（同 `KdbxMerger.resolveConflict` 口径）。
                    val duplicate = diskSide
                        .copy(id = KdbxUuid.random())
                        .withField(
                            KdbxConstants.Fields.TITLE,
                            ProtectedString(
                                "${diskSide.title} (${strings.get(R.string.ext_mod_merge_duplicate_suffix)})",
                                isProtected = false
                            )
                        )
                    listOf(localSide, duplicate)
                }
            }
            for (entry in resolved) {
                byParent.getOrPut(entry.parentGroupId ?: mergedRoot.id) { mutableListOf() }.add(entry)
            }
        }
        return applyResolvedEntriesToGroup(mergedRoot, byParent)
    }
}

/**
 * `ISSUE-P1-631`（裁决归属）：该处置是否属于**另一个库**（换库后的过期选择）——返回非空即过期。
 *
 * 归属来源为协调器「最近一次被提示的库路径标识」（`ISSUE-P1-631` 字段）。从未提示过时不判过期：
 * 该路径仅存在于「无提示直调」场景（纯 JVM 用例 / 内部收敛路径），判过期只会误伤合法调用；
 * 生产侧一切处置都由对话框驱动，必有提示在先。
 */
internal fun staleChoiceOwner(coordinator: VaultFileDriftCoordinator?, currentPathId: String): String? {
    val prompted = coordinator?.lastPromptedPathIdentifier() ?: return null
    if (currentPathId.isEmpty()) return null
    return prompted.takeIf { it != currentPathId }
}

/**
 * `ISSUE-P1-631`（读取版本钉扎）：合并**读取时点**的通道身份与文件指纹。
 *
 * 用途：解析 / 合并是长窗口，窗口内磁盘可能被再次外部改写、会话也可能被换库——提交前
 * 复核本钉扎，把用旧版本算出的产物 fail-closed 拦下（`VaultFileDriftResolve.adoptAndPersist`）。
 *
 * 口径：判据与保存前漂移检测**同一实现**（[VaultFileDriftPolicy.shouldAbortSave]：路径不一致 /
 * size 不等 / mtime 差 ≥ 1s 均判已变），不另写一份；读取时点元数据不可读（`baseline == null`）
 * 时不把「不可读」当证据，仅在**当前**仍不可读时放行（与既有「宁可不提示」口径一致）。
 */
internal class VaultFileReadPin private constructor(
    private val pathId: String,
    /** 捕获时是否走本地 `File` 通道（复核时通道类型变化 ⇔ 已换库/换通道） */
    private val fileChannel: Boolean,
    private val baseline: VaultFileBaseline?
) {

    /**
     * 复核：通道身份未变且文件指纹未变 ⇒ true（可安全提交）。
     *
     * [currentPathId] / [currentFile] 必须取**复核时点**的会话当前值——换库后与捕获值不同，
     * 直接判拒（并发 barrier 验收里「库 A 的旧任务不得提交到库 B」的第一道闸）。
     */
    fun isStillCurrent(context: Context?, currentPathId: String, currentFile: File?): Boolean {
        if (pathId != currentPathId) return false
        if (fileChannel != (currentFile != null)) return false
        val current = if (currentFile != null) {
            VaultFileBaseline.fromFile(currentFile)
        } else {
            VaultFileMetadataProbe.baselineFor(context, currentPathId)
        }
        val before = baseline ?: return current == null
        val after = current ?: return false
        return !VaultFileDriftPolicy.shouldAbortSave(
            baseline = before.copy(pathIdentifier = currentPathId),
            current = after
        )
    }

    companion object {
        /** 读取时点捕获（本地 `File` 直读属性；SAF 取文档元数据；不可读 ⇒ baseline 为 null） */
        fun capture(context: Context?, pathId: String, localFile: File?): VaultFileReadPin =
            VaultFileReadPin(
                pathId = pathId,
                fileChannel = localFile != null,
                baseline = if (localFile != null) {
                    VaultFileBaseline.fromFile(localFile)
                } else {
                    VaultFileMetadataProbe.baselineFor(context, pathId)
                }
            )
    }
}
