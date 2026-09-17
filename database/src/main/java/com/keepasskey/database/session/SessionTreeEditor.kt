package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid

/**
 * KDBX 分组树纯变换（ISSUE-P3-31 批次 D 结构拆分；ISSUE-P3-156 路径复制与替换关系回报）。
 *
 * 自 [DatabaseSession] 原样抽出：全部为无副作用的 copy-on-write 树变换，
 * 不含任何会话状态读写，便于独立复用与审查。
 *
 * **路径复制契约（ISSUE-P3-156）**：变换只重建「从根到目标」的分组链，未命中的兄弟子树
 * **按同一对象引用原样返回**（不再逐层 `copy`）。这既消除了 O(分组数) 的对象复制，
 * 也是增量定点擦除的**正确性前提**——新树除替换位置外与旧树共享全部子树，
 * 故「旧树中下线、新树中存活」的敏感实例只可能来自被替换的那个节点
 * （见 [KdbxGroup.eraseSupersededSensitiveData]）。树变换的返回类型因此携带**替换关系**
 * （被替换下线的旧节点 + 同位置上线的节点），供调用方做 O(被替换节点) 的定点擦除。
 */
internal object SessionTreeEditor {

    fun updateOrAddEntry(group: KdbxGroup, entry: KdbxEntry): EntryEditResult {
        // ISSUE-P3-63：parentGroupId=null 的既定语义是「根组」（落树放置、同步合并均按
        // `?: group.id` 处理；XML 重新解析时亦按结构归属还原为根组 id）。但此前落树时
        // **不回写对象**，导致会话内存条目长期持有 null 父组——UI 投影按 groupId 过滤时
        // 根级条目（新建/导入）在会话内不可见，冷启动后才出现。此处落树即规范化，
        // 使内存模型与落盘解析模型保持同一取值。
        val normalized = if (entry.parentGroupId == null) entry.copy(parentGroupId = group.id) else entry
        val targetParentId = normalized.parentGroupId ?: group.id
        // 目标父组不在本树：原样返回**同一根实例**（原实现会整树重建，属纯浪费）
        return updateOrAddEntryIn(group, normalized, targetParentId)
            ?: EntryEditResult(group, replaced = null, replacement = normalized)
    }

    /**
     * 递归落树：命中目标父组即插入/替换并回报替换关系；未命中的兄弟子树按引用原样返回。
     * 返回 null 表示目标父组不在本子树内。分组 id 在树内唯一，故命中即返回（不再继续扫描
     * 其余子树）——既省遍历，也避免对同一 id 的多个节点重复落树。
     */
    private fun updateOrAddEntryIn(
        group: KdbxGroup,
        entry: KdbxEntry,
        targetParentId: KdbxUuid
    ): EntryEditResult? {
        if (group.id == targetParentId) {
            val existingIndex = group.entries.indexOfFirst { it.id == entry.id }
            if (existingIndex < 0) {
                return EntryEditResult(
                    root = group.copy(entries = group.entries + entry),
                    replaced = null,
                    replacement = entry
                )
            }
            val newEntries = group.entries.toMutableList()
            val replaced = newEntries.set(existingIndex, entry)
            return EntryEditResult(group.copy(entries = newEntries), replaced = replaced, replacement = entry)
        }
        for (index in group.subgroups.indices) {
            val result = updateOrAddEntryIn(group.subgroups[index], entry, targetParentId) ?: continue
            val newSubgroups = group.subgroups.toMutableList()
            newSubgroups[index] = result.root
            return EntryEditResult(group.copy(subgroups = newSubgroups), result.replaced, result.replacement)
        }
        return null
    }

    /**
     * 按 id 定位单条条目并就地变换（ISSUE-P3-157）：只重建「从根到命中位置」的分组链，
     * 未命中的兄弟子树按**同一实例**复用（路径复制契约，见类 KDoc）。
     *
     * [transform] 的产物即落树实例；它与命中的旧实例按 `copy` 语义共享未变更的字段 /
     * 自定义字段 / 附件 / 历史容器，故调用方据回报的替换关系做增量定点擦除时，
     * 只会清掉**真正下线**的敏感实例（见 [KdbxGroup.eraseSupersededSensitiveData]）。
     *
     * @return 命中时返回编辑结果（[EntryEditResult.replaced] 为命中的旧实例、
     *   [EntryEditResult.replacement] 为 [transform] 的产物）；[entryId] 不在本树内时返回 null，
     *   由调用方据此**零写入**（不得再退回整库变换）。
     */
    fun updateEntryById(
        root: KdbxGroup,
        entryId: KdbxUuid,
        transform: (KdbxEntry) -> KdbxEntry
    ): EntryEditResult? = updateEntryByIdIn(root, entryId, transform)

    /** 递归定位：语义同 [updateOrAddEntryIn]，但按**条目 id** 命中（不依赖 `parentGroupId`）。 */
    private fun updateEntryByIdIn(
        group: KdbxGroup,
        entryId: KdbxUuid,
        transform: (KdbxEntry) -> KdbxEntry
    ): EntryEditResult? {
        val index = group.entries.indexOfFirst { it.id == entryId }
        if (index >= 0) {
            val replaced = group.entries[index]
            val replacement = transform(replaced)
            val newEntries = group.entries.toMutableList()
            newEntries[index] = replacement
            return EntryEditResult(group.copy(entries = newEntries), replaced, replacement)
        }
        for (index in group.subgroups.indices) {
            val result = updateEntryByIdIn(group.subgroups[index], entryId, transform) ?: continue
            val newSubgroups = group.subgroups.toMutableList()
            newSubgroups[index] = result.root
            return EntryEditResult(group.copy(subgroups = newSubgroups), result.replaced, result.replacement)
        }
        return null
    }

    fun removeEntry(group: KdbxGroup, entryId: KdbxUuid): KdbxGroup {
        var changed = false
        val newEntries = if (group.entries.any { it.id == entryId }) {
            changed = true
            group.entries.filterNot { it.id == entryId }
        } else {
            group.entries
        }
        val newSubgroups = group.subgroups.map { sub ->
            val updated = removeEntry(sub, entryId)
            if (updated !== sub) changed = true
            updated
        }
        return if (changed) group.copy(entries = newEntries, subgroups = newSubgroups) else group
    }

    fun updateOrAddGroup(parent: KdbxGroup, groupToSave: KdbxGroup): GroupEditResult {
        // ISSUE-P3-63：与 updateOrAddEntry 同一规范化语义——null 父组的既定语义是根组
        // （放置逻辑与冷启动结构解析均按此处理），落树前把组自身及其子树成员的 null
        // 父组修正为结构真实父组 id（如模板分组及其条目以 null 构造后整组保存的场景）。
        val normalized = if (groupToSave.parentGroupId == null) {
            groupToSave.copy(parentGroupId = parent.id)
        } else {
            groupToSave
        }
        val targetParentId = normalized.parentGroupId ?: parent.id
        return updateOrAddGroupIn(parent, normalized, targetParentId)
            ?: GroupEditResult(parent, replaced = null, replacement = null)
    }

    /** 递归落组：命中目标父组即插入/替换并回报替换关系；语义同 [updateOrAddEntryIn]。 */
    private fun updateOrAddGroupIn(
        parent: KdbxGroup,
        groupToSave: KdbxGroup,
        targetParentId: KdbxUuid
    ): GroupEditResult? {
        if (parent.id == targetParentId) {
            val fixed = normalizeParentRefs(groupToSave)
            val existingIndex = parent.subgroups.indexOfFirst { it.id == fixed.id }
            if (existingIndex < 0) {
                return GroupEditResult(parent.copy(subgroups = parent.subgroups + fixed), null, fixed)
            }
            // P0-1 保护性合并：替换既有分组前保留其子项（详见 preserveChildrenIfMissing）
            val placed = preserveChildrenIfMissing(parent.subgroups[existingIndex], fixed)
            val newSubgroups = parent.subgroups.toMutableList()
            val replaced = newSubgroups.set(existingIndex, placed)
            return GroupEditResult(parent.copy(subgroups = newSubgroups), replaced = replaced, replacement = placed)
        }
        for (index in parent.subgroups.indices) {
            val result = updateOrAddGroupIn(parent.subgroups[index], groupToSave, targetParentId) ?: continue
            val newSubgroups = parent.subgroups.toMutableList()
            newSubgroups[index] = result.root
            return GroupEditResult(parent.copy(subgroups = newSubgroups), result.replaced, result.replacement)
        }
        return null
    }

    /**
     * 整根替换（saveGroup 的根分组分支）：经 [preserveChildrenIfMissing] 的 P0-1 保护后返回新根，
     * 并回报替换关系（旧根 → 新根）供调用方做增量定点擦除。
     */
    fun replaceRootGroup(current: KdbxGroup, incoming: KdbxGroup): GroupEditResult {
        val placed = preserveChildrenIfMissing(current, incoming)
        return GroupEditResult(placed, replaced = current, replacement = placed)
    }

    /**
     * ISSUE-P3-63：递归修正子树内所有 `parentGroupId=null` 的成员，使其携带结构真实父组 id。
     * 仅对「即将落树」的整组子树调用（updateOrAddGroup 的放置分支），根分组自身除外——
     * 根的 parentGroupId=null 是合法表达（无父组）。
     *
     * ISSUE-P3-156：无成员需要修正时**原样返回同一实例**（不再逐层重建）；这既省分配，
     * 也让「替换节点与新节点共享全部承载容器」的快速通道得以命中。
     */
    private fun normalizeParentRefs(group: KdbxGroup): KdbxGroup {
        var changed = false
        val normalizedEntries = group.entries.map { entry ->
            if (entry.parentGroupId == null) {
                changed = true
                entry.copy(parentGroupId = group.id)
            } else {
                entry
            }
        }
        val normalizedSubgroups = group.subgroups.map { sub ->
            val fixed = if (sub.parentGroupId == null) {
                changed = true
                sub.copy(parentGroupId = group.id)
            } else {
                sub
            }
            val recursed = normalizeParentRefs(fixed)
            if (recursed !== fixed) changed = true
            recursed
        }
        return if (changed) group.copy(entries = normalizedEntries, subgroups = normalizedSubgroups) else group
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
        var changed = false
        val newSubgroups = parent.subgroups.mapNotNull { sub ->
            if (sub.id == groupId) {
                changed = true
                null
            } else {
                val updated = removeGroup(sub, groupId)
                if (updated !== sub) changed = true
                updated
            }
        }
        return if (changed) parent.copy(subgroups = newSubgroups) else parent
    }
}

/**
 * 条目编辑结果（ISSUE-P3-156）：新树 + 本次**被替换下线**的旧条目（新增 / 目标父组不在本树
 * 时为 null）+ 同位置上线的条目。调用方据此做增量定点擦除：
 * `edit.replaced?.let { edit.root.eraseSupersededSensitiveData(it, edit.replacement) }`。
 */
internal class EntryEditResult(
    val root: KdbxGroup,
    val replaced: KdbxEntry?,
    val replacement: KdbxEntry?
)

/** 分组编辑结果：语义同 [EntryEditResult]，[replaced] / [replacement] 为分组。 */
internal class GroupEditResult(
    val root: KdbxGroup,
    val replaced: KdbxGroup?,
    val replacement: KdbxGroup?
)