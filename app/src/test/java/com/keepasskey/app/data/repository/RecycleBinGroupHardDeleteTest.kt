package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P2-284` AC②：分组硬删除的墓碑**递归计数**回归。
 *
 * ## 锁定的缺陷
 *
 * 整改前组硬删除（回收站禁用 / 与回收站相关分支）只为**组自身**立墓碑，
 * 子条目与子组无碑 ⇒ 他端仍持有的副本在合并中按「修改胜」**复活**。
 * 整改后与 `emptyRecycleBin` 复用同一实现 `permanentlyDeleteObjects`，
 * 墓碑集合 = 子孙条目 ∪ 子孙子组 ∪ 组自身（官方 `PwGroup.DeleteAllObjects` 同口径）。
 */
class RecycleBinGroupHardDeleteTest {

    private val groupId = KdbxUuid.random()
    private val subGroupId = KdbxUuid.random()
    private val entryId1 = KdbxUuid.random()
    private val entryId2 = KdbxUuid.random()

    private fun entry(id: KdbxUuid, parentId: KdbxUuid) = KdbxEntry(
        id = id,
        parentGroupId = parentId,
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("条目-$id", false))
    )

    private fun newDb(): KdbxDatabase {
        val subGroup = KdbxGroup(
            id = subGroupId,
            parentGroupId = groupId,
            name = "子组",
            entries = listOf(entry(entryId2, subGroupId))
        )
        val target = KdbxGroup(
            id = groupId,
            name = "目标组",
            entries = listOf(entry(entryId1, groupId)),
            subgroups = listOf(subGroup)
        )
        return KdbxDatabase(
            header = KdbxHeader.createDefault(),
            rootGroup = KdbxGroup(name = "Root", subgroups = listOf(target)),
            // 回收站禁用 ⇒ 删除分组必走物理删除分支（本条整改面）
            recycleBinEnabled = false
        )
    }

    @Test
    fun `组硬删除：墓碑数 = 子孙对象数 + 组自身，且整棵子树摘除`() = runBlocking {
        val session = DatabaseSession()
        session.setDatabaseForTesting(newDb())
        val coordinator = RecycleBinCoordinator(
            strings = StringsProvider { _, _ -> "" },
            databaseSession = session,
            persistSession = { KdbxResult.Success(Unit) }
        )

        val result = coordinator.deleteGroup(groupId.toHexString())

        assertTrue("删除必须成功: $result", result is KdbxResult.Success)
        val db = session.databaseFlow.value!!
        val tombstoneIds = db.deletedObjects.map { it.id }.toSet()
        assertEquals(
            "墓碑集合必须恰为 {组自身, 子组, 条目1, 条目2}（子孙对象数 + 组自身）",
            setOf(groupId, subGroupId, entryId1, entryId2),
            tombstoneIds
        )
        assertTrue("目标组必须已摘除", db.rootGroup.allGroups().none { it.id == groupId })
        assertTrue("子组必须已摘除", db.rootGroup.allGroups().none { it.id == subGroupId })
        assertTrue("子孙条目必须已摘除", db.rootGroup.allEntries().none { it.id == entryId1 || it.id == entryId2 })
    }

    @Test
    fun `组硬删除：空组只立自身一碑（无子孙不多立）`() = runBlocking {
        val session = DatabaseSession()
        session.setDatabaseForTesting(
            newDb().copy(
                rootGroup = KdbxGroup(
                    name = "Root",
                    subgroups = listOf(KdbxGroup(id = groupId, name = "空组"))
                )
            )
        )
        val coordinator = RecycleBinCoordinator(
            strings = StringsProvider { _, _ -> "" },
            databaseSession = session,
            persistSession = { KdbxResult.Success(Unit) }
        )

        val result = coordinator.deleteGroup(groupId.toHexString())

        assertTrue("删除必须成功: $result", result is KdbxResult.Success)
        assertEquals(
            setOf(groupId),
            session.databaseFlow.value!!.deletedObjects.map { it.id }.toSet()
        )
    }
}
