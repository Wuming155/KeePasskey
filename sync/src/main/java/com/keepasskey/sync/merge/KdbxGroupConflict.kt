package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid

/**
 * `ISSUE-P1-631`（分组冲突模型）：**分组**同字段双侧修改的冲突条目。
 *
 * 为什么需要它：条目侧自始有 `ConflictedEntryPair` + 冲突清单，同字段双侧修改会进入待裁决面
 * （`MergeResult.conflicts`）而不静默取一侧；分组侧此前**没有**对应模型，双侧同字段修改由
 * `KdbxGroupMerger.mergeGroupsBothModified` 按 LWW **静默**定案——分组改名 / 备注 / 图标 /
 * 所属父组 / 组级 customData 的一侧修改会无任何用户可见信号地消失。模型缺口不是豁免：
 * 「同字段双侧修改不静默取一侧」是 P1 定的合并安全口径，必须同时覆盖分组与条目。
 *
 * [modifiedFields] 的词汇为 [KdbxGroupConflictKeys] 的机读键，产出侧（`KdbxGroupMerger`）与
 * 消费侧（冲突裁决）共用同一份，禁止两处各算一份差异。
 *
 * 存活集合里的**临时值**：`mergeGroupsBothModified` 仍按 LWW 给出一个可用的值（保证合并树
 * 结构完整、无冲突场景零行为变更）；只有「记录冲突 + 裁决前不落盘」才把静默变成显式。
 */
data class ConflictedGroupPair(
    val groupId: String,
    /** 展示用组名（本地优先，空则磁盘侧） */
    val groupName: String,
    val localGroup: KdbxGroup,
    val remoteGroup: KdbxGroup,
    val modifiedFields: List<String>
)

/**
 * `ISSUE-P1-631`：分组冲突差异键的单一词汇表（与条目侧 `KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX`
 * 同构——每类键都必须由产出侧与消费侧共用同一常量，禁止字面量对拍）。
 */
object KdbxGroupConflictKeys {
    const val NAME = "group.name"
    const val NOTES = "group.notes"
    const val ICON_ID = "group.iconId"
    const val CUSTOM_ICON_ID = "group.customIconId"
    const val PARENT_GROUP_ID = "group.parentGroupId"

    /** 组级 customData 差异键前缀：实际键 = 本前缀 + 自定义键名（防与上面几项撞名） */
    const val CUSTOM_DATA_PREFIX = "group.customData:"

    /** 组级 customData 的差异键 */
    fun customDataKey(key: String): String = CUSTOM_DATA_PREFIX + key
}

/**
 * `ISSUE-P1-631`：分组存活裁决的产物——存活集合 + **冲突清单**。
 *
 * 两者必须同时返回：只给存活集合则调用方无法区分「LWW 定案」与「双侧真的分叉」，
 * 冲突又必然在存活裁决过程中才能知道（要看 base 才能判「双侧都改」）。
 */
class GroupMergeOutcome(
    val survivingGroups: Map<KdbxUuid, KdbxGroup>,
    val conflicts: List<ConflictedGroupPair>
)

/**
 * `ISSUE-P1-631`：分组冲突的**按策略重裁**（与条目侧 `KdbxMerger.resolveConflictFieldsOnly` 同形）。
 *
 * 以三方合并产物 [merged]（已由 `assembleGroupTree` 装好子组与条目）为底版，只把
 * [ConflictedGroupPair.modifiedFields] 列出的差异字段整体取所选一侧，**非差异字段保持合并产物**
 * ——同条目侧的理由：整节点替换会把对侧对其它字段的就近修改一并丢掉。
 *
 * [ConflictResolutionChoice.DUPLICATE_BOTH] 不在此处理（分组「双方保留」需复制整棵子树并重映射
 * UUID，见 `KdbxGroupDuplicator`），传入即拒绝，避免调用方误以为已按双方保留处理。
 */
object KdbxGroupConflictResolver {

    fun resolveFieldsOnly(
        merged: KdbxGroup,
        pair: ConflictedGroupPair,
        choice: ConflictResolutionChoice
    ): KdbxGroup {
        val source = when (choice) {
            ConflictResolutionChoice.KEEP_LOCAL -> pair.localGroup
            ConflictResolutionChoice.KEEP_REMOTE -> pair.remoteGroup
            ConflictResolutionChoice.DUPLICATE_BOTH -> throw IllegalArgumentException(
                "DUPLICATE_BOTH 不适用分组字段级重裁；双方保留由 KdbxGroupDuplicator 组装"
            )
        }
        var result = merged
        for (key in pair.modifiedFields) {
            result = adoptField(result, key, source)
        }
        return result
    }

    /** 单字段采用（键词汇见 [KdbxGroupConflictKeys]；未知键安全忽略，不放大为整节点替换） */
    private fun adoptField(target: KdbxGroup, key: String, source: KdbxGroup): KdbxGroup = when {
        key == KdbxGroupConflictKeys.NAME -> target.copy(name = source.name)
        key == KdbxGroupConflictKeys.NOTES -> target.copy(notes = source.notes)
        key == KdbxGroupConflictKeys.ICON_ID -> target.copy(iconId = source.iconId)
        key == KdbxGroupConflictKeys.CUSTOM_ICON_ID -> target.copy(customIconId = source.customIconId)
        key == KdbxGroupConflictKeys.PARENT_GROUP_ID ->
            target.copy(parentGroupId = source.parentGroupId)
        key.startsWith(KdbxGroupConflictKeys.CUSTOM_DATA_PREFIX) -> {
            val customKey = key.removePrefix(KdbxGroupConflictKeys.CUSTOM_DATA_PREFIX)
            val value = source.customData[customKey]
            target.copy(
                customData = if (value == null) {
                    target.customData - customKey
                } else {
                    target.customData + (customKey to value)
                }
            )
        }
        // 未登记键：保持合并产物取值（不得因未知键退化为整节点采用某一侧）
        else -> target
    }

    /**
     * 把已重裁的分组节点替换回合并树（保留该节点在树上已装好的子组与条目）。
     *
     * 树内找不到 [KdbxUuid] 时报 null 由调用方 fail-closed——绝不静默跳过冲突。
     */
    fun applyResolvedGroupsToTree(
        root: KdbxGroup,
        replacements: Map<KdbxUuid, KdbxGroup>
    ): KdbxGroup? {
        if (replacements.isEmpty()) return root
        var found = 0
        fun walk(group: KdbxGroup): KdbxGroup {
            var result = group
            replacements[group.id]?.let { resolved ->
                found++
                // 裁决只改标量字段；子树结构必须保持合并产物（resolved 的 children 即合并产物）
                result = resolved
            }
            var referenced: MutableList<KdbxGroup>? = null
            result.subgroups.forEachIndexed { index, sub ->
                val updated = walk(sub)
                if (updated !== sub) {
                    val list = referenced ?: result.subgroups.toMutableList().also { referenced = it }
                    list[index] = updated
                }
            }
            return if (referenced == null) result else result.copy(subgroups = referenced)
        }
        val walked = walk(root)
        return if (found == replacements.size) walked else null
    }
}
