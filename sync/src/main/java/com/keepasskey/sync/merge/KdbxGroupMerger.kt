package com.keepasskey.sync.merge

import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid

/**
 * `ENG-20261111-05`：分组树组装产物。
 *
 * [rootConflicts] 是根组**普通属性**同字段双侧异值的冲突（词汇表与子组同源：
 * `MERGED_GROUP_FIELDS` + `KdbxGroupConflictKeys`）。根组不进入 [KdbxGroupMerger.mergeSurvivingGroups]
 * 的存活裁决（根 UUID 与身份固定），冲突必须在组装阶段带出，否则只能由 LWW 静默定案。
 */
internal class GroupTreeAssembly(
    val root: KdbxGroup,
    val rootConflicts: List<ConflictedGroupPair>
)

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
     *
     * `ISSUE-P1-631`：返回 [GroupMergeOutcome]——除存活集合外**必须**带出**同字段双侧异值**的
     * 冲突清单。此前本方法只给存活集合，调用方无从区分「双方改的都一样」与「双方真的分叉」，
     * 分组改名 / 备注 / 图标 / 父组 / 组级 customData 的一侧修改会被 LWW **静默**吞掉。
     * 字段级合并值仍按 LWW 给出（保证树结构完整、无冲突场景零行为变更），但冲突必须被显式登记，
     * 由调用方决定「阻断待裁决」还是「按策略重裁」。
     */
    fun mergeSurvivingGroups(
        allGroupUuids: Set<KdbxUuid>,
        baseGroups: Map<KdbxUuid, KdbxGroup>,
        localGroups: Map<KdbxUuid, KdbxGroup>,
        remoteGroups: Map<KdbxUuid, KdbxGroup>,
        localDeleted: Map<KdbxUuid, DeletedObject>,
        remoteDeleted: Map<KdbxUuid, DeletedObject>
    ): GroupMergeOutcome {
        val survivingGroups = mutableMapOf<KdbxUuid, KdbxGroup>()
        val groupConflicts = mutableListOf<ConflictedGroupPair>()

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
                        else -> {
                            val fieldMerge = mergeGroupsBothModified(bg, lg, rg)
                            survivingGroups[groupId] = fieldMerge.group
                            // `ISSUE-P1-631`：同字段双侧异值 ⇒ 登记冲突（值仍按 LWW 临时给出，
                            // 「静默」由调用方的阻断 / 裁决消除，而不是靠这里挑一侧）
                            if (fieldMerge.conflictingFields.isNotEmpty()) {
                                groupConflicts += ConflictedGroupPair(
                                    groupId = groupId.toHexString(),
                                    groupName = lg.name.ifBlank { rg.name },
                                    localGroup = lg,
                                    remoteGroup = rg,
                                    modifiedFields = fieldMerge.conflictingFields
                                )
                            }
                        }
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

        return GroupMergeOutcome(survivingGroups, groupConflicts)
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
     * `ENG-20261111-05`：根组**普通属性**（名称 / 备注 / 图标 / 自定义图标）与子组同口径参与
     * 字段级三方合并——整改前根分支只在 `localRoot` 上替换 times 与 customData，远端单侧改的
     * 根组名称等被静默丢弃，双侧分歧也不产生根组冲突。判据与取值复用子组同一条实现
     * [mergeGroupsBothModified]（词汇表 `MERGED_GROUP_FIELDS` 为单一真相源），仅保留根组既有的
     * times LWW（取较晚一侧整份时间戳）与 customData 三方合并口径；根 UUID 与树组装语义不变。
     * 同字段双侧异值经 [GroupTreeAssembly.rootConflicts] 交调用方并入 `MergeResult.groupConflicts`。
     * 前提：两侧根为同一实体（同一根 UUID）；不同 UUID 的远端根（`.kdbx` 并入的他库根组）
     * 不覆盖本地根身份属性，保持既有导入语义。
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
        baseRoot: KdbxGroup,
        localRoot: KdbxGroup,
        remoteRoot: KdbxGroup,
        sanitizedGroups: Map<KdbxUuid, KdbxGroup>,
        entriesByParent: Map<KdbxUuid, List<KdbxEntry>>,
        subgroupsByParent: Map<KdbxUuid, List<KdbxGroup>>
    ): GroupTreeAssembly {
        val lTime = localRoot.times.lastModificationTime
        val rTime = remoteRoot.times.lastModificationTime
        val laterTimes = if (rTime.isAfter(lTime)) remoteRoot.times else localRoot.times
        // ISSUE-P2-385：根组 customData 与子组同口径三方合并（Secret Service 暴露组等）
        val rootCustomData = mergeCustomDataMap(
            base = baseRoot.customData,
            local = localRoot.customData,
            remote = remoteRoot.customData,
            localTime = lTime,
            remoteTime = rTime
        )
        // ENG-20261111-05：根组普通属性复用子组同一字段级合并（含同字段双侧异值的冲突登记）。
        // 前提是两侧根为**同一实体**（同一根 UUID）——UUID 是全局合并键，不同 UUID 的「远端根组」
        // 属于另一个库（`.kdbx` 并入路径即以他库根组作 remote），其身份属性不得覆盖当前库根组。
        val rootFieldMerge =
            if (localRoot.id == remoteRoot.id) mergeGroupsBothModified(baseRoot, localRoot, remoteRoot) else null
        val rootGroup = if (rootFieldMerge != null) {
            rootFieldMerge.group.copy(times = laterTimes, customData = rootCustomData)
        } else {
            localRoot.copy(times = laterTimes, customData = rootCustomData)
        }
        val rootConflicts = if (rootFieldMerge == null || rootFieldMerge.conflictingFields.isEmpty()) {
            emptyList()
        } else {
            listOf(
                ConflictedGroupPair(
                    groupId = rootId.toHexString(),
                    groupName = localRoot.name.ifBlank { remoteRoot.name },
                    localGroup = localRoot,
                    remoteGroup = remoteRoot,
                    modifiedFields = rootFieldMerge.conflictingFields
                )
            )
        }

        fun assembleGroup(gid: KdbxUuid): KdbxGroup {
            val rawGroup = if (gid == rootId) {
                rootGroup
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

        return GroupTreeAssembly(assembleGroup(rootId), rootConflicts)
    }

    /**
     * 组级 customData 三方合并（ISSUE-P2-385）：键并集 + 三方值裁决。
     * 与条目级同口径：单侧取该侧，双侧同值取本地，双侧异值按 LMT 取胜；
     * 第三方键（含本仓无写入者但同步可带入的 Secret Service 暴露组标记）默认保留。
     */
    internal fun mergeCustomDataMap(
        base: Map<String, String>?,
        local: Map<String, String>,
        remote: Map<String, String>,
        localTime: java.time.Instant,
        remoteTime: java.time.Instant
    ): Map<String, String> {
        val baseMap = base.orEmpty()
        if (local == remote) return local
        if (local == baseMap && remote != baseMap) return remote
        if (remote == baseMap && local != baseMap) return local
        val keys = baseMap.keys + local.keys + remote.keys
        val merged = LinkedHashMap<String, String>()
        for (key in keys) {
            val bv = baseMap[key]
            val lv = local[key]
            val rv = remote[key]
            val winner = when {
                lv != bv && rv == bv -> lv
                lv == bv && rv != bv -> rv
                lv == rv -> lv
                else -> if (remoteTime.isAfter(localTime)) rv else lv
            }
            if (winner != null) merged[key] = winner
        }
        return merged
    }
}
