package com.keepasskey.database

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 回归测试：分组「重命名/改图标」等仅更新元数据的保存路径不得清空
 * 既有分组的子条目与子分组（DatabaseSession.saveGroup / updateOrAddGroup 保护性合并）。
 *
 * 原缺陷：调用方（RealVaultRepository.saveGroup）曾以仅含 4 个字段的 KdbxGroup
 * （entries 与 subgroups 均为默认空列表）直接覆盖既有分组，导致子内容全部丢失。
 */
class DatabaseSessionGroupProtectionTest {

    private fun buildEntry(id: KdbxUuid, parentId: KdbxUuid, title: String): KdbxEntry {
        return KdbxEntry(
            id = id,
            parentGroupId = parentId,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, false))
        )
    }

    @Test
    fun `saveGroup 以不含子项的分组更新嵌套既有分组时子项被保护性保留`() = runTest {
        val rootId = KdbxUuid.random()
        val intermediateId = KdbxUuid.random()
        val targetId = KdbxUuid.random()
        val subGroupId = KdbxUuid.random()
        val entryAId = KdbxUuid.random()
        val entryBId = KdbxUuid.random()

        // 结构：root > 中间分组 > 目标分组（子条目A + 子分组（孙条目B））
        val targetGroup = KdbxGroup(
            id = targetId,
            parentGroupId = intermediateId,
            name = "目标分组",
            entries = listOf(buildEntry(entryAId, targetId, "子条目A")),
            subgroups = listOf(
                KdbxGroup(
                    id = subGroupId,
                    parentGroupId = targetId,
                    name = "子分组",
                    entries = listOf(buildEntry(entryBId, subGroupId, "孙条目B"))
                )
            )
        )
        val root = KdbxGroup(
            id = rootId,
            name = "Root",
            subgroups = listOf(
                KdbxGroup(
                    id = intermediateId,
                    parentGroupId = rootId,
                    name = "中间分组",
                    subgroups = listOf(targetGroup)
                )
            )
        )
        val session = DatabaseSession()
        session.setDatabaseForTesting(KdbxDatabase(header = KdbxHeader.createDefault(), rootGroup = root))

        // 模拟上层「重命名 + 改图标」：仅携带 4 个字段的更新（entries/subgroups 默认空列表）
        val bareUpdate = KdbxGroup(
            id = targetId,
            parentGroupId = intermediateId,
            name = "重命名后的分组",
            iconId = 67
        )
        session.saveGroup(bareUpdate)

        val updatedDb = session.databaseFlow.first()!!
        val updatedTarget = updatedDb.rootGroup.findGroup(targetId)
        assertNotNull("更新后目标分组必须仍存在于分组树中", updatedTarget)
        assertEquals("重命名后的分组", updatedTarget!!.name)
        assertEquals("图标更新必须生效", 67, updatedTarget.iconId)
        assertEquals("子条目必须原样保留", 1, updatedTarget.entries.size)
        assertEquals(entryAId, updatedTarget.entries[0].id)
        assertEquals("子条目A", updatedTarget.entries[0].title)
        assertEquals("子分组必须原样保留", 1, updatedTarget.subgroups.size)
        assertEquals(subGroupId, updatedTarget.subgroups[0].id)
        assertEquals("孙条目必须随子分组原样保留", 1, updatedTarget.subgroups[0].entries.size)
        assertEquals(entryBId, updatedTarget.subgroups[0].entries[0].id)
        assertEquals("全树条目数量不得丢失", 2, updatedDb.rootGroup.allEntries().size)
    }

    @Test
    fun `saveGroup 以不含子项的分组更新根分组时子项被保护性保留`() = runTest {
        val rootId = KdbxUuid.random()
        val subGroupId = KdbxUuid.random()
        val entryId = KdbxUuid.random()

        val root = KdbxGroup(
            id = rootId,
            name = "Root",
            entries = listOf(buildEntry(entryId, rootId, "根下条目")),
            subgroups = listOf(
                KdbxGroup(id = subGroupId, parentGroupId = rootId, name = "根下分组")
            )
        )
        val session = DatabaseSession()
        session.setDatabaseForTesting(KdbxDatabase(header = KdbxHeader.createDefault(), rootGroup = root))

        // 仅元数据的根分组更新（id 命中根分组、entries/subgroups 为默认空列表）
        session.saveGroup(
            KdbxGroup(
                id = rootId,
                name = "新根组名",
                iconId = 67
            )
        )

        val updatedDb = session.databaseFlow.first()!!
        val updatedRoot = updatedDb.rootGroup
        assertEquals("新根组名", updatedRoot.name)
        assertEquals(67, updatedRoot.iconId)
        assertEquals("根分组子条目必须保留", 1, updatedRoot.entries.size)
        assertEquals(entryId, updatedRoot.entries[0].id)
        assertEquals("根分组子分组必须保留", 1, updatedRoot.subgroups.size)
        assertEquals(subGroupId, updatedRoot.subgroups[0].id)
        assertEquals(1, updatedDb.rootGroup.allEntries().size)
    }

    @Test
    fun `saveGroup 显式携带子项的分组保存不触发保护性合并`() = runTest {
        val rootId = KdbxUuid.random()
        val targetId = KdbxUuid.random()
        val oldEntryId = KdbxUuid.random()
        val newEntryId = KdbxUuid.random()

        val root = KdbxGroup(
            id = rootId,
            name = "Root",
            subgroups = listOf(
                KdbxGroup(
                    id = targetId,
                    parentGroupId = rootId,
                    name = "目标分组",
                    entries = listOf(buildEntry(oldEntryId, targetId, "旧子条目"))
                )
            )
        )
        val session = DatabaseSession()
        session.setDatabaseForTesting(KdbxDatabase(header = KdbxHeader.createDefault(), rootGroup = root))

        // 调用方显式携带子项（如回收站移动、合并引擎回写）：以传入内容为准，不做合并
        val explicitUpdate = KdbxGroup(
            id = targetId,
            parentGroupId = rootId,
            name = "显式重建分组",
            entries = listOf(buildEntry(newEntryId, targetId, "新子条目"))
        )
        session.saveGroup(explicitUpdate)

        val updatedDb = session.databaseFlow.first()!!
        val updatedTarget = updatedDb.rootGroup.findGroup(targetId)
        assertNotNull(updatedTarget)
        assertEquals("显式重建分组", updatedTarget!!.name)
        assertEquals("显式携带的子项必须生效（不与旧子项合并）", 1, updatedTarget.entries.size)
        assertEquals(newEntryId, updatedTarget.entries[0].id)
    }

    @Test
    fun `saveGroup 新建分组不受保护性合并影响`() = runTest {
        val rootId = KdbxUuid.random()
        val newGroupId = KdbxUuid.random()

        val root = KdbxGroup(id = rootId, name = "Root")
        val session = DatabaseSession()
        session.setDatabaseForTesting(KdbxDatabase(header = KdbxHeader.createDefault(), rootGroup = root))

        // 全新 UUID：树中不存在同 ID 分组，正常新增（回收站自动创建等路径依赖此行为）
        session.saveGroup(
            KdbxGroup(id = newGroupId, parentGroupId = rootId, name = "新建分组", iconId = 43)
        )

        val updatedDb = session.databaseFlow.first()!!
        val addedGroup = updatedDb.rootGroup.findGroup(newGroupId)
        assertNotNull("新建分组必须成功加入分组树", addedGroup)
        assertEquals("新建分组", addedGroup!!.name)
        assertEquals(43, addedGroup.iconId)
        assertTrue("新建空分组不应被塞入子项", addedGroup.entries.isEmpty() && addedGroup.subgroups.isEmpty())
        assertEquals(1, updatedDb.rootGroup.subgroups.size)
    }
}
