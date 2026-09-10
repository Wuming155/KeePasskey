package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid

/**
 * KDBX 分组树纯变换（ISSUE-P3-31 批次 D 结构拆分）。
 *
 * 自 [DatabaseSession] 原样抽出：全部为无副作用的 copy-on-write 树变换，
 * 不含任何会话状态读写，便于独立复用与审查。
 */
internal object SessionTreeEditor {

    fun updateOrAddEntry(group: KdbxGroup, entry: KdbxEntry): KdbxGroup {
        val targetParentId = entry.parentGroupId ?: group.id
        if (group.id == targetParentId) {
            val existingIndex = group.entries.indexOfFirst { it.id == entry.id }
            val newEntries = group.entries.toMutableList()
            if (existingIndex >= 0) {
                newEntries[existingIndex] = entry
            } else {
                newEntries.add(entry)
            }
            return group.copy(entries = newEntries)
        }

        val newSubgroups = group.subgroups.map { sub ->
            updateOrAddEntry(sub, entry)
        }
        return group.copy(subgroups = newSubgroups)
    }

    fun removeEntry(group: KdbxGroup, entryId: KdbxUuid): KdbxGroup {
        val newEntries = group.entries.filter { it.id != entryId }
        val newSubgroups = group.subgroups.map { removeEntry(it, entryId) }
        return group.copy(entries = newEntries, subgroups = newSubgroups)
    }

    fun updateOrAddGroup(parent: KdbxGroup, groupToSave: KdbxGroup): KdbxGroup {
        val targetParentId = groupToSave.parentGroupId ?: parent.id
        if (parent.id == targetParentId) {
            val existingIndex = parent.subgroups.indexOfFirst { it.id == groupToSave.id }
            val newSubgroups = parent.subgroups.toMutableList()
            if (existingIndex >= 0) {
                // P0-1 保护性合并：替换既有分组前保留其子项（详见 preserveChildrenIfMissing）
                newSubgroups[existingIndex] = preserveChildrenIfMissing(parent.subgroups[existingIndex], groupToSave)
            } else {
                newSubgroups.add(groupToSave)
            }
            return parent.copy(subgroups = newSubgroups)
        }

        val newSubgroups = parent.subgroups.map { sub ->
            updateOrAddGroup(sub, groupToSave)
        }
        return parent.copy(subgroups = newSubgroups)
    }

    /**
     * P0-1 防御性合并：更新既有分组时，若调用方传入的新分组不携带任何子项
     * （entries 与 subgroups 均为空）而既有分组含有子项，则把既有子项原样并入新分组，
     * 确保重命名/改图标等仅更新元数据的保存路径不会清空既有分组的子条目与子分组。
     * 若新分组自身携带子项（如回收站移动、合并引擎回写等显式重建场景），则以其为准，不做合并。
     */
    fun preserveChildrenIfMissing(existing: KdbxGroup, incoming: KdbxGroup): KdbxGroup {
        val incomingHasChildren = incoming.entries.isNotEmpty() || incoming.subgroups.isNotEmpty()
        val existingHasChildren = existing.entries.isNotEmpty() || existing.subgroups.isNotEmpty()
        if (!incomingHasChildren && existingHasChildren) {
            return incoming.copy(entries = existing.entries, subgroups = existing.subgroups)
        }
        return incoming
    }

    fun removeGroup(parent: KdbxGroup, groupId: KdbxUuid): KdbxGroup {
        val newSubgroups = parent.subgroups
            .filter { it.id != groupId }
            .map { removeGroup(it, groupId) }
        return parent.copy(subgroups = newSubgroups)
    }
}
