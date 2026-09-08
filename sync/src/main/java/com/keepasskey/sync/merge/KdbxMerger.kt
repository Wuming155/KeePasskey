package com.keepasskey.sync.merge

import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import java.time.Instant

/**
 * 条目冲突合并决策
 */
enum class ConflictResolutionChoice {
    KEEP_LOCAL,
    KEEP_REMOTE,
    DUPLICATE_BOTH
}

/**
 * 差异检测条目模型
 */
data class ConflictedEntryPair(
    val entryId: String,
    val localEntry: KdbxEntry,
    val remoteEntry: KdbxEntry,
    val modifiedFields: List<String>
)

/**
 * 数据库轻量级镜像（仅包含分组树与墓碑列表）。
 * 由 sync 模块定义与消费，解耦对 database 模块的直接依赖。
 */
data class KdbxDatabaseLite(
    val rootGroup: KdbxGroup,
    val deletedObjects: List<DeletedObject> = emptyList()
)

/**
 * 三方合并结果模型。
 */
data class MergeResult(
    val mergedRoot: KdbxGroup,
    val mergedDeletedObjects: List<DeletedObject>,
    val conflicts: List<ConflictedEntryPair>
)

/**
 * KDBX 墓碑感知三方同步合并引擎 (v2)。
 * 遵循 KeePass 官方 PwDatabase.MergeIn 三方合并语义：
 * 1. 基于 UUID 索引 base / local / remote 三方条目与分组树；
 * 2. 墓碑感知：单边删除且对端未修改则确认删除并保留墓碑；
 *    删除 vs 修改：修改方胜并从墓碑池中移除；
 *    删除后重建：修改/创建时间晚于墓碑时间则采纳新版并清除墓碑；
 * 3. 字段级精细合并：双方修改不同字段自动合并；同字段不同值生成冲突清单；
 * 4. 分组层级自愈：无环校验、父组继承与条目自动归属；
 * 5. 墓碑去重：按 UUID 去重并保留最晚删除时间戳。
 */
object KdbxMerger {

    /**
     * 墓碑感知三方数据库合并。
     */
    fun mergeDatabases(
        base: KdbxDatabaseLite,
        local: KdbxDatabaseLite,
        remote: KdbxDatabaseLite
    ): MergeResult {
        val rootId = local.rootGroup.id

        // 1. 索引三方条目与分组
        val baseEntries = base.rootGroup.allEntries().associateBy { it.id }
        val localEntries = local.rootGroup.allEntries().associateBy { it.id }
        val remoteEntries = remote.rootGroup.allEntries().associateBy { it.id }

        val baseGroups = base.rootGroup.allGroups().associateBy { it.id }
        val localGroups = local.rootGroup.allGroups().associateBy { it.id }
        val remoteGroups = remote.rootGroup.allGroups().associateBy { it.id }

        val localDeleted = local.deletedObjects.associateBy { it.id }
        val remoteDeleted = remote.deletedObjects.associateBy { it.id }

        // 2. 合并分组结构
        val allGroupUuids = (baseGroups.keys + localGroups.keys + remoteGroups.keys).filter { it != rootId }.toSet()
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

        // 3. 合并条目
        val allEntryUuids = (baseEntries.keys + localEntries.keys + remoteEntries.keys + localDeleted.keys + remoteDeleted.keys)
            .filter { !allGroupUuids.contains(it) && it != rootId }
            .toSet()

        val survivingEntries = mutableListOf<KdbxEntry>()
        val conflicts = mutableListOf<ConflictedEntryPair>()

        for (entryId in allEntryUuids) {
            val be = baseEntries[entryId]
            val le = localEntries[entryId]
            val re = remoteEntries[entryId]
            val ld = localDeleted[entryId]
            val rd = remoteDeleted[entryId]

            val isLocalDeleted = le == null && (ld != null || be != null)
            val isRemoteDeleted = re == null && (rd != null || be != null)

            when {
                le == null && re == null -> {
                    // 双方均无此条目 / 双方均已删除
                }
                isLocalDeleted && re != null -> {
                    val rModTime = re.times.lastModificationTime
                    val reRecreated = ld != null && rModTime.isAfter(ld.deletionTime)
                    val reModified = isEntryModified(be, re)
                    if (reRecreated || reModified) {
                        // 修改胜 / 重建胜
                        survivingEntries.add(re)
                    }
                }
                isRemoteDeleted && le != null -> {
                    val lModTime = le.times.lastModificationTime
                    val leRecreated = rd != null && lModTime.isAfter(rd.deletionTime)
                    val leModified = isEntryModified(be, le)
                    if (leRecreated || leModified) {
                        survivingEntries.add(le)
                    }
                }
                le != null && re != null -> {
                    val lModified = isEntryModified(be, le)
                    val rModified = isEntryModified(be, re)
                    when {
                        !lModified && !rModified -> survivingEntries.add(le)
                        lModified && !rModified -> survivingEntries.add(le)
                        !lModified && rModified -> survivingEntries.add(re)
                        else -> {
                            val (mergedEntry, conflictPair) = mergeConflictedEntry(be, le, re)
                            survivingEntries.add(mergedEntry)
                            if (conflictPair != null) {
                                conflicts.add(conflictPair)
                            }
                        }
                    }
                }
                le != null && re == null -> {
                    // 单侧新建条目（base 与墓碑中均无记录）：新建方保留。
                    // 缺失此分支会导致合并静默丢弃所有一端新建的条目（静默数据丢失）
                    survivingEntries.add(le)
                }
                le == null && re != null -> {
                    survivingEntries.add(re)
                }
            }
        }

        // 4. 构建合并后分组树与父子关系自愈（防死循环/断链挂载至根组）
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

        // 条目分配至所属分组：parentGroupId 失链时（删除vs修改复活场景）优先回退到
        // previousParentGroup（KeePassXC Merger 复活规则：跨设备移动/复活条目应回到原父组
        // 而非无条件抛到根组），仍无处可挂才归属根组；回退时同步改写条目 parentGroupId
        val entriesByParent = survivingEntries
            .map { entry ->
                val pid = entry.parentGroupId
                if (pid != null && sanitizedGroups.containsKey(pid)) {
                    entry
                } else {
                    val target = entry.previousParentGroup
                        ?.takeIf { sanitizedGroups.containsKey(it) }
                        ?: rootId
                    entry.copy(parentGroupId = target)
                }
            }
            .groupBy { it.parentGroupId!! }

        val subgroupsByParent = sanitizedGroups.values.groupBy { it.parentGroupId ?: rootId }

        fun assembleGroup(gid: KdbxUuid): KdbxGroup {
            val rawGroup = if (gid == rootId) {
                val lRoot = local.rootGroup
                val rRoot = remote.rootGroup
                val laterTimes = if (rRoot.times.lastModificationTime.isAfter(lRoot.times.lastModificationTime)) {
                    rRoot.times
                } else {
                    lRoot.times
                }
                lRoot.copy(times = laterTimes)
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

        val mergedRootGroup = assembleGroup(rootId)

        // 5. 墓碑合并去重与存活对象清洗
        val survivingEntryUuids = survivingEntries.map { it.id }.toSet()
        val survivingGroupUuids = sanitizedGroups.keys + rootId
        val allSurvivingUuids = survivingEntryUuids + survivingGroupUuids

        val candidateTombstones = (base.deletedObjects + local.deletedObjects + remote.deletedObjects)
            .groupBy { it.id }

        val mergedDeletedObjects = mutableListOf<DeletedObject>()
        for ((id, list) in candidateTombstones) {
            // 若存活（修改方胜或重建胜），从墓碑中剔除
            if (!allSurvivingUuids.contains(id)) {
                val latest = list.maxByOrNull { it.deletionTime }!!
                mergedDeletedObjects.add(latest)
            }
        }

        return MergeResult(
            mergedRoot = mergedRootGroup,
            mergedDeletedObjects = mergedDeletedObjects,
            conflicts = conflicts
        )
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

    private fun isEntryModified(base: KdbxEntry?, current: KdbxEntry): Boolean {
        if (base == null) return true
        // ProtectedString.equals 为字节数组内容比较，直接用 Map 相等性判断，
        // 不经 readString() 将全库密码物化为不可清除的 String
        if (base.fields != current.fields) return true
        if (base.customFields != current.customFields) return true
        if (base.tags != current.tags) return true
        if (base.attachments != current.attachments) return true
        if (base.parentGroupId != current.parentGroupId) return true
        if (base.overrideUrl != current.overrideUrl) return true
        if (base.qualityCheck != current.qualityCheck) return true
        if (base.iconId != current.iconId || base.customIconId != current.customIconId) return true
        if (base.times.lastModificationTime != current.times.lastModificationTime) return true
        return false
    }

    private fun mergeConflictedEntry(
        base: KdbxEntry?,
        local: KdbxEntry,
        remote: KdbxEntry
    ): Pair<KdbxEntry, ConflictedEntryPair?> {
        val diffFields = mutableListOf<String>()

        // 字段级三方合并
        val allFieldKeys = (local.fields.keys + remote.fields.keys + (base?.fields?.keys ?: emptySet())).toSet()
        val mergedFields = mutableMapOf<String, ProtectedString>()

        for (key in allFieldKeys) {
            val bv = base?.fields?.get(key)
            val lv = local.fields[key]
            val rv = remote.fields[key]

            val lChanged = isFieldDifferent(lv, bv)
            val rChanged = isFieldDifferent(rv, bv)

            when {
                lChanged && !rChanged -> if (lv != null) mergedFields[key] = lv
                !lChanged && rChanged -> if (rv != null) mergedFields[key] = rv
                !lChanged && !rChanged -> if (lv != null) mergedFields[key] = lv
                else -> {
                    // 双方均修改
                    if (!isFieldDifferent(lv, rv)) {
                        if (lv != null) mergedFields[key] = lv
                    } else {
                        // 冲突字段
                        diffFields.add(getFieldDisplayName(key))
                        val picked = if (remote.times.lastModificationTime.isAfter(local.times.lastModificationTime)) rv else lv
                        if (picked != null) mergedFields[key] = picked
                    }
                }
            }
        }

        // 自定义字段合并
        val baseCustomMap = base?.customFields?.associateBy { it.key } ?: emptyMap()
        val localCustomMap = local.customFields.associateBy { it.key }
        val remoteCustomMap = remote.customFields.associateBy { it.key }
        val allCustomKeys = (localCustomMap.keys + remoteCustomMap.keys + baseCustomMap.keys).toSet()
        val mergedCustomFields = mutableListOf<KdbxCustomField>()

        for (key in allCustomKeys) {
            val bc = baseCustomMap[key]
            val lc = localCustomMap[key]
            val rc = remoteCustomMap[key]

            // KdbxCustomField 为 data class，其 value 的 ProtectedString.equals
            // 为字节数组内容比较，无需物化明文
            val lChanged = lc?.value != bc?.value
            val rChanged = rc?.value != bc?.value

            when {
                lChanged && !rChanged -> if (lc != null) mergedCustomFields.add(lc)
                !lChanged && rChanged -> if (rc != null) mergedCustomFields.add(rc)
                !lChanged && !rChanged -> if (lc != null) mergedCustomFields.add(lc)
                else -> {
                    if (lc?.value == rc?.value) {
                        if (lc != null) mergedCustomFields.add(lc)
                    } else {
                        diffFields.add("自定义字段: $key")
                        val picked = if (remote.times.lastModificationTime.isAfter(local.times.lastModificationTime)) rc else lc
                        if (picked != null) mergedCustomFields.add(picked)
                    }
                }
            }
        }

        // 标签合并 (Union)
        val mergedTags = (local.tags + remote.tags).distinct()

        // 附件合并
        val bAttachments: List<KdbxAttachment> = base?.attachments ?: emptyList()
        val mergedAttachments = when {
            local.attachments != bAttachments && remote.attachments == bAttachments -> local.attachments
            local.attachments == bAttachments && remote.attachments != bAttachments -> remote.attachments
            else -> {
                val attMap = mutableMapOf<String, KdbxAttachment>()
                bAttachments.forEach { attMap[it.name] = it }
                remote.attachments.forEach { attMap[it.name] = it }
                local.attachments.forEach { attMap[it.name] = it }
                attMap.values.toList()
            }
        }

        // 历史版本合并（对齐官方 MergeIn：三方历史并集，按最后修改时间去重后升序排列；
        // 时间戳碰撞时优先保留本地侧快照）
        val mergedHistory = (local.history + remote.history + base?.history.orEmpty())
            .distinctBy { it.times.lastModificationTime }
            .sortedBy { it.times.lastModificationTime }

        // 时间戳取最新
        val maxMod = if (remote.times.lastModificationTime.isAfter(local.times.lastModificationTime)) {
            remote.times.lastModificationTime
        } else {
            local.times.lastModificationTime
        }

        val parentGroupId = when {
            local.parentGroupId != base?.parentGroupId && remote.parentGroupId == base?.parentGroupId -> local.parentGroupId
            local.parentGroupId == base?.parentGroupId && remote.parentGroupId != base?.parentGroupId -> remote.parentGroupId
            else -> if (remote.times.lastModificationTime.isAfter(local.times.lastModificationTime)) remote.parentGroupId else local.parentGroupId
        }

        val mergedEntry = local.copy(
            fields = mergedFields,
            customFields = mergedCustomFields,
            tags = mergedTags,
            attachments = mergedAttachments,
            history = mergedHistory,
            times = local.times.copy(lastModificationTime = maxMod),
            parentGroupId = parentGroupId
        )

        val conflictPair = if (diffFields.isNotEmpty()) {
            ConflictedEntryPair(
                entryId = local.id.toHexString(),
                localEntry = local,
                remoteEntry = remote,
                modifiedFields = diffFields
            )
        } else {
            null
        }

        return Pair(mergedEntry, conflictPair)
    }

    private fun isFieldDifferent(a: ProtectedString?, b: ProtectedString?): Boolean {
        if (a == null && b == null) return false
        if (a == null || b == null) return true
        // ProtectedString.equals 为字节数组内容比较，不物化明文 String
        return a != b
    }

    private fun getFieldDisplayName(key: String): String {
        return when (key) {
            KdbxConstants.Fields.TITLE -> "标题 (Title)"
            KdbxConstants.Fields.USER_NAME -> "用户名 (Username)"
            KdbxConstants.Fields.PASSWORD -> "密码 (Password)"
            KdbxConstants.Fields.URL -> "网址 (URL)"
            KdbxConstants.Fields.NOTES -> "备注 (Notes)"
            else -> key
        }
    }

    /**
     * 字段级冲突解决（TASK-30 整改）：按字段粒度应用用户决策——以本地条目为底版，
     * 用户选择「云端」的字段用远端值覆写，其余字段保留本地值。
     *
     * 合并条目保留本地 UUID（同一冲突条目的就地裁决）；任一字段采用远端值时
     * lastModificationTime 刷新为当前时刻（产物相对两侧均有变化，需触发他端再次合并）。
     * [fieldChoices] 键为 KDBX 标准字段键（KdbxConstants.Fields.*）；密码等敏感字段
     * 以 ProtectedString 整体移交，全程不物化明文。
     */
    fun resolveConflictByFields(
        pair: ConflictedEntryPair,
        fieldChoices: Map<String, ConflictResolutionChoice>
    ): KdbxEntry {
        var merged = pair.localEntry
        var adoptedRemote = false
        for ((fieldKey, choice) in fieldChoices) {
            if (choice != ConflictResolutionChoice.KEEP_REMOTE) continue
            val remoteValue = pair.remoteEntry.fields[fieldKey] ?: continue
            merged = merged.withField(fieldKey, remoteValue)
            adoptedRemote = true
        }
        if (!adoptedRemote) return merged
        return merged.copy(times = merged.times.copy(lastModificationTime = Instant.now()))
    }

    /**
     * 根据用户在冲突界面中的选择解决冲突
     */
    fun resolveConflict(
        pair: ConflictedEntryPair,
        choice: ConflictResolutionChoice
    ): List<KdbxEntry> {
        return when (choice) {
            ConflictResolutionChoice.KEEP_LOCAL -> listOf(pair.localEntry)
            ConflictResolutionChoice.KEEP_REMOTE -> listOf(pair.remoteEntry)
            ConflictResolutionChoice.DUPLICATE_BOTH -> {
                // 冲突副本必须换新 UUID：KDBX 要求 UUID 全局唯一，且同 UUID 副本
                // 在应用回分组树时会与本地原条目命中同一槽位而互相覆盖
                val remoteDuplicate = pair.remoteEntry
                    .copy(id = KdbxUuid.random())
                    .withField(
                        KdbxConstants.Fields.TITLE,
                        ProtectedString("${pair.remoteEntry.title} (云端冲突副本)", isProtected = false)
                    )
                listOf(pair.localEntry, remoteDuplicate)
            }
        }
    }
}
