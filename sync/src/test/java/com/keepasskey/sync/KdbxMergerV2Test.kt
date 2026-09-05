package com.keepasskey.sync

import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * KdbxMerger v2 墓碑感知三方合并算法单元测试。
 */
class KdbxMergerV2Test {

    private val rootId = KdbxUuid(ByteArray(16) { 0 })

    private fun createRootGroup(
        entries: List<KdbxEntry> = emptyList(),
        subgroups: List<KdbxGroup> = emptyList()
    ): KdbxGroup {
        return KdbxGroup(
            id = rootId,
            name = "Root",
            entries = entries,
            subgroups = subgroups
        )
    }

    @Test
    fun `测试单边删除传播且保留墓碑`() {
        val entryUuid = KdbxUuid(ByteArray(16) { 1 })
        val baseEntry = KdbxEntry(
            id = entryUuid,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Old Entry", false)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1000L))
        )

        val baseDb = KdbxDatabaseLite(createRootGroup(entries = listOf(baseEntry)))
        // Local 删除了该条目，并记录墓碑
        val localTombstone = DeletedObject(id = entryUuid, deletionTime = Instant.ofEpochMilli(1500L))
        val localDb = KdbxDatabaseLite(
            rootGroup = createRootGroup(entries = emptyList()),
            deletedObjects = listOf(localTombstone)
        )
        // Remote 未动该条目
        val remoteDb = KdbxDatabaseLite(createRootGroup(entries = listOf(baseEntry)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 结果条目应已被删除
        assertNull(result.mergedRoot.findEntry(entryUuid))
        // 墓碑中保留该记录
        assertEquals(1, result.mergedDeletedObjects.size)
        assertEquals(entryUuid, result.mergedDeletedObjects.first().id)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `测试删除对修改时修改方胜并清除墓碑`() {
        val entryUuid = KdbxUuid(ByteArray(16) { 2 })
        val baseEntry = KdbxEntry(
            id = entryUuid,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Original", false)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1000L))
        )

        val baseDb = KdbxDatabaseLite(createRootGroup(entries = listOf(baseEntry)))

        // Local 删除了该条目
        val localDb = KdbxDatabaseLite(
            rootGroup = createRootGroup(entries = emptyList()),
            deletedObjects = listOf(DeletedObject(entryUuid, Instant.ofEpochMilli(1200L)))
        )

        // Remote 修改了该条目
        val remoteModified = baseEntry.withField(KdbxConstants.Fields.TITLE, "Modified by Remote")
            .copy(times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1400L)))
        val remoteDb = KdbxDatabaseLite(createRootGroup(entries = listOf(remoteModified)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 修改胜：条目被保留
        val mergedEntry = result.mergedRoot.findEntry(entryUuid)
        assertNotNull(mergedEntry)
        assertEquals("Modified by Remote", mergedEntry?.title)
        // 墓碑中被移除
        assertTrue(result.mergedDeletedObjects.isEmpty())
    }

    @Test
    fun `测试删除后对端重建胜并移除墓碑`() {
        val entryUuid = KdbxUuid(ByteArray(16) { 3 })
        val tombstoneTime = Instant.ofEpochMilli(2000L)

        // Base 中条目已在更早时间被删或不存在
        val baseDb = KdbxDatabaseLite(createRootGroup())

        // Local 持有墓碑
        val localDb = KdbxDatabaseLite(
            rootGroup = createRootGroup(),
            deletedObjects = listOf(DeletedObject(entryUuid, tombstoneTime))
        )

        // Remote 在墓碑之后重新创建了该 UUID 的条目
        val recreationTime = Instant.ofEpochMilli(3000L)
        val recreatedEntry = KdbxEntry(
            id = entryUuid,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Recreated", false)),
            times = KdbxTimes(creationTime = recreationTime, lastModificationTime = recreationTime)
        )
        val remoteDb = KdbxDatabaseLite(createRootGroup(entries = listOf(recreatedEntry)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 重建胜
        val merged = result.mergedRoot.findEntry(entryUuid)
        assertNotNull(merged)
        assertEquals("Recreated", merged?.title)
        // 墓碑被移除
        assertTrue(result.mergedDeletedObjects.isEmpty())
    }

    @Test
    fun `测试双方修改不同字段的无冲突自动合并`() {
        val entryUuid = KdbxUuid(ByteArray(16) { 4 })
        val baseEntry = KdbxEntry(
            id = entryUuid,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Base Title", false),
                KdbxConstants.Fields.USER_NAME to ProtectedString("base_user", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("base_pwd", true)
            ),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1000L))
        )

        val baseDb = KdbxDatabaseLite(createRootGroup(entries = listOf(baseEntry)))

        // Local 修改了用户名
        val localEntry = baseEntry.withField(KdbxConstants.Fields.USER_NAME, "local_user")
            .copy(times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1500L)))
        val localDb = KdbxDatabaseLite(createRootGroup(entries = listOf(localEntry)))

        // Remote 修改了密码与添加了自定义字段
        val remoteEntry = baseEntry.withField(KdbxConstants.Fields.PASSWORD, "remote_secret_123")
            .copy(
                customFields = listOf(KdbxCustomField("PIN", ProtectedString("9999", true))),
                times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1600L))
            )
        val remoteDb = KdbxDatabaseLite(createRootGroup(entries = listOf(remoteEntry)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        assertTrue(result.conflicts.isEmpty())
        val merged = result.mergedRoot.findEntry(entryUuid)
        assertNotNull(merged)
        assertEquals("local_user", merged?.userName)
        assertEquals("remote_secret_123", merged?.password?.readString())
        assertEquals("9999", merged?.customFields?.find { it.key == "PIN" }?.value?.readString())
    }

    @Test
    fun `测试双方修改同一字段产生冲突清单`() {
        val entryUuid = KdbxUuid(ByteArray(16) { 5 })
        val baseEntry = KdbxEntry(
            id = entryUuid,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Base Title", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("pwd_base", true)
            ),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1000L))
        )

        val baseDb = KdbxDatabaseLite(createRootGroup(entries = listOf(baseEntry)))

        // Local 改成 pwd_local
        val localEntry = baseEntry.withField(KdbxConstants.Fields.PASSWORD, "pwd_local")
            .copy(times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1500L)))
        val localDb = KdbxDatabaseLite(createRootGroup(entries = listOf(localEntry)))

        // Remote 改成 pwd_remote
        val remoteEntry = baseEntry.withField(KdbxConstants.Fields.PASSWORD, "pwd_remote")
            .copy(times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1600L)))
        val remoteDb = KdbxDatabaseLite(createRootGroup(entries = listOf(remoteEntry)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        assertEquals(1, result.conflicts.size)
        val conflict = result.conflicts.first()
        assertEquals(entryUuid.toHexString(), conflict.entryId)
        assertTrue(conflict.modifiedFields.contains("密码 (Password)"))
    }

    @Test
    fun `测试墓碑合并去重保留最晚删除时间`() {
        val uuid1 = KdbxUuid(ByteArray(16) { 11 })
        val uuid2 = KdbxUuid(ByteArray(16) { 12 })

        val baseDb = KdbxDatabaseLite(createRootGroup())

        val localDb = KdbxDatabaseLite(
            rootGroup = createRootGroup(),
            deletedObjects = listOf(
                DeletedObject(uuid1, Instant.ofEpochMilli(1000L)),
                DeletedObject(uuid2, Instant.ofEpochMilli(2000L))
            )
        )

        val remoteDb = KdbxDatabaseLite(
            rootGroup = createRootGroup(),
            deletedObjects = listOf(
                DeletedObject(uuid1, Instant.ofEpochMilli(1500L)) // 远端删除时间更晚
            )
        )

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        assertEquals(2, result.mergedDeletedObjects.size)
        val t1 = result.mergedDeletedObjects.find { it.id == uuid1 }
        assertEquals(Instant.ofEpochMilli(1500L), t1?.deletionTime)
    }

    @Test
    fun `测试分组移动修改与循环引用防护`() {
        val group1Id = KdbxUuid(ByteArray(16) { 21 })
        val group2Id = KdbxUuid(ByteArray(16) { 22 })

        val group1 = KdbxGroup(id = group1Id, parentGroupId = rootId, name = "Group1")
        val group2 = KdbxGroup(id = group2Id, parentGroupId = group1Id, name = "Group2")

        val baseDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(group1.copy(subgroups = listOf(group2)))))

        // Local 将 Group1 改名为 FolderA
        val localG1 = group1.copy(name = "FolderA")
        val localDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(localG1.copy(subgroups = listOf(group2)))))

        // Remote 将 Group2 移动至 Root 下
        val remoteG2 = group2.copy(parentGroupId = rootId)
        val remoteDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(group1, remoteG2)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        val foundG1 = result.mergedRoot.findGroup(group1Id)
        val foundG2 = result.mergedRoot.findGroup(group2Id)

        assertNotNull(foundG1)
        assertNotNull(foundG2)
        assertEquals("FolderA", foundG1?.name)
    }
}
