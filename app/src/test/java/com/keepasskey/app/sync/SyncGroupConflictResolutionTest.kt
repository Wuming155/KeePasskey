package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictedGroupPair
import com.keepasskey.sync.merge.KdbxGroupConflictKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P2-660`：分组冲突裁决落树（同步面与漂移面共用的 [applyGroupConflictChoices]）。
 *
 * 覆盖「逐条裁决落树」与「双方保留」两条语义：
 * - 取本地 / 取云端：**只重裁差异字段**，非差异字段保持三方合并产物（整节点替换会丢掉对侧
 *   对其它字段的就近修改，与 `ISSUE-P1-631` 的裁决范围收口同口径）；
 * - 双方保留：原分组保留本地值，同父下插入复制子树，根节点取云端值 + 副本后缀，
 *   子树内分组与条目 UUID 全部重生成；
 * - 结构不一致（目标不在树内 / 目标为根）fail-closed 返回 null，绝不静默跳过冲突。
 */
class SyncGroupConflictResolutionTest {

    private val rootId = KdbxUuid(ByteArray(16) { 9 })
    private val parentId = KdbxUuid(ByteArray(16) { 1 })
    private val targetId = KdbxUuid(ByteArray(16) { 2 })
    private val childGroupId = KdbxUuid(ByteArray(16) { 3 })
    private val entryId = KdbxUuid(ByteArray(16) { 4 })

    @Test
    fun `取本地只重裁差异字段而保留其它字段的合并结果`() {
        val merged = mergedTree()
        val pair = conflictPair()

        val resolved = requireNotNull(
            applyGroupConflictChoices(
                root = merged,
                conflicts = listOf(pair),
                choiceOf = { ConflictResolutionChoice.KEEP_LOCAL },
                duplicateNameOf = { "副本" }
            )
        )

        val target = resolved.allGroups().first { it.id == targetId }
        assertEquals("差异字段取本地", "本地组名", target.name)
        assertEquals("非差异字段必须保持合并产物", "合并备注", target.notes)
        assertEquals("合并产物里的图标不得被整节点替换冲掉", 42, target.iconId)
        assertEquals("子树结构必须保持合并产物", 1, target.subgroups.size)
    }

    @Test
    fun `取云端只重裁差异字段`() {
        val resolved = requireNotNull(
            applyGroupConflictChoices(
                root = mergedTree(),
                conflicts = listOf(conflictPair()),
                choiceOf = { ConflictResolutionChoice.KEEP_REMOTE },
                duplicateNameOf = { "副本" }
            )
        )

        val target = resolved.allGroups().first { it.id == targetId }
        assertEquals("差异字段取云端", "云端组名", target.name)
        assertEquals("非差异字段保持合并产物", "合并备注", target.notes)
    }

    @Test
    fun `双方保留复制整棵子树并全部换新 UUID`() {
        val resolved = requireNotNull(
            applyGroupConflictChoices(
                root = mergedTree(),
                conflicts = listOf(conflictPair()),
                choiceOf = { ConflictResolutionChoice.DUPLICATE_BOTH },
                duplicateNameOf = { "云端组名 (云端冲突副本)" }
            )
        )

        val parent = resolved.allGroups().first { it.id == parentId }
        assertEquals("原分组 + 副本必须成为同父兄弟", 2, parent.subgroups.size)

        val original = parent.subgroups.first { it.id == targetId }
        assertEquals("原分组保留本地字段值", "本地组名", original.name)

        val copy = parent.subgroups.first { it.id != targetId }
        assertEquals("副本名 = 云端组名 + 副本后缀", "云端组名 (云端冲突副本)", copy.name)
        assertNotEquals("副本必须换新分组 UUID", targetId, copy.id)
        assertEquals("副本必须挂在同一父组", parentId, copy.parentGroupId)
        assertEquals("子树必须被完整复制", 1, copy.subgroups.size)
        assertNotEquals("子树分组 UUID 必须重生成", childGroupId, copy.subgroups.single().id)
        assertEquals("子树内条目必须被复制", 1, copy.subgroups.single().entries.size)
        assertNotEquals("条目 UUID 必须重生成", entryId, copy.subgroups.single().entries.single().id)
        assertEquals(
            "条目必须挂到复制体分组",
            copy.subgroups.single().id,
            copy.subgroups.single().entries.single().parentGroupId
        )
        assertEquals(
            "原分组子树不得被复制体改动",
            entryId,
            original.subgroups.single().entries.single().id
        )
    }

    @Test
    fun `目标分组不在合并树内时 fail-closed`() {
        val missing = conflictPair().copy(groupId = KdbxUuid(ByteArray(16) { 7 }).toHexString())
        assertNull(
            "结构不一致必须返回 null，绝不静默跳过一条冲突",
            applyGroupConflictChoices(
                root = mergedTree(),
                conflicts = listOf(missing),
                choiceOf = { ConflictResolutionChoice.KEEP_LOCAL },
                duplicateNameOf = { "副本" }
            )
        )
    }

    @Test
    fun `冲突指向根组时 fail-closed`() {
        val rootPair = conflictPair().copy(groupId = rootId.toHexString())
        assertNull(
            "替换根节点会丢掉整棵子树，必须拒绝",
            applyGroupConflictChoices(
                root = mergedTree(),
                conflicts = listOf(rootPair),
                choiceOf = { ConflictResolutionChoice.KEEP_LOCAL },
                duplicateNameOf = { "副本" }
            )
        )
    }

    @Test
    fun `无冲突时返回原树实例`() {
        val merged = mergedTree()
        val same = applyGroupConflictChoices(merged, emptyList(), { ConflictResolutionChoice.KEEP_LOCAL }, { "副本" })
        assertTrue("无冲突不得重建整棵树", same === merged)
    }

    @Test
    fun `多组冲突按同一批裁决各自落树`() {
        val secondId = KdbxUuid(ByteArray(16) { 5 })
        val merged = mergedTree().let { tree ->
            val parent = tree.subgroups.single()
            tree.copy(
                subgroups = listOf(
                    parent.copy(
                        subgroups = parent.subgroups + KdbxGroup(
                            id = secondId,
                            name = "合并组名2",
                            notes = "合并备注2",
                            parentGroupId = parentId
                        )
                    )
                )
            )
        }
        val second = ConflictedGroupPair(
            groupId = secondId.toHexString(),
            groupName = "本地组名2",
            localGroup = KdbxGroup(id = secondId, name = "本地组名2"),
            remoteGroup = KdbxGroup(id = secondId, name = "云端组名2"),
            modifiedFields = listOf(KdbxGroupConflictKeys.NAME)
        )

        val resolved = requireNotNull(
            applyGroupConflictChoices(
                root = merged,
                conflicts = listOf(conflictPair(), second),
                choiceOf = { pair ->
                    if (pair.groupId == second.groupId) {
                        ConflictResolutionChoice.KEEP_REMOTE
                    } else {
                        ConflictResolutionChoice.KEEP_LOCAL
                    }
                },
                duplicateNameOf = { "副本" }
            )
        )

        assertEquals("本地组名", resolved.allGroups().first { it.id == targetId }.name)
        assertEquals("云端组名2", resolved.allGroups().first { it.id == secondId }.name)
    }

    private fun mergedTree(): KdbxGroup {
        val childGroup = KdbxGroup(
            id = childGroupId,
            name = "子分组",
            parentGroupId = targetId,
            entries = listOf(
                KdbxEntry(id = entryId, parentGroupId = childGroupId)
            )
        )
        val target = KdbxGroup(
            id = targetId,
            name = "合并组名",
            notes = "合并备注",
            iconId = 42,
            parentGroupId = parentId,
            subgroups = listOf(childGroup)
        )
        val parent = KdbxGroup(
            id = parentId,
            name = "父分组",
            parentGroupId = rootId,
            subgroups = listOf(target)
        )
        return KdbxGroup(
            id = rootId,
            name = "根",
            subgroups = listOf(parent)
        )
    }

    private fun conflictPair(): ConflictedGroupPair = ConflictedGroupPair(
        groupId = targetId.toHexString(),
        groupName = "本地组名",
        localGroup = KdbxGroup(id = targetId, name = "本地组名"),
        remoteGroup = KdbxGroup(id = targetId, name = "云端组名"),
        modifiedFields = listOf(KdbxGroupConflictKeys.NAME)
    )
}
