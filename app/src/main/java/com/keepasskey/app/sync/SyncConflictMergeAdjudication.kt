package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.history.HistoryManager
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
 * `ISSUE-P3-235` G2（`RC-02` 收口）起，**丢弃解析树的擦除原语**（[wipeDiscarded] 的无别名形态、
 * [eraseDiscardedDatabase] / [eraseDiscardedGroup] / [eraseDiscardedParseResults] 的身份集合判定形态）
 * 同置本文件：擦除**判定**无实例状态，门面只负责在各自钩子上调用它。
 *
 * 仍在 [SyncConflictController] 内的部分：`pending*` 通道、`resolveConflicts` 的上传与落库、
 * `handleConflictMerge` 的树获取与 `localDbOwned` 判据、`autoMergeAndUpload` /
 * `beginPendingConflict` —— 它们要么持有会话态，要么承载擦除**时机**与 `If-Match` 语义。
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
        // ISSUE-P2-280：base 镜像同样带图标池（合并为双向并集，base 图标池不直接参与，
        // 但保持镜像三面同形，避免后续按镜像取池时拿到空表）
        trustedLite = trustedBase?.let { KdbxDatabaseLite(it.rootGroup, it.deletedObjects, it.customIcons) }
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
 * ISSUE-P3-119：丢弃「仅服务本次判定 / 合并、且不被任何存活对象引用」的解析产物前**显式擦除**
 * （`clearSensitiveData()` 自 `ISSUE-P3-258` 起一并清零该库的二进制池）。
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
 * `ISSUE-P3-292`：合并产物在**序列化 / 上传 / 落库之前**按库级 Meta 上限截断每条目历史。
 *
 * 缺陷背景：`KdbxEntryMerger` 的合并产物是 `local.history + remote.history + base.history`
 * 的三方**并集**（仅按 `lastModificationTime` 去重），而合并路径此前不经任何截断——
 * ① 未被用户再编辑过的条目，其合并来的历史**永久留存**；② 每个历史快照都是完整 `KdbxEntry`
 * （含各自 `ProtectedString`），内存逐快照单调增长；③ 与附件池引用计费耦合（限界表 §29
 * 的「单条目引用次数 ≤ 1024」正是按「合并历史未截断」取的宽值，本函数落地后该值已可复评）。
 *
 * 两条合并路径**共用本函数**（自动合并 / 用户裁决），且都在 `serializeLocalDatabase` 之前调用
 * ——上传给云端的字节与本地落库的树取自同一个已截断的 `mergedDb`，不存在「本地截了、远端没截」。
 *
 * 截断判据一律取自**库级 Meta**（[KdbxDatabase.historyMaxItems] / [KdbxDatabase.historyMaxSize]，
 * 即官方 `PwDatabase.MaintainHistory` 的两个上限），与保存路径的既有修剪口径同源
 * （[HistoryManager.pruneGroupHistoryByLimit] 与 `recordHistorySnapshot` 复用同一 `pruneHistory`）。
 *
 * **未发生修剪时返回同一实例**（`rootGroup` 身份不变），调用方免于无谓的整库对象重建。
 */
internal fun truncateMergedHistory(db: KdbxDatabase): KdbxDatabase {
    val prunedRoot = HistoryManager.pruneGroupHistoryByLimit(
        group = db.rootGroup,
        maxItems = db.historyMaxItems,
        maxSize = db.historyMaxSize
    )
    return if (prunedRoot === db.rootGroup) db else db.copy(rootGroup = prunedRoot)
}

/**
 * `ISSUE-P3-235` G2（`RC-02` 收口）：丢弃整棵解析树前的**身份集合判定**擦除。
 *
 * 与 [wipeDiscarded] 的分工（**选错即 P0 级数据损坏**）：
 * - [wipeDiscarded] 只适用于「本次判定内自解析、且确定不存在任何存活别名」的树；
 * - 本函数适用于「**可能**被存活别名引用」的树：[KdbxDatabase] 是 data class，
 *   `localDb.copy(rootGroup = mergedRoot, …)` 与 [KdbxMerger] 对「单侧独有对象」的**原实例复用**
 *   会使同一 `ProtectedString` / `KdbxAttachment` 实例被「待丢弃树」与「活动会话树」同时可达
 *   （`SECURITY_RECHECK_2026-09.md` §9.6 #19 同型）。此时裸调 `clearSensitiveData()` 会
 *   **静默清空活动库内容**——这正是 `ISSUE-P3-119` / §52 在原「所有权语义未定义」前提下
 *   对 `pending*` 树**只丢引用不擦除**的原因。
 *
 * 判据（对齐 `KdbxGroup.clearSupersededSensitiveData` 的身份集合口径）：
 * 以 [live]（当前活动会话树）为存活侧收集敏感实例身份，**只擦除 [db] 中不被存活树以同一对象
 * 引用到的实例**。头部 KDF secret `K` 另有一道独立护栏：`localDb.copy(...)` 产出的合并树与来源树
 * **共享同一 `header` 实例**（`KdbxHeader` 为 data class 浅拷贝），故仅在 `db.header !== live?.header`
 * 时才擦——误擦会让活动库以「无 `K`」的头部重新派生（fail-visible，但属静默写坏库的同型风险）。
 *
 * [live] 为 null 表示**确无存活别名**（会话终止路径：活动树已被 `DatabaseSession` 先行擦除并置空）
 * ⇒ 全量擦除；[db] 与 [live] 为同一实例时不做任何动作。
 *
 * **池内二进制同口径**（`ISSUE-P3-258` 准入② / 契约 Step 4）：除树与头部外，本函数同时对
 * [db] 的二进制池按**实例身份**判定擦除——跳过被 [live] 以同一 `BinaryItem` 实例引用的条目
 * （`localDb.copy(...)` 共享同一池列表的形态即由此护栏保住），[live] 为 null 时全量擦池。
 * 裸 `binaries.forEach { it.clear() }` 会使共享池的存活库拿到全零附件，由
 * `SyncPendingTreeErasureTest` 的共享池护栏机检。
 */
internal fun eraseDiscardedDatabase(db: KdbxDatabase, live: KdbxDatabase?) {
    if (db === live) return
    eraseDiscardedGroup(db.rootGroup, live?.rootGroup)
    if (db.header !== live?.header) db.header.kdfParameters.clearSensitive()
    db.clearBinaryPool(live?.binaries ?: emptyList())
}

/**
 * [eraseDiscardedDatabase] 的分组版：擦除 [discarded] 子树中**未被 [liveRoot] 以同一实例引用**
 * 的敏感实例（`ProtectedString` / `KdbxAttachment`，含 history）；[liveRoot] 为 null 时全量擦除
 * （含未被任何存活树引用的下线实例），[discarded] 与 [liveRoot] 为同一实例时不动作。
 */
internal fun eraseDiscardedGroup(discarded: KdbxGroup, liveRoot: KdbxGroup?) {
    if (liveRoot == null) {
        discarded.clearSensitiveData()
    } else {
        discarded.clearSupersededSensitiveData(liveRoot)
    }
}

/**
 * `ISSUE-P3-235` G2：一次三方合并判定的解析产物**全部失去持有者**时，逐棵按身份集合判定擦除
 * （存活侧 = [live]，即当前活动会话树；判据与红线见 [eraseDiscardedDatabase]）。
 *
 * @param mergedDb 本次合并产物；**已被采用为会话库时必须传 `null`**（它即存活侧自身）
 * @param localDbOwned [localDb] 是否为调用方自己解析出的独立副本；false 表示它属调用方 / 会话
 *   （`ISSUE-P3-168` 的 `localDbOverride` 入参），与 [wipeDiscarded] 同口径**一律不擦**
 */
internal fun eraseDiscardedParseResults(
    localDb: KdbxDatabase,
    localDbOwned: Boolean,
    remoteDb: KdbxDatabase,
    trustedBase: KdbxDatabase?,
    mergedDb: KdbxDatabase?,
    live: KdbxDatabase?
) {
    mergedDb?.let { eraseDiscardedDatabase(it, live) }
    if (localDbOwned) eraseDiscardedDatabase(localDb, live)
    eraseDiscardedDatabase(remoteDb, live)
    trustedBase?.let { eraseDiscardedDatabase(it, live) }
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
