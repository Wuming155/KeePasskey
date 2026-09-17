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
 * 擦除分两档（ISSUE-P3-156）：
 * - **增量**（[saveEntry] / [saveGroup] / [batchMoveEntries]）：树变换回报被替换节点，
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
     * 允许受控原子修改数据库顶层元数据与墓碑列表（例如 recycleBinUuid、deletedObjects 追加）。
     * 修改后置为 DIRTY 状态，供后续统一 save() 序列化落盘。
     *
     * ISSUE-P3-156：本入口的变换是**任意整库变换**（同步合并 / 远端库接管会整体替换分组树），
     * 无法定位被替换节点 ⇒ 擦除保留通用实现（为整棵新树建身份集合，O(全库)）；仅改元数据
     * （根分组同一实例）时零成本返回。需要 O(被替换节点) 的单条写入请走 `saveEntry`。
     */
    suspend fun updateDatabaseMeta(transform: (KdbxDatabase) -> KdbxDatabase) = mutex.withLock {
        if (readOnly()) return@withLock
        val currentDb = databaseFlow.value ?: return@withLock
        val updated = transform(currentDb)
        // ISSUE-P2-06：元数据变换同样可能下线旧条目实例，替换前定点擦除
        currentDb.rootGroup.clearSupersededSensitiveData(updated.rootGroup)
        databaseFlow.value = updated
        stateFlow.value = DatabaseSession.SessionState.DIRTY
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
        var currentRoot = currentDb.rootGroup
        for (e in entriesToMove) {
            currentRoot = SessionTreeEditor.removeEntry(currentRoot, e.id)
        }
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
        var currentRoot = currentDb.rootGroup
        for (id in entryIds) {
            currentRoot = SessionTreeEditor.removeEntry(currentRoot, id)
        }
        // ISSUE-P2-06 修正：批量删除同样无替换树，禁止身份擦除（同 deleteEntry 说明）
        databaseFlow.value = currentDb.copy(rootGroup = currentRoot)
        stateFlow.value = DatabaseSession.SessionState.DIRTY
    }
}
