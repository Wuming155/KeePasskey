package com.keepasskey.app.data.repository

import android.content.Context
import android.net.Uri
import com.keepasskey.app.R
import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileBaseline
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.security.VaultFileDriftPolicy
import com.keepasskey.app.security.VaultFileMetadataProbe
import com.keepasskey.app.sync.applyResolvedEntriesToGroup
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.util.readBoundedBytes
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
import com.keepasskey.sync.merge.ConflictedGroupPair
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxEntryDuplicator
import com.keepasskey.sync.merge.KdbxGroupConflictResolver
import com.keepasskey.sync.merge.KdbxGroupDuplicator
import com.keepasskey.sync.merge.KdbxMerger
import com.keepasskey.sync.merge.MergeResult
import java.io.File
import java.security.MessageDigest

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
     * 冲突裁决前置——无冲突直接返回合并根；有策略按策略落定冲突（条目 + **分组**）；
     * 无策略时置待裁决提示并返回 null（调用方不落盘、如实上浮待裁决结果）。
     *
     * `ISSUE-P1-631`：**条目与分组必须一并判定**。分组此前无冲突模型，双侧改名 / 改备注 /
     * 改图标 / 改父组 / 改组级 customData 由 LWW 静默定案——同属「同字段双侧修改静默取一侧」，
     * 与本项要消除的数据丢失面同类，不得因「实现能力不足」而豁免。
     */
    fun resolveConflictsBeforeAdopt(
        merged: MergeResult,
        policy: DriftMergeConflictPolicy?,
        pathId: String
    ): KdbxGroup? {
        val total = merged.conflicts.size + merged.groupConflicts.size
        if (total == 0) return merged.mergedRoot
        if (policy == null) {
            // 同字段双侧修改不静默取一侧——置待裁决提示并**不落盘**；
            // 裁决（本地 / 磁盘 / 双方保留）驱动同一合并实现重算后提交
            driftCoordinator?.requestMergeConflictPrompt(
                pathIdentifier = pathId,
                conflictCount = total,
                titles = conflictTitles(merged)
            )
            AppLog.w(
                DRIFT_LOG_TAG,
                "漂移合并存在待裁决冲突: 条目=${merged.conflicts.size} 分组=${merged.groupConflicts.size}"
            )
            return null
        }
        var root = merged.mergedRoot
        if (merged.conflicts.isNotEmpty()) {
            root = applyEntryConflictPolicy(root, merged.conflicts, policy)
        }
        if (merged.groupConflicts.isNotEmpty()) {
            root = applyGroupConflictPolicy(root, merged.groupConflicts, policy) ?: return null
        }
        return root
    }

    /**
     * 待裁决对象的展示用标题（仅展示面，不承载任何字段值 / 明文）；
     * 条目在前、分组在后，共取前 [MAX_CONFLICT_TITLES] 条（总数仍按真实值展示）。
     */
    private fun conflictTitles(merged: MergeResult): List<String> {
        val entryTitles = merged.conflicts.map { pair ->
            pair.localEntry.displayTitle().ifBlank { pair.remoteEntry.displayTitle() }
        }
        val groupTitles = merged.groupConflicts.map { pair ->
            strings.get(R.string.ext_mod_merge_conflict_group_prefix) + pair.groupName
        }
        return (entryTitles + groupTitles).take(MAX_CONFLICT_TITLES)
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
    private fun applyEntryConflictPolicy(
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
                    // `ISSUE-P1-631`（审计发现）：history 必须同批跟随——走唯一实现
                    // `KdbxEntryDuplicator`，否则副本 history 仍带原条目 UUID。
                    val duplicate = KdbxEntryDuplicator
                        .duplicateWithNewId(diskSide)
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

    /**
     * `ISSUE-P1-631`（分组裁决）：按用户裁决把冲突分组落回合并树。
     *
     * - 本地 / 磁盘：只重裁差异字段（`KdbxGroupConflictResolver.resolveFieldsOnly`），
     *   非差异字段保持三方合并产物；
     * - 双方保留：原分组保留**本地**字段值，另在**同一父组下**插入一棵复制子树，其根取
     *   **磁盘**字段值、名字带「磁盘冲突副本」后缀；复制体上分组与条目 UUID 全部重生成
     *   （`KdbxGroupDuplicator`），history 指向新条目 UUID、子树内部引用重映射。
     *
     * 任一环节找不到目标节点（树结构与冲突清单不一致，理论不可达）返回 null，
     * 调用方 fail-closed —— 绝不静默丢掉一条冲突。
     */
    private fun applyGroupConflictPolicy(
        mergedRoot: KdbxGroup,
        conflicts: List<ConflictedGroupPair>,
        policy: DriftMergeConflictPolicy
    ): KdbxGroup? {
        val byHex = mergedRoot.allGroups().associateBy { it.id.toHexString() }
        val replacements = mutableMapOf<KdbxUuid, KdbxGroup>()
        val duplicates = mutableListOf<ConflictedGroupPair>()
        for (pair in conflicts) {
            val mergedGroup = byHex[pair.groupId] ?: return null
            when (policy) {
                DriftMergeConflictPolicy.KEEP_LOCAL -> {
                    replacements[mergedGroup.id] = KdbxGroupConflictResolver.resolveFieldsOnly(
                        merged = mergedGroup,
                        pair = pair,
                        choice = ConflictResolutionChoice.KEEP_LOCAL
                    )
                }
                DriftMergeConflictPolicy.KEEP_DISK -> {
                    replacements[mergedGroup.id] = KdbxGroupConflictResolver.resolveFieldsOnly(
                        merged = mergedGroup,
                        pair = pair,
                        choice = ConflictResolutionChoice.KEEP_REMOTE
                    )
                }
                DriftMergeConflictPolicy.KEEP_BOTH -> {
                    replacements[mergedGroup.id] = KdbxGroupConflictResolver.resolveFieldsOnly(
                        merged = mergedGroup,
                        pair = pair,
                        choice = ConflictResolutionChoice.KEEP_LOCAL
                    )
                    duplicates += pair
                }
            }
        }
        var root = KdbxGroupConflictResolver.applyResolvedGroupsToTree(mergedRoot, replacements)
            ?: return null
        for (pair in duplicates) {
            val targetId = root.allGroups().firstOrNull { it.id.toHexString() == pair.groupId }?.id
                ?: return null
            root = KdbxGroupDuplicator.duplicateSubtreeAsSibling(
                root = root,
                targetId = targetId,
                newName = duplicateGroupName(pair),
                // 复制体根节点取磁盘侧字段值；parentGroupId 由复制器重挂为原分组的父组
                resolveTargetFields = { duplicate ->
                    KdbxGroupConflictResolver.resolveFieldsOnly(
                        merged = duplicate,
                        pair = pair,
                        choice = ConflictResolutionChoice.KEEP_REMOTE
                    )
                }
            ) ?: return null
        }
        return root
    }

    /** 分组冲突副本的显示名：磁盘侧组名 + 后缀（空名与条目侧同口径，退化为仅后缀） */
    private fun duplicateGroupName(pair: ConflictedGroupPair): String {
        val suffix = strings.get(R.string.ext_mod_merge_duplicate_suffix)
        val diskName = pair.remoteGroup.name
        return if (diskName.isBlank()) suffix else "$diskName ($suffix)"
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
