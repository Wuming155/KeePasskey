package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.database.file.KdbxDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 会话内容变更（ISSUE-P3-31 批次 D 结构拆分）。
 *
 * 自 [DatabaseSession] 原样抽出条目 / 分组的增删改与批量操作：统一在 [mutex] 临界区内
 * 对 [databaseFlow] 做 copy-on-write 替换、对 [stateFlow] 置 DIRTY，并在替换前定点擦除下线实例。
 *
 * 擦除分两档（ISSUE-P3-156 / ISSUE-P3-157）：
 * - **增量**（[saveEntry] / [saveGroup] / [batchMoveEntries] / [updateEntryById]）：树变换回报被替换节点，
 *   候选集合规模 = 被替换节点规模，不随全库规模增长；
 * - **通用**（[updateDatabaseMeta]）：任意整库变换无法定位替换位置，退回为整棵新树建身份集合。
 *
 * 只读态经 [readOnly] 提供方判定后直接 no-op。
 */
internal class SessionContentMutations(
    private val mutex: Mutex,
    private val databaseFlow: MutableStateFlow<KdbxDatabase?>,
    private val stateFlow: MutableStateFlow<DatabaseSession.SessionState>,
    private val readOnly: () -> Boolean
) {

    /** 更新或保存条目。 */
    suspend fun saveEntry(entry: KdbxEntry) = mutex.withLock {
        if (readOnly()) return@withLock
        val currentDb = databaseFlow.value ?: return@withLock
        val edit = SessionTreeEditor.updateOrAddEntry(currentDb.rootGroup, entry)
        // ISSUE-P2-06 / P3-156：copy-on-write 替换前定点擦除——只以本次被替换下线的旧节点为候选，
        // 集合规模不随全库规模增长（前提是 SessionTreeEditor 的路径复制契约）
        edit.replaced?.let { edit.root.eraseSupersededSensitiveData(it, edit.replacement) }
        databaseFlow.value = currentDb.copy(rootGroup = edit.root)
        stateFlow.value = DatabaseSession.SessionState.DIRTY
    }

    /** 删除条目。 */
    suspend fun deleteEntry(entryId: KdbxUuid) = mutex.withLock {
        if (readOnly()) return@withLock
        val currentDb = databaseFlow.value ?: return@withLock
        val updatedRoot = SessionTreeEditor.removeEntry(currentDb.rootGroup, entryId)
        // ISSUE-P2-06 修正：删除路径禁止身份擦除——删除没有「替换树」，而调用方（回收站软删）
        // 会在 deleteEntry 之后用与旧条目**共享同一 ProtectedString 实例**的 moved 副本重新
        // saveEntry；若按「新树未包含 = 已下线」判定，会把即将复用的存活字段一并清空，
        // 条目遂成空壳、后续 save() 序列化抛 IllegalStateException（app 回归实测复现）。
        // 下线实例交由 GC 回收；有替换树的写入路径（saveEntry/saveGroup/updateDatabaseMeta）仍照常擦除。
        databaseFlow.value = currentDb.copy(rootGroup = updatedRoot)
        stateFlow.value = DatabaseSession.SessionState.DIRTY
    }

    /**
     * 保存或更新分组。
     *
     * P0-1 防御性保护：更新既有分组（含根分组）时，若传入分组不携带任何子项而既有分组含有子项，
     * 经 [SessionTreeEditor.preserveChildrenIfMissing] 保留既有子项，防止「重命名/改图标」等仅更新
     * 元数据的调用路径意外清空子条目与子分组。
     */
    suspend fun saveGroup(group: KdbxGroup) = mutex.withLock {
        if (readOnly()) return@withLock
        val currentDb = databaseFlow.value ?: return@withLock
        val edit = if (group.id == currentDb.rootGroup.id) {
            SessionTreeEditor.replaceRootGroup(currentDb.rootGroup, group)
        } else {
            SessionTreeEditor.updateOrAddGroup(currentDb.rootGroup, group)
        }
        // ISSUE-P2-06 / P3-156：分组保存可能下线旧条目/旧字段实例，替换前对被替换节点定点擦除
        edit.replaced?.let { edit.root.eraseSupersededSensitiveData(it, edit.replacement) }
        databaseFlow.value = currentDb.copy(rootGroup = edit.root)
        stateFlow.value = DatabaseSession.SessionState.DIRTY
    }

    /**
     * 单条条目原子读-改-写（ISSUE-P3-157）：在会话 Mutex 内的**单次**受控变换中完成
     * 「按 id 定位 → [transform] 变换 → 落树 → 增量定点擦除」。
     *
     * 与 [updateDatabaseMeta] 的分工：本入口的树变换由 [SessionTreeEditor] 完成并回报替换关系，
     * 因此擦除候选只取自被替换的那一条旧条目（集合规模 = 单条条目，不随全库规模增长）；
     * [updateDatabaseMeta] 的变换是**任意整库变换**（同步合并 / 远端库接管会整体替换分组树），
     * 无法定位替换位置，只能保留通用实现。
     *
     * @return 条目存在时返回 [transform] 的产物（**落树上线的实例**，调用方需要回传的值应写入
     *   该实例后再从它读取，保证与已提交状态同源）；条目不存在（或只读态 / 无活动库）时返回 null，
     *   且**不写入、不置 DIRTY**——调用方不得据此认为写入已发生。
     */
    suspend fun updateEntryById(
        entryId: KdbxUuid,
        transform: (KdbxEntry) -> KdbxEntry
    ): KdbxEntry? = mutex.withLock {
        if (readOnly()) return@withLock null
        val currentDb = databaseFlow.value ?: return@withLock null
        val edit = SessionTreeEditor.updateEntryById(currentDb.rootGroup, entryId, transform)
            ?: return@withLock null
        // ISSUE-P2-06 / P3-157：copy-on-write 替换前定点擦除——只以本次被替换下线的旧条目为候选
        edit.replaced?.let { edit.root.eraseSupersededSensitiveData(it, edit.replacement) }
        databaseFlow.value = currentDb.copy(rootGroup = edit.root)
        stateFlow.value = DatabaseSession.SessionState.DIRTY
        edit.replacement
    }

    /**
     * 允许受控原子修改数据库顶层元数据与墓碑列表（例如 recycleBinUuid、deletedObjects 追加）。
     * 修改后置为 DIRTY 状态，供后续统一 save() 序列化落盘。
     *
     * ISSUE-P3-156：本入口的变换是**任意整库变换**（同步合并 / 远端库接管会整体替换分组树），
     * 无法定位被替换节点 ⇒ 擦除保留通用实现（为整棵新树建身份集合，O(全库)）；仅改元数据
     * （根分组同一实例）时零成本返回。需要 O(被替换节点) 的单条写入请走 [saveEntry] 或
     * [updateEntryById]（后者额外提供会话 Mutex 内的原子读-改-写）。
     */
    suspend fun updateDatabaseMeta(transform: (KdbxDatabase) -> KdbxDatabase) = mutex.withLock {
        if (readOnly()) return@withLock
        val currentDb = databaseFlow.value ?: return@withLock
        val updated = transform(currentDb)
        // ISSUE-P2-06：元数据变换同样可能下线旧条目实例，替换前定点擦除
        currentDb.rootGroup.clearSupersededSensitiveData(updated.rootGroup)
        // ISSUE-P3-258（契约 Step 4）：与树擦除同点收口——换下库的二进制池中，不被新库以同一
        // `BinaryItem` 实例引用的条目就地清零（copy 形态共享同一池列表 ⇒ 全部跳过；
        // 远端库整体接管的形态池不相交 ⇒ 旧池全量擦除，不再滞留 GC）。身份集合判定，
        // 与上方树擦除的存活口径一致，禁止退化为裸 forEach 清池。
        currentDb.clearBinaryPool(updated.binaries)
        databaseFlow.value = updated
        stateFlow.value = DatabaseSession.SessionState.DIRTY
    }

    /**
     * ISSUE-P2-278：「校验-采用」原子落库——**仅当**当前会话树仍是 [expectedAtCycleStart]
     * 那一棵实例时，才以 [replacement] 整树替换并置 DIRTY；否则什么都不做并返回 false。
     *
     * 存在意义：同步周期（含合并计算 / 网络往返 / 冲突待决窗口）期间 UI 写路径**不取**
     * `SyncSessionState.mutex`，周期起点之后用户仍可能编辑并保存——本类全部写入入口均为
     * copy-on-write（`databaseFlow` 实例必被替换），故「实例身份未变」即「窗口内无本地编辑」。
     * 在会话 Mutex 内完成校验与采用，与全部写路径（mutations / save）互斥，无 TOCTOU；
     * 调用方拿到 false 必须如实中止（禁静默以旧快照算出的接管树 / 合并树覆盖会话，
     * 否则窗口内的编辑从内存与文件同时消失）。
     *
     * 已知保守面：`save()` 触发按龄修剪历史时也会替换实例（内容变化极小的误判），
     * 结果是本轮同步如实中止、下轮重试即收敛——宁可误中止，不可静默丢编辑。
     *
     * 擦除口径与 [updateDatabaseMeta] 逐字一致（采用点同一收口，身份集合判定）。
     */
    suspend fun adoptDatabaseIfUnchanged(
        expectedAtCycleStart: KdbxDatabase,
        replacement: KdbxDatabase
    ): Boolean = mutex.withLock {
        if (readOnly()) return@withLock false
        val currentDb = databaseFlow.value ?: return@withLock false
        if (currentDb !== expectedAtCycleStart) return@withLock false
        currentDb.rootGroup.clearSupersededSensitiveData(replacement.rootGroup)
        currentDb.clearBinaryPool(replacement.binaries)
        databaseFlow.value = replacement
        stateFlow.value = DatabaseSession.SessionState.DIRTY
        true
    }

    /** 删除分组。 */
    suspend fun deleteGroup(groupId: KdbxUuid) = mutex.withLock {
        if (readOnly()) return@withLock
        val currentDb = databaseFlow.value ?: return@withLock
        if (groupId == currentDb.rootGroup.id) return@withLock
        val updatedRoot = SessionTreeEditor.removeGroup(currentDb.rootGroup, groupId)
        // ISSUE-P2-06 修正：同 deleteEntry——删除路径无替换树，禁止身份擦除（会误伤调用方复用中的共享实例）
        databaseFlow.value = currentDb.copy(rootGroup = updatedRoot)
        stateFlow.value = DatabaseSession.SessionState.DIRTY
    }

    /** 批量移动条目。 */
    suspend fun batchMoveEntries(entryIds: Set<KdbxUuid>, targetGroupId: KdbxUuid?) = mutex.withLock {
        if (readOnly()) return@withLock
        val currentDb = databaseFlow.value ?: return@withLock
        val entriesToMove = currentDb.rootGroup.allEntries().filter { it.id in entryIds }
        // ISSUE-P3-160：一次批量移除（单趟按 id 集合剪枝整棵树），取代「对每个条目各调一次
        // removeEntry」——后者 K 个 id 即 O(K × 节点数) 次整树遍历 + K × 分组数 次列表分配，
        // 且全程持会话 Mutex。被移除集合与逐条实现相同（entriesToMove 的 id ⊆ entryIds）。
        var currentRoot = SessionTreeEditor.removeEntries(currentDb.rootGroup, entryIds)
        // ISSUE-P2-06 / P3-156：待擦除对 = 源位置被移除的旧条目 → 其 moved 副本（`copy` 按引用
        // 共享全部敏感实例，故候选集合通常为空）；目标位置若已有同 id 旧节点被替换，一并纳入
        val superseded = mutableListOf<Pair<KdbxEntry, KdbxEntry>>()
        for (e in entriesToMove) {
            val movedEntry = e.copy(parentGroupId = targetGroupId)
            val edit = SessionTreeEditor.updateOrAddEntry(currentRoot, movedEntry)
            currentRoot = edit.root
            superseded += e to movedEntry
            edit.replaced?.let { superseded += it to movedEntry }
        }
        // 擦除在**最终树**上执行（存活判定需看到全部移动结果）
        superseded.forEach { (old, moved) -> currentRoot.eraseSupersededSensitiveData(old, moved) }
        databaseFlow.value = currentDb.copy(rootGroup = currentRoot)
        stateFlow.value = DatabaseSession.SessionState.DIRTY
    }

    /** 批量删除条目。 */
    suspend fun batchDeleteEntries(entryIds: Set<KdbxUuid>) = mutex.withLock {
        if (readOnly()) return@withLock
        val currentDb = databaseFlow.value ?: return@withLock
        // ISSUE-P3-160：单趟按 id 集合剪枝，取代「对每个 id 各调一次 removeEntry」
        //（原实现 K 个 id ⇒ O(K × 节点数) 次整树遍历 + K × 分组数 次列表分配，全程持会话 Mutex）。
        // 仍无条件置 DIRTY 并写回新库实例——与逐条实现逐字一致（含「id 不存在」的情形）。
        val updatedRoot = SessionTreeEditor.removeEntries(currentDb.rootGroup, entryIds)
        // ISSUE-P2-06 修正：批量删除同样无替换树，禁止身份擦除（同 deleteEntry 说明）
        databaseFlow.value = currentDb.copy(rootGroup = updatedRoot)
        stateFlow.value = DatabaseSession.SessionState.DIRTY
    }
}
