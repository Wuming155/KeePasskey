package com.keepasskey.sync.merge

import com.keepasskey.core.model.CustomIcon
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
 *
 * ISSUE-P2-281 AC①：[modifiedFields] 是冲突差异的**单一真相源**，词汇为机读键
 * （不再是展示串）：标准字段＝KDBX 标准字段键（[KdbxConstants.Fields.*]）；
 * 自定义字段＝[KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX] + 字段名；标量字段＝
 * [KdbxMerger.CONFLICT_KEY_ICON_ID] 等四键。冲突界面与 [KdbxMerger.resolveConflictByFields]
 * 均以同一份词汇消费，禁止再各算一份差异。
 */
data class ConflictedEntryPair(
    val entryId: String,
    val localEntry: KdbxEntry,
    val remoteEntry: KdbxEntry,
    val modifiedFields: List<String>
)

/**
 * 数据库轻量级镜像（分组树 + 墓碑列表 + 自定义图标池）。
 * 由 sync 模块定义与消费，解耦对 database 模块的直接依赖。
 *
 * ISSUE-P2-280：[customIcons] 自本批起参与合并（此前镜像不含图标池，落库恒取本地 ⇒
 * 对端新增图标变悬空 `CustomIconRef`）。默认空表保持既有构造点源码兼容。
 */
data class KdbxDatabaseLite(
    val rootGroup: KdbxGroup,
    val deletedObjects: List<DeletedObject> = emptyList(),
    val customIcons: List<CustomIcon> = emptyList()
)

/**
 * 三方合并结果模型。
 *
 * ISSUE-P2-280：[mergedCustomIcons] 为合并后的自定义图标池（见 [KdbxMerger.mergeCustomIcons]），
 * 落库时必须一并采用——只换 `rootGroup` / `deletedObjects` 会让远端新增图标丢失、
 * 条目 / 分组的 `customIconId` 沦为悬空引用。
 */
data class MergeResult(
    val mergedRoot: KdbxGroup,
    val mergedDeletedObjects: List<DeletedObject>,
    val conflicts: List<ConflictedEntryPair>,
    // 不设默认值：强制每个构造点显式给出图标池（防「忘了采用合并池」式回归）
    val mergedCustomIcons: List<CustomIcon>
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
     * ISSUE-P2-281：冲突差异键的词汇常量（[ConflictedEntryPair.modifiedFields] 的单一词汇表，
     * 产出侧 `KdbxEntryMerger` / `BothModifiedEntryCollector` 与消费侧冲突界面、
     * [resolveConflictByFields] 共用）。
     */

    /** 自定义字段差异键前缀：实际键 = 本前缀 + 自定义字段名（防与标准字段键撞名）。 */
    const val CUSTOM_FIELD_CONFLICT_PREFIX = "custom:"

    /** 标量字段差异键（`KdbxEntryMerger.MERGED_SCALAR_FIELDS` 的同词汇外显）。 */
    const val CONFLICT_KEY_ICON_ID = "iconId"
    const val CONFLICT_KEY_CUSTOM_ICON_ID = "customIconId"
    const val CONFLICT_KEY_OVERRIDE_URL = "overrideUrl"
    const val CONFLICT_KEY_QUALITY_CHECK = "qualityCheck"

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
            conflicts = conflicts,
            // ISSUE-P2-280 AC①：自定义图标池参与合并（对齐官方 MergeInCustomIcons 口径）
            mergedCustomIcons = mergeCustomIcons(local.customIcons, remote.customIcons)
        )
    }

    /**
     * ISSUE-P2-280 AC①：自定义图标池合并——逐字对齐官方 `PwDatabase.MergeInCustomIcons`
     * （`PwDatabase.cs:945-979`）口径：
     *
     * - 按 UUID 求**并集**：对端新增的图标进入合并池（本端条目的 `customIconId` 从此可解析）；
     * - 同 UUID 两侧内容不一致 ⇒ 按 `lastModificationTime` **LWW** 取胜；
     *   任一侧时间为 `null` 视为最旧（KDBX 4.0 库无该字段），均 `null` / 相等时取本地侧；
     * - **只增不删**：图标池不做删除合并（同官方——墓碑只覆盖条目 / 分组，不覆盖图标）；
     * - **base 不参与**：官方对图标池即双向合并，无三方底版语义。
     */
    internal fun mergeCustomIcons(
        local: List<CustomIcon>,
        remote: List<CustomIcon>
    ): List<CustomIcon> {
        val merged = local.associateBy { it.uuid }.toMutableMap()
        for (icon in remote) {
            val existing = merged[icon.uuid]
            merged[icon.uuid] = when {
                existing == null -> icon
                existing == icon -> existing
                (existing.lastModificationTime ?: Instant.MIN) >=
                    (icon.lastModificationTime ?: Instant.MIN) -> existing
                else -> icon
            }
        }
        return merged.values.toList()
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
     * 字段级冲突解决（TASK-30 整改；`ISSUE-P2-281` 扩词汇）：按字段粒度应用用户决策——
     * 以本地条目为底版，用户选择「云端」的字段用远端值覆写，其余字段保留本地值。
     *
     * 合并条目保留本地 UUID（同一冲突条目的就地裁决）；任一字段采用远端值时
     * lastModificationTime 刷新为当前时刻（产物相对两侧均有变化，需触发他端再次合并）。
     * [fieldChoices] 键的词汇与 [ConflictedEntryPair.modifiedFields] 同表
     * （单一真相源，禁止另造）：标准字段键（[KdbxConstants.Fields.*]）、
     * [CUSTOM_FIELD_CONFLICT_PREFIX] 前缀的自定义字段键、四个标量键
     * （[CONFLICT_KEY_ICON_ID] / [CONFLICT_KEY_CUSTOM_ICON_ID] /
     * [CONFLICT_KEY_OVERRIDE_URL] / [CONFLICT_KEY_QUALITY_CHECK]）。
     * 密码等敏感字段以 ProtectedString 整体移交，全程不物化明文。
     */
    fun resolveConflictByFields(
        pair: ConflictedEntryPair,
        fieldChoices: Map<String, ConflictResolutionChoice>
    ): KdbxEntry {
        var merged = pair.localEntry
        var adoptedRemote = false
        for ((fieldKey, choice) in fieldChoices) {
            if (choice != ConflictResolutionChoice.KEEP_REMOTE) continue
            when {
                fieldKey.startsWith(CUSTOM_FIELD_CONFLICT_PREFIX) -> {
                    val customKey = fieldKey.removePrefix(CUSTOM_FIELD_CONFLICT_PREFIX)
                    val remoteField = pair.remoteEntry.customFields.firstOrNull { it.key == customKey }
                        ?: continue
                    merged = merged.copy(
                        customFields = merged.customFields.filterNot { it.key == customKey } + remoteField
                    )
                }
                fieldKey == CONFLICT_KEY_ICON_ID ->
                    merged = merged.copy(iconId = pair.remoteEntry.iconId)
                fieldKey == CONFLICT_KEY_CUSTOM_ICON_ID ->
                    merged = merged.copy(customIconId = pair.remoteEntry.customIconId)
                fieldKey == CONFLICT_KEY_OVERRIDE_URL ->
                    merged = merged.copy(overrideUrl = pair.remoteEntry.overrideUrl)
                fieldKey == CONFLICT_KEY_QUALITY_CHECK ->
                    merged = merged.copy(qualityCheck = pair.remoteEntry.qualityCheck)
                else -> {
                    val remoteValue = pair.remoteEntry.fields[fieldKey] ?: continue
                    merged = merged.withField(fieldKey, remoteValue)
                }
            }
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
