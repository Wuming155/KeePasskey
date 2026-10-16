package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid

/**
 * `ISSUE-P1-631`（分组「双方保留」）：把分叉的分组复制为**同父兄弟节点**，分组与其内条目全部换新 UUID。
 *
 * 为什么需要复制整棵子树而不能只复制分组节点：分组「双方保留」要表达的是「本地这条分组与磁盘那条
 * 分组都还在」。只复制空壳会让磁盘侧的名字挂在一个没有内容的组上，用户看到的仍是「数据被搬走了」。
 * 因此与条目侧 `ConflictResolutionChoice.DUPLICATE_BOTH` 同口径——复制的是**整个对象**（分组即含子树）。
 *
 * **UUID 全局唯一是硬约束**（KDBX 要求，且同 UUID 在应用回树时会命中同一槽位而互相覆盖）：
 * 复制体上的分组 UUID、条目 UUID 全部重生成，条目 history 一并指向新条目 UUID
 * （KDBX 语义：history 条目的 UUID 与其父条目相同），`previousParentGroup` 若指向**子树内部**
 * 的分组则同步重映射、指向子树外部则保持原值（外部组未被复制，引用仍然有效）。
 *
 * **不做**：不产生墓碑（原对象仍在，删除语义不适用）；不复制库级 Meta（分组复制不改变 Meta）。
 */
object KdbxGroupDuplicator {

    /**
     * 返回插入复制体后的**新根**；[targetId] 不存在、为根组（无父可挂）或树内出现重复 UUID 时返回 null
     * ——调用方 fail-closed，绝不静默跳过冲突。
     *
     * @param resolveTargetFields 施加在**复制体根节点**上的字段重裁（取磁盘侧值），以三方合并产物为底版
     *   （与 `KdbxGroupConflictResolver.resolveFieldsOnly` 同形）
     * @param newName 复制体根节点的组名（带冲突副本后缀，用户可辨识）
     */
    fun duplicateSubtreeAsSibling(
        root: KdbxGroup,
        targetId: KdbxUuid,
        newName: String,
        resolveTargetFields: (KdbxGroup) -> KdbxGroup,
        newId: () -> KdbxUuid = { KdbxUuid.random() }
    ): KdbxGroup? {
        if (root.id == targetId) return null

        var target: KdbxGroup? = null
        fun locate(group: KdbxGroup) {
            if (target != null) return
            for (sub in group.subgroups) {
                if (sub.id == targetId) {
                    target = sub
                    return
                }
                locate(sub)
                if (target != null) return
            }
        }
        locate(root)
        val original = target ?: return null

        // 预扫：为子树内全部分组与条目分配新 UUID（条目 history 复用父条目 UUID）
        val groupRemap = HashMap<KdbxUuid, KdbxUuid>()
        val entryRemap = HashMap<KdbxUuid, KdbxUuid>()
        for (group in original.allGroups()) groupRemap[group.id] = newId()
        for (entry in original.allEntries()) entryRemap[entry.id] = newId()

        fun remapGroupRef(id: KdbxUuid?): KdbxUuid? = id?.let { groupRemap[it] ?: it }

        fun duplicateEntry(entry: KdbxEntry, newParentId: KdbxUuid): KdbxEntry {
            val newEntryId = entryRemap.getValue(entry.id)
            return entry.copy(
                id = newEntryId,
                parentGroupId = newParentId,
                // KDBX：history 条目的 UUID 与父条目一致 ⇒ 随父条目一并换新
                history = entry.history.map { it.copy(id = newEntryId, parentGroupId = newParentId) },
                previousParentGroup = remapGroupRef(entry.previousParentGroup)
            )
        }

        fun duplicateGroup(group: KdbxGroup): KdbxGroup {
            val newGroupId = groupRemap.getValue(group.id)
            return group.copy(
                id = newGroupId,
                entries = group.entries.map { duplicateEntry(it, newGroupId) },
                subgroups = group.subgroups.map { duplicateGroup(it) },
                previousParentGroup = remapGroupRef(group.previousParentGroup)
            )
        }

        val duplicated = resolveTargetFields(duplicateGroup(original))
            .copy(name = newName, parentGroupId = original.parentGroupId)

        // 挂为同父兄弟：在树中找到持有该分组的父节点，把复制体追加进其 subgroups
        var inserted = false
        fun insert(group: KdbxGroup): KdbxGroup {
            if (inserted) return group
            if (group.subgroups.any { it.id == targetId }) {
                inserted = true
                return group.copy(subgroups = group.subgroups + duplicated)
            }
            var referenced: MutableList<KdbxGroup>? = null
            group.subgroups.forEachIndexed { index, sub ->
                if (inserted) return@forEachIndexed
                val updated = insert(sub)
                if (updated !== sub) {
                    val list = referenced ?: group.subgroups.toMutableList().also { referenced = it }
                    list[index] = updated
                }
            }
            return if (referenced == null) group else group.copy(subgroups = referenced)
        }

        val result = insert(root)
        return if (inserted) result else null
    }
}
