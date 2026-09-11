package com.keepasskey.sync.merge

import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
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
 *
 * 本 object 仅保留对外编排与结果组装；分组 / 条目 / 墓碑的独立职责分别委托给
 * [KdbxGroupMerger] / [KdbxEntryMerger] / [KdbxTombstoneMerger]（同包 internal 单元）。
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
        val survivingGroups = KdbxGroupMerger.mergeSurvivingGroups(
            allGroupUuids = allGroupUuids,
            baseGroups = baseGroups,
            localGroups = localGroups,
            remoteGroups = remoteGroups,
            localDeleted = localDeleted,
            remoteDeleted = remoteDeleted
        )

        // 3. 合并条目
        val allEntryUuids = (baseEntries.keys + localEntries.keys + remoteEntries.keys + localDeleted.keys + remoteDeleted.keys)
            .filter { !allGroupUuids.contains(it) && it != rootId }
            .toSet()

        val (survivingEntries, conflicts) = KdbxEntryMerger.mergeSurvivingEntries(
            allEntryUuids = allEntryUuids,
            baseEntries = baseEntries,
            localEntries = localEntries,
            remoteEntries = remoteEntries,
            localDeleted = localDeleted,
            remoteDeleted = remoteDeleted
        )

        // 4. 构建合并后分组树与父子关系自愈（防死循环/断链挂载至根组）
        val sanitizedGroups = KdbxGroupMerger.sanitizeParentLinks(survivingGroups, rootId)

        // 条目分配至所属分组：parentGroupId 失链时（删除vs修改复活场景）优先回退到
        // previousParentGroup（KeePassXC Merger 复活规则：跨设备移动/复活条目应回到原父组
        // 而非无条件抛到根组），仍无处可挂才归属根组；回退时同步改写条目 parentGroupId
        val entriesByParent = KdbxEntryMerger.assignEntriesToGroups(survivingEntries, sanitizedGroups, rootId)

        val subgroupsByParent = sanitizedGroups.values.groupBy { it.parentGroupId ?: rootId }

        val mergedRootGroup = KdbxGroupMerger.assembleGroupTree(
            rootId = rootId,
            localRoot = local.rootGroup,
            remoteRoot = remote.rootGroup,
            sanitizedGroups = sanitizedGroups,
            entriesByParent = entriesByParent,
            subgroupsByParent = subgroupsByParent
        )

        // 5. 墓碑合并去重与存活对象清洗
        val survivingEntryUuids = survivingEntries.map { it.id }.toSet()
        val survivingGroupUuids = sanitizedGroups.keys + rootId
        val allSurvivingUuids = survivingEntryUuids + survivingGroupUuids

        val mergedDeletedObjects = KdbxTombstoneMerger.merge(
            baseDeleted = base.deletedObjects,
            localDeleted = local.deletedObjects,
            remoteDeleted = remote.deletedObjects,
            survivingUuids = allSurvivingUuids
        )

        return MergeResult(
            mergedRoot = mergedRootGroup,
            mergedDeletedObjects = mergedDeletedObjects,
            conflicts = conflicts
        )
    }

    /**
     * 条目相对 base 是否被修改。
     * ISSUE-P3-03 (43a)：由 private 放宽为 internal —— 「每次询问」策略的决策清单扩充
     * （[BothModifiedEntryCollector]）必须复用同一修改判定，避免两处判定口径漂移。
     *
     * 委托 [KdbxEntryMerger.isModified]：单一实现，杜绝两处判定口径漂移。
     */
    internal fun isEntryModified(base: KdbxEntry?, current: KdbxEntry): Boolean =
        KdbxEntryMerger.isModified(base, current)

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
