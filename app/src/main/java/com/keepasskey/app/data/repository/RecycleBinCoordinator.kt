package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * 库内回收站协调器（TASK-21 拆分自 RealVaultRepository）。
 * 职责单一：条目/分组的回收站语义删除（软删→移入回收站；硬删→物理删除+墓碑）、
 * 还原、清空与回收站组懒创建。落盘统一经 [persistSession] 回传仓库出口，
 * 保存失败原样向上传播（H3 语义不变）。
 */
internal class RecycleBinCoordinator(
    private val strings: StringsProvider,
    private val databaseSession: DatabaseSession,
    private val persistSession: suspend () -> KdbxResult<Unit>
) {

    /** 删除条目：回收站启用且未在回收站内→移入回收站；否则物理删除并记录墓碑 */
    suspend fun deleteEntry(id: String): KdbxResult<Unit> {
        val uuid = parseKdbxUuidOrNull(id)
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_invalid_entry_id)),
                strings.get(R.string.repo_entry_not_found)
            )
        val db = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        val entry = db.rootGroup.allEntries().firstOrNull { it.id == uuid }
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_entry_not_found)),
                strings.get(R.string.repo_entry_not_found)
            )

        // 官方 KeePass MainForm_Functions 分流：回收站禁用，或条目父组即为回收站/位于回收站
        // 子树内（PwGroup.IsContainedIn 语义），一律物理删除并追加墓碑；否则软删移入回收站。
        val binGroup = resolveRecycleBinGroup(db)
        val entryParentId = entry.parentGroupId
        val parentInsideBin = binGroup != null &&
                entryParentId != null &&
                binGroup.subtreeContainsGroup(entryParentId)

        if (parentInsideBin || !db.recycleBinEnabled) {
            // 已在回收站内或禁用回收站：物理删除并记录 DeletedObject 墓碑
            databaseSession.deleteEntry(uuid)
            databaseSession.updateDatabaseMeta { cur ->
                val tombstone = DeletedObject(id = uuid, deletionTime = Instant.now())
                cur.copy(deletedObjects = cur.deletedObjects + tombstone)
            }
        } else {
            // 移入标准库内回收站组
            val targetBin = getOrCreateRecycleBinGroup()
            val moved = entry.copy(
                parentGroupId = targetBin.id,
                previousParentGroup = entry.parentGroupId,
                times = entry.times.copy(lastModificationTime = Instant.now())
            )
            databaseSession.deleteEntry(uuid)
            databaseSession.saveEntry(moved)
        }
        return persistSession()
    }

    /** 删除分组：整组（含子内容）移入回收站或物理删除+墓碑；回收站组自身删除为无操作 */
    suspend fun deleteGroup(id: String): KdbxResult<Unit> {
        val uuid = parseKdbxUuidOrNull(id)
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_invalid_group_id)),
                strings.get(R.string.repo_group_not_found)
            )
        val db = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        if (uuid == db.recycleBinUuid) {
            return KdbxResult.Success(Unit)
        }
        val targetGroup = db.rootGroup.allGroups().firstOrNull { it.id == uuid }
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_group_not_found)),
                strings.get(R.string.repo_group_not_found)
            )

        // 官方 KeePass 分组删除分流（MainForm_Functions DeleteGroup）：
        //  - 回收站禁用；或
        //  - 目标组即为回收站、或位于回收站子树内（bin.subtreeContains(target)）；或
        //  - 目标组包含回收站（target.subtreeContains(bin)，官方 pgRecycleBin.IsContainedIn(pg)）
        // 三者任一成立即物理删除整组并追加墓碑。尤其「目标包含回收站」若走软删，会把回收站
        // 连同整棵子树移入自身，saveGroup 随即找不到父组而静默丢库——此即 ISSUE-P1-03 数据完整性修复点。
        val binGroup = resolveRecycleBinGroup(db)
        val binRelated = binGroup != null &&
                (binGroup.subtreeContainsGroup(targetGroup.id) || targetGroup.subtreeContainsGroup(binGroup.id))

        if (binRelated || !db.recycleBinEnabled) {
            // 已在回收站内（或回收站被禁用）：物理删除整组。
            // ISSUE-P2-284：墓碑必须覆盖**整棵子树**（组自身 + 全部子孙条目 / 子组）——
            // 只为组自身立碑时，他端仍持有的子对象在合并中按「修改胜」复活
            // （父组墓碑按 UUID 精确匹配，不覆盖子项）；口径对齐官方
            // `PwGroup.DeleteAllObjects`（`PwGroup.cs:1367-1386`，为每个子孙立碑），
            // 并与本类 `emptyRecycleBin` 复用同一实现（禁两份）。
            val subtreeEntries = targetGroup.allEntries().map { it.id }.toSet()
            val subtreeGroups = targetGroup.allGroups().map { it.id }.toSet()
            permanentlyDeleteObjects(entryIds = subtreeEntries, groupIds = subtreeGroups)
        } else {
            // 标准回收站语义：整组（含子内容）移入库内回收站组，不产生墓碑
            val targetBin = getOrCreateRecycleBinGroup()
            val moved = targetGroup.copy(
                parentGroupId = targetBin.id,
                previousParentGroup = targetGroup.parentGroupId,
                times = targetGroup.times.copy(lastModificationTime = Instant.now())
            )
            databaseSession.deleteGroup(uuid)
            databaseSession.saveGroup(moved)
        }
        return persistSession()
    }

    /** 还原条目：移回 previousParentGroup（父组已被删时回退根组），并清空回退标记 */
    suspend fun restoreEntry(id: String): KdbxResult<Unit> {
        val uuid = parseKdbxUuidOrNull(id)
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_invalid_entry_id)),
                strings.get(R.string.repo_entry_not_found)
            )
        val db = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        val entry = db.rootGroup.allEntries().firstOrNull { it.id == uuid }
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_entry_not_found)),
                strings.get(R.string.repo_entry_not_found)
            )

        val allGroups = db.rootGroup.allGroups()
        val targetParentId = entry.previousParentGroup?.takeIf { prevId -> allGroups.any { it.id == prevId } }
            ?: db.rootGroup.id

        val restored = entry.copy(
            parentGroupId = targetParentId,
            previousParentGroup = null,
            times = entry.times.copy(lastModificationTime = Instant.now())
        )
        databaseSession.deleteEntry(uuid)
        databaseSession.saveEntry(restored)
        return persistSession()
    }

    /**
     * 清空回收站：断点9 整改——必须覆盖其子分组——递归收集子树内全部条目，
     * 子分组本身物理删除并记录墓碑（KeePassDX 语义：回收站清空即整棵清空）
     */
    suspend fun emptyRecycleBin(): KdbxResult<Unit> {
        val db = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        val binGroup = primaryRecycleBinRoot(db) ?: return KdbxResult.Success(Unit)

        val entriesToDelete = binGroup.allEntries()
        val subgroupsToDelete = binGroup.allGroups().filter { it.id != binGroup.id }
        if (entriesToDelete.isEmpty() && subgroupsToDelete.isEmpty()) {
            return KdbxResult.Success(Unit)
        }

        val entryIds = entriesToDelete.map { it.id }.toSet()
        permanentlyDeleteObjects(
            entryIds = entryIds,
            groupIds = subgroupsToDelete.map { it.id }.toSet()
        )
        return persistSession()
    }

    /**
     * 物理删除 + **逐对象墓碑**的单一实现（`ISSUE-P2-284` AC①：组硬删除与清空回收站共用，
     * 禁止两份墓碑口径漂移）。
     *
     * 先批量删条目、再逐组摘除（父组摘除后其子组的删除为安全 no-op），最后一次性追加
     * 全部墓碑——墓碑集合 = 条目集 ∪ 组集，与被删对象**一一对应**（官方
     * `PwGroup.DeleteAllObjects` 同口径：每个子孙对象各立一碑）。
     */
    private suspend fun permanentlyDeleteObjects(entryIds: Set<KdbxUuid>, groupIds: Set<KdbxUuid>) {
        if (entryIds.isNotEmpty()) {
            databaseSession.batchDeleteEntries(entryIds)
        }
        for (groupId in groupIds) {
            databaseSession.deleteGroup(groupId)
        }
        databaseSession.updateDatabaseMeta { cur ->
            val now = Instant.now()
            val tombstones = entryIds.map { DeletedObject(it, now) } + groupIds.map { DeletedObject(it, now) }
            cur.copy(deletedObjects = cur.deletedObjects + tombstones)
        }
    }

    /** 批量删除：逐条按回收站语义分流（移入回收站 / 物理删除+墓碑），最后统一落盘 */
    suspend fun batchDeleteEntries(entryIds: Set<String>): KdbxResult<Unit> {
        val db = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        val binGroup = resolveRecycleBinGroup(db)
        val allEntries = db.rootGroup.allEntries().associateBy { it.id.toHexString() }

        val toPermanentDelete = mutableSetOf<KdbxUuid>()
        val toMoveToBin = mutableListOf<KdbxEntry>()

        for (id in entryIds) {
            val entry = allEntries[id] ?: continue
            // 官方分流（同 deleteEntry）：父组即为回收站或位于回收站子树内 → 物理删除
            val entryParentId = entry.parentGroupId
            val parentInsideBin = binGroup != null &&
                    entryParentId != null &&
                    binGroup.subtreeContainsGroup(entryParentId)

            if (parentInsideBin || !db.recycleBinEnabled) {
                toPermanentDelete.add(entry.id)
            } else {
                toMoveToBin.add(entry)
            }
        }

        if (toMoveToBin.isNotEmpty()) {
            val targetBin = getOrCreateRecycleBinGroup()
            for (e in toMoveToBin) {
                val moved = e.copy(
                    parentGroupId = targetBin.id,
                    previousParentGroup = e.parentGroupId,
                    times = e.times.copy(lastModificationTime = Instant.now())
                )
                databaseSession.deleteEntry(e.id)
                databaseSession.saveEntry(moved)
            }
        }

        if (toPermanentDelete.isNotEmpty()) {
            databaseSession.batchDeleteEntries(toPermanentDelete)
            databaseSession.updateDatabaseMeta { cur ->
                val tombstones = toPermanentDelete.map { DeletedObject(it, Instant.now()) }
                cur.copy(deletedObjects = cur.deletedObjects + tombstones)
            }
        }

        return persistSession()
    }

    /** 懒创建/定位库内回收站组：meta UUID 命中 → 按名匹配（兼容 KeePass 官方 "Recycle Bin"）→ 新建 */
    suspend fun getOrCreateRecycleBinGroup(): KdbxGroup {
        val db = databaseSession.databaseFlow.first()
            ?: throw IllegalStateException(strings.get(R.string.repo_not_unlocked_state))

        // ISSUE-P2-341：定位口径收拢到 [primaryRecycleBinRoot]（UUID 优先、其次按名首命中），
        // 不再在此重复第三份 uuid/name 匹配。原「按名命中则回填 Meta」的行为**逐条保留**：
        // UUID 命中时 `binUuid == existing.id` ⇒ 不写 Meta；按名命中时才回填。
        val binUuid = db.recycleBinUuid
        primaryRecycleBinRoot(db)?.let { existing ->
            if (binUuid == null || binUuid != existing.id) {
                databaseSession.updateDatabaseMeta {
                    it.copy(
                        recycleBinUuid = existing.id,
                        recycleBinEnabled = true,
                        recycleBinChanged = Instant.now()
                    )
                }
            }
            return existing
        }

        val newBinGroup = KdbxGroup(
            id = KdbxUuid.random(),
            parentGroupId = db.rootGroup.id,
            name = RealVaultRepository.RECYCLE_BIN_NAME,
            iconId = 43,
            // 官方 EnsureRecycleBin：回收站组禁用 AutoType 与搜索（PwIcon.TrashBin=43）
            enableAutoType = false,
            enableSearching = false
        )
        databaseSession.saveGroup(newBinGroup)
        databaseSession.updateDatabaseMeta {
            it.copy(
                recycleBinUuid = newBinGroup.id,
                recycleBinEnabled = true,
                recycleBinChanged = Instant.now()
            )
        }
        return newBinGroup
    }

    /**
     * 只读定位当前回收站组（不创建、不改 Meta）。
     *
     * `ISSUE-P2-341`：口径收拢到 [primaryRecycleBinRoot]（UUID 优先、其次按名首命中），
     * 不再在此重复一份 `firstOrNull { uuid || name }` —— 旧写法在**遍历顺序**上会先撞上同名组，
     * 使「Meta 指向 A、但库里另有一个叫回收站的 B」时挑错组。
     */
    private fun resolveRecycleBinGroup(db: KdbxDatabase): KdbxGroup? = primaryRecycleBinRoot(db)
}

/**
 * 回收站组判定的**唯一真相源**（`ISSUE-P2-341`，2026-09-27）。
 *
 * ## 为何存在
 *
 * 本项目里"已删除"不是条目上的位，而是**组子树**：`KdbxEntry` 无 `isDeleted` / `deleteTime`
 * （`core/.../KdbxEntry.kt:9-27`），移入回收站只是把 `parentGroupId` 改到 bin 组
 * （[RecycleBinCoordinator.deleteEntry]）。而此前全仓**只有列表页的搜索分支**过滤了它，
 * 验证器列表、CM 通行密钥候选、autofill 候选、断言与填充执行侧**一律捞出整树**
 * ⇒ 用户删掉的凭据仍然可用。更糟的是"什么算 bin"曾有**四套各不相同**的实现
 * （`resolveRecycleBinGroup` / `emptyRecycleBin` / `getOrCreateRecycleBinGroup` /
 * `VaultGroupCoordinator.groupsFlow`），再多写一份"供给面专用过滤"只会让两面迟早分叉
 * （用户所见再次不一致）。本函数是收敛后的**唯一**口径。
 *
 * ## 口径（`ISSUE-P3-470` AC① 收敛：判定规则随 Meta `RecycleBinEnabled` 门控）
 *
 * * **`recycleBinEnabled = false` ⇒ 空表**：官方语义下关闭开关即「删除＝永久删除」，
 *   此时**不凭任何依据**判「已删」—— 残留同名组或历史 UUID 命中组里的条目都是**活条目**，
 *   不得被判为已删而从供给面消失（`ISSUE-P3-470` 锁定的缺陷）。
 * * **`recycleBinEnabled = true` ⇒ 以 `RecycleBinUuid` 命中为准**；UUID **缺失**时（懒创建
 *   未写 UUID 的第三方库）才允许组名（`回收站` / `Recycle Bin`）兜底。
 * * **含全部后代**：bin 的子组里的条目同样是已删条目（子组名一般不叫回收站，故必须展开子树）。
 * * **根组永不视为回收站**：否则一旦根组命名撞上，整库条目都会被判为"已删"、
 *   从所有供给面消失 —— 那会把 fail-closed 变成 fail-whole-vault。
 *
 * ## 与 `primaryRecycleBinRoot` 的分工
 *
 * 本函数回答「**哪些条目算已删**」（供给面判定，含后代展开）；`primaryRecycleBinRoot`
 * 回答「**回收站组在哪**」（删除 / 还原 / 清空的定位）。两者的输入不同（前者看条目归属，
 * 后者要落地移动），故口径各自独立：删除主流程对 `recycleBinEnabled = false` 已在
 * 各个删除分支显式走物理删除（`deleteEntry` / `deleteGroup` / `batchDeleteEntries`），
 * 不依赖本函数。
 *
 * @return 回收站组及其全部后代（按 id 去重；UUID 缺失且多个同名 bin 时全部纳入）
 */
internal fun recycleBinGroupsOf(db: KdbxDatabase): List<KdbxGroup> {
    val roots = recycleBinRootsOf(db)
    if (roots.isEmpty()) return emptyList()
    val seen = mutableSetOf<KdbxUuid>()
    val out = mutableListOf<KdbxGroup>()
    for (candidate in roots) {
        // KdbxGroup.allGroups() 含自身（emptyRecycleBin 即以 `filter { it.id != binGroup.id }` 取后代）
        for (descendant in candidate.allGroups()) {
            if (seen.add(descendant.id)) out.add(descendant)
        }
    }
    return out
}

/**
 * 供给面「回收站根组」候选（不含后代）——`ISSUE-P3-470` AC① 的唯一收口。
 *
 * * `recycleBinEnabled = false` ⇒ 空表（不凭任何依据判已删）。
 * * `= true` ⇒ 有 `RecycleBinUuid` 只认 UUID 命中；UUID 缺失才按组名兜底。
 */
private fun recycleBinRootsOf(db: KdbxDatabase): List<KdbxGroup> {
    if (!db.recycleBinEnabled) return emptyList()
    val root = db.rootGroup
    val binUuid = db.recycleBinUuid
    return root.allGroups().filter { group ->
        group.id != root.id &&
            if (binUuid != null) group.id == binUuid else isRecycleBinName(group.name)
    }
}

/** 回收站子树的全部组 id（判定条目是否"已在回收站内"用；条目 `parentGroupId` 落在此集合内即为已删）。 */
internal fun recycleBinGroupIdsOf(db: KdbxDatabase): Set<KdbxUuid> =
    recycleBinGroupsOf(db).mapTo(mutableSetOf()) { it.id }

/** 主回收站**根**组：Meta UUID 命中优先，其次按名首命中；无则 null（不含后代）。 */
internal fun primaryRecycleBinRoot(db: KdbxDatabase): KdbxGroup? {
    val root = db.rootGroup
    val binUuid = db.recycleBinUuid
    val roots = root.allGroups().filter { group ->
        group.id != root.id && (group.id == binUuid || isRecycleBinName(group.name))
    }
    return roots.firstOrNull { binUuid != null && it.id == binUuid } ?: roots.firstOrNull()
}

/** 回收站组的官方命名集合（本地化前 `回收站`，外部 KeePass 库多为 `Recycle Bin`）。 */
internal fun isRecycleBinName(name: String): Boolean =
    name == RealVaultRepository.RECYCLE_BIN_NAME || name.equals("Recycle Bin", ignoreCase = true)
