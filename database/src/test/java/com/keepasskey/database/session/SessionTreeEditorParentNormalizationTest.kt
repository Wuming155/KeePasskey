package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ISSUE-P3-63 回归：`parentGroupId=null` 条目落树后的父组规范化。
 *
 * 此前 `SessionTreeEditor.updateOrAddEntry` 仅按 `null = 根组` 决定**放置位置**，
 * 不回写条目对象——会话内存中的根级条目长期持有 null 父组，与「XML 重新解析按
 * 结构归属还原根组 id」的冷启动模型不一致，UI 投影按 groupId 过滤时根级条目
 * （新建 / 导入）在会话内不可见、冷启动后才出现。
 */
class SessionTreeEditorParentNormalizationTest {

    @Test
    fun `null 父组条目落根组后对象规范化为根组 id`() {
        val root = KdbxGroup(name = "Root")
        val entry = KdbxEntry(parentGroupId = null)

        val updated = SessionTreeEditor.updateOrAddEntry(root, entry)

        val stored = updated.entries.single()
        assertEquals(root.id, stored.parentGroupId)
    }

    @Test
    fun `null 父组条目落入子组时规范化为该子组 id`() {
        val root = KdbxGroup(name = "Root")
        val sub = KdbxGroup(name = "Sub", parentGroupId = root.id)
        val rootWithSub = root.copy(subgroups = listOf(sub))
        val entry = KdbxEntry(parentGroupId = sub.id)

        val updated = SessionTreeEditor.updateOrAddEntry(rootWithSub, entry)

        assertEquals(sub.id, updated.subgroups.single().entries.single().parentGroupId)
    }

    @Test
    fun `既有条目以 null 父组更新后仍留在原组且父组被规范化`() {
        val root = KdbxGroup(name = "Root")
        val existing = KdbxEntry(parentGroupId = null)
        val rootWithEntry = root.copy(entries = listOf(existing))
        val edited = existing.copy(parentGroupId = null)

        val updated = SessionTreeEditor.updateOrAddEntry(rootWithEntry, edited)

        val stored = updated.entries.single()
        assertEquals(root.id, stored.parentGroupId)
        assertEquals(existing.id, stored.id)
    }

    @Test
    fun `null 父组的新分组落根组后组自身与子树成员均被规范化`() {
        val root = KdbxGroup(name = "Root")
        // 对齐 VaultTemplateFactory 的构造方式：分组与条目均以 null 父组整组保存
        val templateEntry = KdbxEntry(parentGroupId = null)
        val templateGroup = KdbxGroup(
            name = "模板",
            parentGroupId = null,
            entries = listOf(templateEntry)
        )

        val updated = SessionTreeEditor.updateOrAddGroup(root, templateGroup)

        val storedGroup = updated.subgroups.single()
        assertEquals(root.id, storedGroup.parentGroupId)
        assertEquals(storedGroup.id, storedGroup.entries.single().parentGroupId)
    }

    @Test
    fun `显式父组的分组保存不受规范化影响`() {
        val root = KdbxGroup(name = "Root")
        val target = KdbxGroup(name = "Target", parentGroupId = root.id)
        val rootWithTarget = root.copy(subgroups = listOf(target))
        val renamed = target.copy(name = "Renamed")

        val updated = SessionTreeEditor.updateOrAddGroup(rootWithTarget, renamed)

        val stored = updated.subgroups.single()
        assertEquals("Renamed", stored.name)
        assertEquals(root.id, stored.parentGroupId)
    }
}
