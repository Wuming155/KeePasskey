package com.keepasskey.sync.merge

import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid

/**
 * 分组级三方合并单元（自 [KdbxMerger] 结构性拆出，零行为变更）。
 *
 * 负责分组存活裁决、父子关系自愈（防死循环/断链）与合并后分组树递归组装；
 * 条目与墓碑处理分别由 [KdbxEntryMerger] / [KdbxTombstoneMerger] 承担。
 */
internal object KdbxGroupMerger {

    /**
     * 按 UUID 逐组裁决三方分组存活集合：
     * 双方均删则消亡；单边删除时仅当对端「删除后重建」或「相对 base 已修改」方胜；
     * 双方均改则走 [mergeGroupsBothModified] 字段级合并；单侧新建（base 与墓碑均无记录）保留。
     */
    fun mergeSurvivingGroups(
        allGroupUuids: Set<KdbxUuid>,
        baseGroups: Map<KdbxUuid, KdbxGroup>,
        localGroups: Map<KdbxUuid, KdbxGroup>,
        remoteGroups: Map<KdbxUuid, KdbxGroup>,
        localDeleted: Map<KdbxUuid, DeletedObject>,
        remoteDeleted: Map<KdbxUuid, DeletedObject>
    ): Map<KdbxUuid, KdbxGroup> {
        val survivingGroups = mutableMapOf<KdbxUuid, KdbxGroup>()

        for (groupId in allGroupUuids) {
            val bg = baseGroups[groupId]
            val lg = localGroups[groupId]
            val rg = remoteGroups[groupId]
            val ld = localDeleted[groupId]
            val rd = remoteDeleted[groupId]

            val isLocalGroupDeleted = lg == null && (ld != null || bg != null)
            val isRemoteGroupDeleted = rg == null && (rd != null || bg != null)

            when {
                lg == null && rg == null -> {
                    // 双方均已删除
                }
                isLocalGroupDeleted && rg != null -> {
                    val rModTime = rg.times.lastModificationTime
                    val reRecreated = ld != null && rModTime.isAfter(ld.deletionTime)
                    val rModified = isGroupModified(bg, rg)
                    if (reRecreated || rModified) {
                        // 修改方胜 / 删除后重建胜
                        survivingGroups[groupId] = rg
                    }
                }
                isRemoteGroupDeleted && lg != null -> {
                    val lModTime = lg.times.lastModificationTime
                    val leRecreated = rd != null && lModTime.isAfter(rd.deletionTime)
                    val lModified = isGroupModified(bg, lg)
                    if (leRecreated || lModified) {
                        survivingGroups[groupId] = lg
                    }
                }
                lg != null && rg != null -> {
                    val lModified = isGroupModified(bg, lg)
                    val rModified = isGroupModified(bg, rg)
                    when {
                        !lModified && !rModified -> survivingGroups[groupId] = lg
                        lModified && !rModified -> survivingGroups[groupId] = lg
                        !lModified && rModified -> survivingGroups[groupId] = rg
                        else -> survivingGroups[groupId] = mergeGroupsBothModified(bg, lg, rg)
                    }
                }
                lg != null && rg == null -> {
                    // 单侧新建分组（base 与墓碑中均无记录）：新建方保留。
                    // 此分支必须在墓碑分支之后——带墓碑的单侧缺失已由上方删除分支裁决
                    survivingGroups[groupId] = lg
                }
                lg == null && rg != null -> {
                    survivingGroups[groupId] = rg
                }
            }
        }

        return survivingGroups
    }

    /**
     * 父子关系自愈：父链失链/指向根组/构成环路时统一挂载至根组。
     */
    fun sanitizeParentLinks(
        survivingGroups: Map<KdbxUuid, KdbxGroup>,
        rootId: KdbxUuid
    ): Map<KdbxUuid, KdbxGroup> {
        val sanitizedGroups = mutableMapOf<KdbxUuid, KdbxGroup>()
        for ((gid, g) in survivingGroups) {
            val targetParent = g.parentGroupId
            if (targetParent == null || targetParent == rootId || !survivingGroups.containsKey(targetParent)) {
                sanitizedGroups[gid] = g.copy(parentGroupId = rootId)
            } else {
                // 环路检测
                var curr = targetParent
                var hasCycle = false
                val visited = mutableSetOf(gid)
                while (curr != null && curr != rootId && survivingGroups.containsKey(curr)) {
                    if (!visited.add(curr)) {
                        hasCycle = true
                        break
                    }
                    curr = survivingGroups[curr]?.parentGroupId
                }
                if (hasCycle) {
                    sanitizedGroups[gid] = g.copy(parentGroupId = rootId)
                } else {
                    sanitizedGroups[gid] = g
                }
            }
        }
        return sanitizedGroups
    }

    /**
     * 递归组装合并后分组树：根组取本地/远端较晚的变更时间戳，其余节点取已净化分组。
     */
    fun assembleGroupTree(
        rootId: KdbxUuid,
        localRoot: KdbxGroup,
        remoteRoot: KdbxGroup,
        sanitizedGroups: Map<KdbxUuid, KdbxGroup>,
        entriesByParent: Map<KdbxUuid, List<KdbxEntry>>,
        subgroupsByParent: Map<KdbxUuid, List<KdbxGroup>>
    ): KdbxGroup {
        fun assembleGroup(gid: KdbxUuid): KdbxGroup {
            val rawGroup = if (gid == rootId) {
                val laterTimes = if (remoteRoot.times.lastModificationTime.isAfter(localRoot.times.lastModificationTime)) {
                    remoteRoot.times
                } else {
                    localRoot.times
                }
                localRoot.copy(times = laterTimes)
            } else {
                sanitizedGroups[gid]!!
            }

            val childEntries = entriesByParent[gid].orEmpty()
            val childGroups = (subgroupsByParent[gid].orEmpty()).map { assembleGroup(it.id) }

            return rawGroup.copy(
                entries = childEntries,
                subgroups = childGroups
            )
        }

        return assembleGroup(rootId)
    }

    private fun isGroupModified(base: KdbxGroup?, current: KdbxGroup): Boolean {
        if (base == null) return true
        return base.name != current.name ||
                base.notes != current.notes ||
                base.iconId != current.iconId ||
                base.customIconId != current.customIconId ||
                base.parentGroupId != current.parentGroupId ||
                base.times.lastModificationTime != current.times.lastModificationTime
    }

    private fun mergeGroupsBothModified(
        base: KdbxGroup?,
        local: KdbxGroup,
        remote: KdbxGroup
    ): KdbxGroup {
        val lTime = local.times.lastModificationTime
        val rTime = remote.times.lastModificationTime

        val bName = base?.name
        val name = when {
            local.name != bName && remote.name == bName -> local.name
            local.name == bName && remote.name != bName -> remote.name
            local.name == remote.name -> local.name
            else -> if (rTime.isAfter(lTime)) remote.name else local.name
        }

        val bNotes = base?.notes
        val notes = when {
            local.notes != bNotes && remote.notes == bNotes -> local.notes
            local.notes == bNotes && remote.notes != bNotes -> remote.notes
            local.notes == remote.notes -> local.notes
            else -> if (rTime.isAfter(lTime)) remote.notes else local.notes
        }

        val bIconId = base?.iconId
        val iconId = when {
            local.iconId != bIconId && remote.iconId == bIconId -> local.iconId
            local.iconId == bIconId && remote.iconId != bIconId -> remote.iconId
            else -> if (rTime.isAfter(lTime)) remote.iconId else local.iconId
        }

        val bParent = base?.parentGroupId
        val parentGroupId = when {
            local.parentGroupId != bParent && remote.parentGroupId == bParent -> local.parentGroupId
            local.parentGroupId == bParent && remote.parentGroupId != bParent -> remote.parentGroupId
            else -> if (rTime.isAfter(lTime)) remote.parentGroupId else local.parentGroupId
        }

        val maxMod = if (rTime.isAfter(lTime)) rTime else lTime
        val mergedTimes = local.times.copy(lastModificationTime = maxMod)

        return local.copy(
            name = name,
            notes = notes,
            iconId = iconId,
            parentGroupId = parentGroupId,
            times = mergedTimes
        )
    }
}
