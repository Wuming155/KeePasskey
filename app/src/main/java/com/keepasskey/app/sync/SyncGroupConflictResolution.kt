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
        // 根组不参与分组冲突（`KdbxGroupMerger.allGroupUuids` 已排除 rootId）；出现即结构异常，
        // 替换根节点会丢掉整棵子树 —— fail-closed 而不是静默降级。
        if (mergedGroup.id == root.id) return null
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
                duplicates += pair
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
