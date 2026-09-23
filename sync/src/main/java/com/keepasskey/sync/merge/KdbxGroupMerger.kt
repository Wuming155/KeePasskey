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
                    // ISSUE-P2-284：墓碑存在时按官方口径比时间（修改晚于删除时刻才复活）；
                    // 无墓碑时保持「修改方胜」旧口径
                    val reRecreated = ld != null && rModTime.isAfter(ld.deletionTime)
                    val rModified = ld == null && isGroupModified(bg, rg)
                    if (reRecreated || rModified) {
                        // 重建胜（墓碑在且修改更晚）/ 无碑时的修改方胜
                        survivingGroups[groupId] = rg
                    }
                }
                isRemoteGroupDeleted && lg != null -> {
                    val lModTime = lg.times.lastModificationTime
                    // ISSUE-P2-284：同上的镜像分支
                    val leRecreated = rd != null && lModTime.isAfter(rd.deletionTime)
                    val lModified = rd == null && isGroupModified(bg, lg)
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
     *
     * `ISSUE-P3-165`：判定由「**逐组**上溯整条父链 + 每组一个 `visited` 集」改为
     * **一次函数图染色**——原实现对每个分组都向上走到根 / 断链，链状退化结构
     * （**远端可构造**）下为 `O(G × 深度)`、最坏 `O(G²)`，且每组各分配一个 `Set`。
     *
     * 判定口径**逐字保持**：`g` 需挂回根组 ⟺ 父链为空 / 指向根 / 指向不存在的分组，
     * 或从 `g` 沿**原始**父链上溯会重现某个节点（即「在环上」或「其父链进入某个环」）。
     * 各组的判定均基于**原始**链接、互不影响 ⇒ 结论与逐组上溯**逐项等价**。
     * 复杂度降为 `O(G)`（每个节点至多被走一次，路径复用同一缓冲）。
     */
    fun sanitizeParentLinks(
        survivingGroups: Map<KdbxUuid, KdbxGroup>,
        rootId: KdbxUuid
    ): Map<KdbxUuid, KdbxGroup> {
        val cyclic = cyclicGroups(survivingGroups, rootId)
        val sanitizedGroups = mutableMapOf<KdbxUuid, KdbxGroup>()
        for ((gid, g) in survivingGroups) {
            val targetParent = g.parentGroupId
            if (targetParent == null || targetParent == rootId ||
                !survivingGroups.containsKey(targetParent) || gid in cyclic
            ) {
                sanitizedGroups[gid] = g.copy(parentGroupId = rootId)
            } else {
                sanitizedGroups[gid] = g
            }
        }
        return sanitizedGroups
    }

    /**
     * 单趟染色求「需挂回根组」的分组集合（`ISSUE-P3-165`）。
     *
     * 状态：`ON_PATH` = 在**当前**路径上；`CLEAN` = 已判定为**不**进入环；
     * `CYCLIC` = 已判定为**进入**环（含在环上）。路径上命中 `CYCLIC` 或 `ON_PATH`
     * 都意味着该路径**全体**节点均进入某个环 —— 与逐组上溯的「重现即判环」判据一致。
     */
    private fun cyclicGroups(
        survivingGroups: Map<KdbxUuid, KdbxGroup>,
        rootId: KdbxUuid
    ): Set<KdbxUuid> {
        val onPath = 1
        val clean = 2
        val cyclicState = 3
        val state = HashMap<KdbxUuid, Int>(survivingGroups.size)
        val path = ArrayList<KdbxUuid>()
        val cyclic = mutableSetOf<KdbxUuid>()

        for (start in survivingGroups.keys) {
            // 已判定过的节点（clean 或 cyclic）无需再走：其结果已确定
            if (state.containsKey(start)) continue
            path.clear()
            var curr: KdbxUuid? = start
            var hitsCycle = false
            while (true) {
                if (curr == null || curr == rootId || !survivingGroups.containsKey(curr)) break
                when (state[curr]) {
                    clean -> break
                    cyclicState, onPath -> {
                        hitsCycle = true
                        break
                    }

                    else -> {
                        state[curr] = onPath
                        path.add(curr)
                        curr = survivingGroups[curr]?.parentGroupId
                    }
                }
            }
            val mark = if (hitsCycle) cyclicState else clean
            for (id in path) {
                state[id] = mark
                if (hitsCycle) cyclic.add(id)
            }
        }
        return cyclic
    }

    /**
     * 递归组装合并后分组树：根组取本地/远端较晚的变更时间戳，其余节点取已净化分组。
     *
     * `ISSUE-P3-165`：本递归**未设自有深度上限**，依据是**上游已封顶**——
     * 两侧树均由 `KdbxXmlParser` 解析而来，其 `MAX_XML_DEPTH = 64` 对嵌套深度 fail-closed 封顶
     * （分组嵌套是 XML 嵌套的一部分），且 `subgroupsByParent` 由**净化后**的父链构建
     * （环与失链一律挂回根组，只会缩短链）⇒ 递归深度实际有界、无可达的栈溢出路径。
     *
     * **依赖（须随上游同步）**：若日后放宽 / 移除该解析器深度上限，或引入不经该解析器的树来源
     * （其它格式导入、插件通道等），**必须**在此补自有深度上限或改显式栈，
     * 超限按既有失败语义处理（fail-closed），**不得**静默截断。
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

    /**
     * ISSUE-P2-279：分组标量字段的**单一词汇表**——[isGroupModified] 的判修改集与
     * [mergeGroupsBothModified] 的实际合并集由本表驱动，禁止两处各写一份字段清单。
     * 此前 `customIconId` 判定进修改集却不进合并集（`local.copy` 原样保留本地值）⇒
     * 他端改分组图标后同步回本端时静默丢失（本仓无分组 `customIconId` 写入者，
     * 需 KeePassXC / 官方桌面端改组图标方可达，核实见条目正文）。
     */
    private class GroupFieldSpec(
        val read: (KdbxGroup) -> Any?,
        val write: (KdbxGroup, Any?) -> KdbxGroup
    )

    private val MERGED_GROUP_FIELDS: List<GroupFieldSpec> = listOf(
        GroupFieldSpec({ it.name }, { g, v -> g.copy(name = v as String) }),
        GroupFieldSpec({ it.notes }, { g, v -> g.copy(notes = v as String) }),
        GroupFieldSpec({ it.iconId }, { g, v -> g.copy(iconId = v as Int) }),
        GroupFieldSpec({ it.customIconId }, { g, v -> g.copy(customIconId = v as KdbxUuid?) }),
        GroupFieldSpec({ it.parentGroupId }, { g, v -> g.copy(parentGroupId = v as KdbxUuid?) })
    )

    private fun isGroupModified(base: KdbxGroup?, current: KdbxGroup): Boolean {
        if (base == null) return true
        // ISSUE-P2-279：判定与合并共用同一词汇表（禁两份清单）
        return MERGED_GROUP_FIELDS.any { it.read(base) != it.read(current) } ||
                base.times.lastModificationTime != current.times.lastModificationTime
    }

    /**
     * 双方均修改时的分组字段级合并：逐字段三方裁决——单侧变更取该侧，双侧同值取本地，
     * 双侧异值按最后修改时间（LWW）取胜方；时间戳取较晚者。
     * 词汇表见 [MERGED_GROUP_FIELDS]（ISSUE-P2-279 起含 `customIconId`）。
     */
    private fun mergeGroupsBothModified(
        base: KdbxGroup?,
        local: KdbxGroup,
        remote: KdbxGroup
    ): KdbxGroup {
        val lTime = local.times.lastModificationTime
        val rTime = remote.times.lastModificationTime

        var merged = local
        for (spec in MERGED_GROUP_FIELDS) {
            val bv = base?.let(spec.read)
            val lv = spec.read(local)
            val rv = spec.read(remote)
            val winner = when {
                lv != bv && rv == bv -> lv
                lv == bv && rv != bv -> rv
                lv == rv -> lv
                else -> if (rTime.isAfter(lTime)) rv else lv
            }
            merged = spec.write(merged, winner)
        }

        val maxMod = if (rTime.isAfter(lTime)) rTime else lTime
        return merged.copy(times = local.times.copy(lastModificationTime = maxMod))
    }
}
