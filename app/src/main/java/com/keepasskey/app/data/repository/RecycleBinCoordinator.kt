package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
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

        val binUuid = db.recycleBinUuid
        val isAlreadyInRecycle = (binUuid != null && entry.parentGroupId == binUuid) ||
                (entry.parentGroupId != null && db.rootGroup.allGroups().any {
                    it.id == entry.parentGroupId && (it.name == RealVaultRepository.RECYCLE_BIN_NAME || it.name.equals("Recycle Bin", ignoreCase = true))
                })

        if (isAlreadyInRecycle || !db.recycleBinEnabled) {
            // 已在回收站内或禁用回收站：物理删除并记录 DeletedObject 墓碑
            databaseSession.deleteEntry(uuid)
            databaseSession.updateDatabaseMeta { cur ->
                val tombstone = DeletedObject(id = uuid, deletionTime = Instant.now())
                cur.copy(deletedObjects = cur.deletedObjects + tombstone)
            }
        } else {
            // 移入标准库内回收站组
            val binGroup = getOrCreateRecycleBinGroup()
            val moved = entry.copy(
                parentGroupId = binGroup.id,
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

        val alreadyInsideBin = db.recycleBinUuid?.let { binUuid ->
            var parentId = targetGroup.parentGroupId
            while (parentId != null) {
                if (parentId == binUuid) return@let true
                parentId = db.rootGroup.allGroups().firstOrNull { it.id == parentId }?.parentGroupId
            }
            false
        } ?: false

        if (alreadyInsideBin || !db.recycleBinEnabled) {
            // 已在回收站内（或回收站被禁用）：物理删除整组并记录 DeletedObject 墓碑
            databaseSession.deleteGroup(uuid)
            databaseSession.updateDatabaseMeta { cur ->
                cur.copy(deletedObjects = cur.deletedObjects + DeletedObject(id = uuid, deletionTime = Instant.now()))
            }
        } else {
            // 标准回收站语义：整组（含子内容）移入库内回收站组，不产生墓碑
            val binGroup = getOrCreateRecycleBinGroup()
            val moved = targetGroup.copy(
                parentGroupId = binGroup.id,
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
        val binUuid = db.recycleBinUuid
        val binGroup = db.rootGroup.allGroups().firstOrNull {
            (binUuid != null && it.id == binUuid) || it.name == RealVaultRepository.RECYCLE_BIN_NAME || it.name.equals("Recycle Bin", ignoreCase = true)
        } ?: return KdbxResult.Success(Unit)

        val entriesToDelete = binGroup.allEntries()
        val subgroupsToDelete = binGroup.allGroups().filter { it.id != binGroup.id }
        if (entriesToDelete.isEmpty() && subgroupsToDelete.isEmpty()) {
            return KdbxResult.Success(Unit)
        }

        val entryIds = entriesToDelete.map { it.id }.toSet()
        if (entryIds.isNotEmpty()) {
            databaseSession.batchDeleteEntries(entryIds)
        }
        for (sub in subgroupsToDelete) {
            databaseSession.deleteGroup(sub.id)
        }
        databaseSession.updateDatabaseMeta { cur ->
            val tombstones = entryIds.map { DeletedObject(it, Instant.now()) } +
                    subgroupsToDelete.map { DeletedObject(it.id, Instant.now()) }
            cur.copy(deletedObjects = cur.deletedObjects + tombstones)
        }
        return persistSession()
    }

    /** 批量删除：逐条按回收站语义分流（移入回收站 / 物理删除+墓碑），最后统一落盘 */
    suspend fun batchDeleteEntries(entryIds: Set<String>): KdbxResult<Unit> {
        val db = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        val binUuid = db.recycleBinUuid
        val allGroups = db.rootGroup.allGroups()
        val allEntries = db.rootGroup.allEntries().associateBy { it.id.toHexString() }

        val toPermanentDelete = mutableSetOf<KdbxUuid>()
        val toMoveToBin = mutableListOf<KdbxEntry>()

        for (id in entryIds) {
            val entry = allEntries[id] ?: continue
            val isAlreadyInRecycle = (binUuid != null && entry.parentGroupId == binUuid) ||
                    (entry.parentGroupId != null && allGroups.any {
                        it.id == entry.parentGroupId && (it.name == RealVaultRepository.RECYCLE_BIN_NAME || it.name.equals("Recycle Bin", ignoreCase = true))
                    })

            if (isAlreadyInRecycle || !db.recycleBinEnabled) {
                toPermanentDelete.add(entry.id)
            } else {
                toMoveToBin.add(entry)
            }
        }

        if (toMoveToBin.isNotEmpty()) {
            val binGroup = getOrCreateRecycleBinGroup()
            for (e in toMoveToBin) {
                val moved = e.copy(
                    parentGroupId = binGroup.id,
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

        if (db.recycleBinUuid != null) {
            val existingBin = db.rootGroup.allGroups().firstOrNull { it.id == db.recycleBinUuid }
            if (existingBin != null) {
                return existingBin
            }
        }

        val candidate = db.rootGroup.subgroups.firstOrNull {
            it.name == RealVaultRepository.RECYCLE_BIN_NAME || it.name.equals("Recycle Bin", ignoreCase = true)
        } ?: db.rootGroup.allGroups().firstOrNull {
            it.name == RealVaultRepository.RECYCLE_BIN_NAME || it.name.equals("Recycle Bin", ignoreCase = true)
        }

        if (candidate != null) {
            databaseSession.updateDatabaseMeta {
                it.copy(
                    recycleBinUuid = candidate.id,
                    recycleBinEnabled = true,
                    recycleBinChanged = Instant.now()
                )
            }
            return candidate
        }

        val newBinGroup = KdbxGroup(
            id = KdbxUuid.random(),
            parentGroupId = db.rootGroup.id,
            name = RealVaultRepository.RECYCLE_BIN_NAME,
            iconId = 43
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
}
