package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.sync.merge.BothModifiedEntryCollector
import com.keepasskey.sync.merge.ConflictDisposition
import com.keepasskey.sync.merge.ConflictStrategyPolicy
import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import com.keepasskey.sync.merge.MergeResult
import com.keepasskey.sync.merge.SyncConflictStrategy

/**
 * `ISSUE-P3-188` §166：`SyncConflictController` 的四类**无实例状态**合并辅助（自门面逐字搬入，
 * 仅把 `codec` 由实例字段改为显式入参，其余判据、顺序与注释语义未改）。
 *
 * 为什么这四件可以离开门面：它们都不读写 `pending*` 会话态、不取会话互斥锁、也不需要
 * `strings` / `debugLog`——即「冲突决策的**裁决规则**」与「待决会话的**上下文持有**」是两件事，
 * 前者是无副作用（或仅依赖入参）的纯判定，留在门面里只会把后者撑大。
 *
 * 仍在 [SyncConflictController] 内的部分：`pending*` 通道、`resolveConflicts` 的上传与落库、
 * `handleConflictMerge` 的树获取与 `localDbOwned` 判据、`autoMergeAndUpload` /
 * `beginPendingConflict` —— 它们要么持有会话态，要么承载擦除边界与 `If-Match` 语义。
 */

/** base 裁决结果：[trusted] 供失败路径的擦除判定，[trustedLite] 为合并底版（不可信时为空库） */
internal data class SyncConflictBaseSnapshot(
    val trusted: KdbxDatabase?,
    val trustedLite: KdbxDatabaseLite
)

/**
 * F2 修复：base 快照的**三重可信检验**——存在、可解析、且内容与本地字节不同
 * （本地工作副本污染判定：KDBX4 随机 IV 使同一内容的两次序列化字节必然不同，
 * 字节级 contentEquals 命中相同即证明 base 已被本地内容顶替）。
 *
 * 不可信时严禁以本地充当 base——那会把「本地新建」误判为「远端已删除且本地未修改」
 * 而确认删除且不留墓碑，同时远端修改全胜；改为以空库充当 base，
 * 三方合并退化为双方并集语义：单侧新建保留、同 UUID 条目字段级合并、
 * 同字段分叉进入冲突清单交用户决策（宁多冲突不静默丢数据）。
 */
internal suspend fun resolveTrustedBase(
    codec: SyncDatabaseCodec,
    baseSnapshotBytes: ByteArray?,
    localBytes: ByteArray
): SyncConflictBaseSnapshot {
    val parsedBase = baseSnapshotBytes?.let { codec.parseKdbxBytes(it) }
    val trustedBase = parsedBase?.takeIf { !baseSnapshotBytes.contentEquals(localBytes) }
    // ISSUE-P3-119：被判为「不可信 base」的解析产物随即被丢弃（baseSnapshotBytes 已被本地
    // 内容顶替），不再被任何存活对象引用 → 显式擦除。
    if (parsedBase != null && trustedBase == null) wipeDiscarded(parsedBase)
    return SyncConflictBaseSnapshot(
        trusted = trustedBase,
        trustedLite = trustedBase?.let { KdbxDatabaseLite(it.rootGroup, it.deletedObjects) }
            ?: KdbxDatabaseLite(KdbxGroup(name = ""), emptyList())
    )
}

/**
 * ISSUE-P3-03 (43a)：PROMPT_USER（每次询问）把「双方各自修改过的条目」一并纳入决策清单，
 * 而不是只问同字段分歧的条目；其余策略沿用合并引擎给出的冲突清单。
 */
internal fun decisionConflictsOf(
    strategy: SyncConflictStrategy,
    base: SyncConflictBaseSnapshot,
    local: KdbxDatabaseLite,
    remote: KdbxDatabaseLite,
    mergeResult: MergeResult
): List<ConflictedEntryPair> =
    if (ConflictStrategyPolicy.dispositionOf(strategy) == ConflictDisposition.PromptUser) {
        BothModifiedEntryCollector.collect(
            trustedBase = base.trustedLite.takeIf { base.trusted != null },
            local = local,
            remote = remote,
            alreadyConflicted = mergeResult.conflicts
        )
    } else {
        mergeResult.conflicts
    }

/**
 * ISSUE-P3-119：丢弃「仅服务本次判定 / 合并、且不被任何存活对象引用」的解析产物前**显式擦除**。
 *
 * 使用前提（**逐点确认，不可套用**）：
 * - 该树的节点**没有**被 [KdbxMerger] 产物或会话库继续引用——合并器对「远端独有 / 本地独有」
 *   条目**复用原对象**（非深拷贝），因此
 *   「已被 [com.keepasskey.database.session.DatabaseSession.updateDatabaseMeta] 采用为会话库的树」与
 *   「其节点进入待决合并底版 / pending 快照的树」**一律不得擦除**，否则会静默清空活动库内容；
 * - 该树也不会在本次调用返回后被读取（如 `pendingLocalDb` / `pendingRemoteDb` 会在用户决策阶段
 *   再次被读取，故其释放只能随冲突会话结束由会话生命周期收口）；
 * - `ISSUE-P3-168` 起 [SyncConflictController.handleConflictMerge] 的 `localDbOverride` **不是**
 *   门面的解析产物——它属于调用方 / 会话（内存树快照），一律不得擦除（由 `localDbOwned` 判据把关），
 *   否则会静默清空活动库。
 */
internal fun wipeDiscarded(db: KdbxDatabase?) {
    db?.clearSensitiveData()
}

/**
 * `ISSUE-P3-161`：批量把已裁决条目落回分组树——**单趟**递归。
 *
 * 原实现（`applyResolvedEntryToGroup`，逐条版）对**每条**裁决条目复制整棵树：
 * 对每一层都执行 `subgroups.map { … }` + `copy`，目标不在该子树时也照旧复制
 * ⇒ O(裁决条目数 × 分组数) 次分组对象与列表分配。本实现按「父组 id → 待落位条目」表
 * 一次递归到底，且**只复制确实含目标的分组**（未命中子树按同一实例复用，
 * 与 `SessionTreeEditor` 的路径复制契约同口径）。
 *
 * 语义与逐条应用等价：同一父组内的条目按**原冲突顺序**逐个「找到即替换、找不到即追加」；
 * `parentGroupId` 指向的分组在树中不存在时同样不落位（与逐条实现一致）。
 */
internal fun applyResolvedEntriesToGroup(
    group: KdbxGroup,
    byParent: Map<KdbxUuid, List<KdbxEntry>>
): KdbxGroup {
    val own = byParent[group.id]
    var newEntries: List<KdbxEntry>? = null
    if (!own.isNullOrEmpty()) {
        val working = group.entries.toMutableList()
        own.forEach { entry ->
            val idx = working.indexOfFirst { it.id == entry.id }
            if (idx >= 0) {
                working[idx] = entry
            } else {
                working.add(entry)
            }
        }
        newEntries = working
    }

    var referenced: MutableList<KdbxGroup>? = null
    group.subgroups.forEachIndexed { index, sub ->
        val updated = applyResolvedEntriesToGroup(sub, byParent)
        if (updated !== sub) {
            val list = referenced ?: group.subgroups.toMutableList().also { referenced = it }
            list[index] = updated
        }
    }

    if (newEntries == null && referenced == null) return group
    return group.copy(entries = newEntries ?: group.entries, subgroups = referenced ?: group.subgroups)
}
