package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictedGroupPair
import com.keepasskey.sync.merge.KdbxGroupConflictResolver
import com.keepasskey.sync.merge.KdbxGroupDuplicator

/**
 * `ISSUE-P2-660`：把**分组冲突**按用户裁决落回合并树（漂移面与同步面共用的唯一实现）。
 *
 * 为什么必须共用：`ISSUE-P1-631` 已在漂移（本地磁盘）合并里实现同一语义，同步面若另写一份，
 * 两条路径的「只重裁差异字段」「双方保留 = 复制整棵子树」口径必然漂移。本函数只保留
 * 与目标无关的裁决机制，差异集中在两个回调：
 *
 * - [choiceOf]：两边各自的裁决词汇（漂移三档 / 同步条目级三档）→ [ConflictResolutionChoice]
 * - [duplicateNameOf]：复制体根节点的显示名（两侧用户在意的措辞不同：「磁盘冲突副本」/「云端冲突副本」）
 *
 * 语义（与 `ISSUE-P1-631` 逐条一致）：
 * - 本地 / 远端：只重裁 [ConflictedGroupPair.modifiedFields] 列出的差异字段，以合并产物为底版
 *   （`KdbxGroupConflictResolver.resolveFieldsOnly`）——整节点替换会丢掉对侧对其它字段的修改；
 * - 双方保留：原节点保留**本地**字段值，另在同一父组下插入一棵复制子树，根节点取**远端**字段值、
 *   名字带副本后缀；复制体上分组与条目 UUID 全部重生成（`KdbxGroupDuplicator`）；
 * - 任一环节找不到目标节点（树结构与冲突清单不一致）返回 null，调用方 fail-closed，
 *   **绝不静默跳过一条冲突**。
 *
 * `ENG-20261111-05`：**根组**也可以是冲突目标——根组普通属性已并入三方合并（`MergeResult.groupConflicts`），
 * 其字段级重裁（本地 / 远端）与子组同口径生效；`resolveFieldsOnly` 以合并产物为底版、连同已装好的子树
 * 一并返回，故替换根节点不会丢子树。根的「双方保留」无对应语义：一棵库只有一个根，无父组可挂兄弟副本，
 * 按原节点保留**本地**字段值落定（与 `ISSUE-P2-659` 库级 Meta「双方保留不产生副本」的既定边界同类）。
 *
 * @return 应用裁决后的新根；结构不一致时 null
 */
internal fun applyGroupConflictChoices(
    root: KdbxGroup,
    conflicts: List<ConflictedGroupPair>,
    choiceOf: (ConflictedGroupPair) -> ConflictResolutionChoice,
    duplicateNameOf: (ConflictedGroupPair) -> String
): KdbxGroup? {
    if (conflicts.isEmpty()) return root
    val byHex = root.allGroups().associateBy { it.id.toHexString() }
    val replacements = mutableMapOf<KdbxUuid, KdbxGroup>()
    val duplicates = mutableListOf<ConflictedGroupPair>()

    for (pair in conflicts) {
        val mergedGroup = byHex[pair.groupId] ?: return null
        when (choiceOf(pair)) {
            ConflictResolutionChoice.KEEP_LOCAL -> {
                replacements[mergedGroup.id] = KdbxGroupConflictResolver.resolveFieldsOnly(
                    merged = mergedGroup,
                    pair = pair,
                    choice = ConflictResolutionChoice.KEEP_LOCAL
                )
            }
            ConflictResolutionChoice.KEEP_REMOTE -> {
                replacements[mergedGroup.id] = KdbxGroupConflictResolver.resolveFieldsOnly(
                    merged = mergedGroup,
                    pair = pair,
                    choice = ConflictResolutionChoice.KEEP_REMOTE
                )
            }
            ConflictResolutionChoice.DUPLICATE_BOTH -> {
                replacements[mergedGroup.id] = KdbxGroupConflictResolver.resolveFieldsOnly(
                    merged = mergedGroup,
                    pair = pair,
                    choice = ConflictResolutionChoice.KEEP_LOCAL
                )
                // 根组无父组可挂同父兄弟副本（`KdbxGroupDuplicator` 对根目标即返回 null）
                if (mergedGroup.id != root.id) duplicates += pair
            }
        }
    }

    var result = KdbxGroupConflictResolver.applyResolvedGroupsToTree(root, replacements) ?: return null
    for (pair in duplicates) {
        val targetId = result.allGroups().firstOrNull { it.id.toHexString() == pair.groupId }?.id ?: return null
        result = KdbxGroupDuplicator.duplicateSubtreeAsSibling(
            root = result,
            targetId = targetId,
            newName = duplicateNameOf(pair),
            // 复制体根节点取远端侧字段值；parentGroupId 由复制器重挂为原分组的父组
            resolveTargetFields = { duplicate ->
                KdbxGroupConflictResolver.resolveFieldsOnly(
                    merged = duplicate,
                    pair = pair,
                    choice = ConflictResolutionChoice.KEEP_REMOTE
                )
            }
        ) ?: return null
    }
    return result
}
