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
        // ISSUE-P3-63：parentGroupId=null 的既定语义是「根组」（落树放置、同步合并均按
        // `?: group.id` 处理；XML 重新解析时亦按结构归属还原为根组 id）。但此前落树时
        // **不回写对象**，导致会话内存条目长期持有 null 父组——UI 投影按 groupId 过滤时
        // 根级条目（新建/导入）在会话内不可见，冷启动后才出现。此处落树即规范化，
        // 使内存模型与落盘解析模型保持同一取值。
        val normalized = if (entry.parentGroupId == null) entry.copy(parentGroupId = group.id) else entry
        val targetParentId = normalized.parentGroupId ?: group.id
        if (group.id == targetParentId) {
            val existingIndex = group.entries.indexOfFirst { it.id == normalized.id }
            val newEntries = group.entries.toMutableList()
            if (existingIndex >= 0) {
                newEntries[existingIndex] = normalized
            } else {
                newEntries.add(normalized)
            }
            return group.copy(entries = newEntries)
        }

        val newSubgroups = group.subgroups.map { sub ->
            updateOrAddEntry(sub, normalized)
        }
        return group.copy(subgroups = newSubgroups)
    }

    fun removeEntry(group: KdbxGroup, entryId: KdbxUuid): KdbxGroup {
        val newEntries = group.entries.filter { it.id != entryId }
        val newSubgroups = group.subgroups.map { removeEntry(it, entryId) }
        return group.copy(entries = newEntries, subgroups = newSubgroups)
    }

    fun updateOrAddGroup(parent: KdbxGroup, groupToSave: KdbxGroup): KdbxGroup {
        // ISSUE-P3-63：与 updateOrAddEntry 同一规范化语义——null 父组的既定语义是根组
        // （放置逻辑与冷启动结构解析均按此处理），落树前把组自身及其子树成员的 null
        // 父组修正为结构真实父组 id（如模板分组及其条目以 null 构造后整组保存的场景）。
        val normalized = if (groupToSave.parentGroupId == null) {
            groupToSave.copy(parentGroupId = parent.id)
        } else {
            groupToSave
        }
        val targetParentId = normalized.parentGroupId ?: parent.id
        if (parent.id == targetParentId) {
            val fixed = normalizeParentRefs(normalized)
            val existingIndex = parent.subgroups.indexOfFirst { it.id == fixed.id }
            val newSubgroups = parent.subgroups.toMutableList()
            if (existingIndex >= 0) {
                // P0-1 保护性合并：替换既有分组前保留其子项（详见 preserveChildrenIfMissing）
                newSubgroups[existingIndex] = preserveChildrenIfMissing(parent.subgroups[existingIndex], fixed)
            } else {
                newSubgroups.add(fixed)
            }
            return parent.copy(subgroups = newSubgroups)
        }

        val newSubgroups = parent.subgroups.map { sub ->
            updateOrAddGroup(sub, normalized)
        }
        return parent.copy(subgroups = newSubgroups)
    }

    /**
     * ISSUE-P3-63：递归修正子树内所有 `parentGroupId=null` 的成员，使其携带结构真实父组 id。
     * 仅对「即将落树」的整组子树调用（updateOrAddGroup 的放置分支），根分组自身除外——
     * 根的 parentGroupId=null 是合法表达（无父组）。
     */
    private fun normalizeParentRefs(group: KdbxGroup): KdbxGroup {
        val normalizedEntries = group.entries.map { entry ->
            if (entry.parentGroupId == null) entry.copy(parentGroupId = group.id) else entry
        }
        val normalizedSubgroups = group.subgroups.map { sub ->
            val fixed = if (sub.parentGroupId == null) sub.copy(parentGroupId = group.id) else sub
            normalizeParentRefs(fixed)
        }
        return group.copy(entries = normalizedEntries, subgroups = normalizedSubgroups)
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
