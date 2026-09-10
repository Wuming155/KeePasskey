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

    @Test
    fun `测试复活条目回退到 previousParentGroup 而非根组`() {
        val entryUuid = KdbxUuid(ByteArray(16) { 31 })
        val groupAId = KdbxUuid(ByteArray(16) { 32 })
        val groupBId = KdbxUuid(ByteArray(16) { 33 })

        val oldTimes = KdbxTimes(
            creationTime = Instant.ofEpochMilli(500L),
            lastModificationTime = Instant.ofEpochMilli(500L)
        )
        val groupA = KdbxGroup(id = groupAId, parentGroupId = rootId, name = "GroupA", times = oldTimes)
        val groupB = KdbxGroup(id = groupBId, parentGroupId = rootId, name = "GroupB", times = oldTimes)

        // 基线：条目位于 GroupA；此前后条目曾从 GroupA 被移动到 GroupB
        val baseEntry = KdbxEntry(
            id = entryUuid,
            parentGroupId = groupBId,
            previousParentGroup = groupAId,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Original", false)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1000L))
        )
        val baseDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(groupA, groupB), entries = listOf(baseEntry)))

        // Local 删除条目（墓碑），分组树保持不变
        val localTombstone = DeletedObject(id = entryUuid, deletionTime = Instant.ofEpochMilli(1500L))
        val localDb = KdbxDatabaseLite(
            rootGroup = createRootGroup(subgroups = listOf(groupA, groupB)),
            deletedObjects = listOf(localTombstone)
        )

        // Remote 修改了条目（修改方胜 → 复活），且远端已删除条目所在分组 GroupB；
        // 移动史留下的 previousParentGroup 指向存活的 GroupA
        val remoteModified = baseEntry.copy(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Remote Modified", false)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(2500L))
        )
        val remoteTombstone = DeletedObject(id = groupBId, deletionTime = Instant.ofEpochMilli(2000L))
        val remoteDb = KdbxDatabaseLite(
            rootGroup = createRootGroup(subgroups = listOf(groupA), entries = listOf(remoteModified)),
            deletedObjects = listOf(remoteTombstone)
        )

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 复活条目的 parentGroupId（GroupB）已不存活，应回退到 previousParentGroup（GroupA）而非根组
        val revived = result.mergedRoot.findEntry(entryUuid)
        assertNotNull(revived)
        assertEquals(groupAId, revived?.parentGroupId)
    }

    @Test
    fun `测试条目挂载点丢失且无移动史时归属根组`() {
        val entryUuid = KdbxUuid(ByteArray(16) { 41 })
        val groupAId = KdbxUuid(ByteArray(16) { 42 })

        val oldTimes = KdbxTimes(
            creationTime = Instant.ofEpochMilli(500L),
            lastModificationTime = Instant.ofEpochMilli(500L)
        )
        val groupA = KdbxGroup(id = groupAId, parentGroupId = rootId, name = "GroupA", times = oldTimes)

        val baseEntry = KdbxEntry(
            id = entryUuid,
            parentGroupId = groupAId,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Original", false)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1000L))
        )
        val baseDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(groupA), entries = listOf(baseEntry)))

        // Local 删除条目（墓碑），分组树保持不变
        val localDb = KdbxDatabaseLite(
            rootGroup = createRootGroup(subgroups = listOf(groupA)),
            deletedObjects = listOf(DeletedObject(entryUuid, Instant.ofEpochMilli(1500L)))
        )

        // Remote 修改条目（修改方胜 → 复活），且远端已删除其挂载组 GroupA；
        // 条目无 previousParentGroup 移动史可回退
        val remoteModified = baseEntry.copy(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Remote Modified", false)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(2500L))
        )
        val remoteTombstone = DeletedObject(id = groupAId, deletionTime = Instant.ofEpochMilli(2000L))
        val remoteDb = KdbxDatabaseLite(
            rootGroup = createRootGroup(entries = listOf(remoteModified)),
            deletedObjects = listOf(remoteTombstone)
        )

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 挂载组已丢失且无移动史：条目必须自愈归属根组，而非被静默丢弃
        val revived = result.mergedRoot.findEntry(entryUuid)
        assertNotNull(revived)
        assertEquals("Remote Modified", revived?.title)
        assertEquals(rootId, revived?.parentGroupId)
    }

    @Test
    fun `测试分组挂载点丢失自愈归属根组`() {
        val groupAId = KdbxUuid(ByteArray(16) { 51 })
        val groupBId = KdbxUuid(ByteArray(16) { 52 })

        val oldTimes = KdbxTimes(
            creationTime = Instant.ofEpochMilli(500L),
            lastModificationTime = Instant.ofEpochMilli(500L)
        )
        val groupA = KdbxGroup(id = groupAId, parentGroupId = rootId, name = "GroupA", times = oldTimes)
        val groupB = KdbxGroup(id = groupBId, parentGroupId = groupAId, name = "GroupB", times = oldTimes)

        val baseDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(groupA.copy(subgroups = listOf(groupB)))))

        // Local 保持原树不变
        val localDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(groupA.copy(subgroups = listOf(groupB)))))

        // Remote 删除了 GroupA（墓碑），GroupB 幸存但其 parentGroupId 仍悬空指向 GroupA
        val remoteDb = KdbxDatabaseLite(
            rootGroup = createRootGroup(subgroups = listOf(groupB)),
            deletedObjects = listOf(DeletedObject(groupAId, Instant.ofEpochMilli(2000L)))
        )

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // GroupA 未存活，GroupB 的挂载点丢失：必须自愈归属根组而非静默丢弃
        assertNull(result.mergedRoot.findGroup(groupAId))
        val foundB = result.mergedRoot.findGroup(groupBId)
        assertNotNull(foundB)
        assertEquals(rootId, foundB?.parentGroupId)
    }

    @Test
    fun `测试分组互指环路检测回退根组且无对象丢失`() {
        val groupAId = KdbxUuid(ByteArray(16) { 61 })
        val groupBId = KdbxUuid(ByteArray(16) { 62 })

        val oldTimes = KdbxTimes(
            creationTime = Instant.ofEpochMilli(500L),
            lastModificationTime = Instant.ofEpochMilli(500L)
        )
        val groupA = KdbxGroup(id = groupAId, parentGroupId = rootId, name = "GroupA", times = oldTimes)
        val groupB = KdbxGroup(id = groupBId, parentGroupId = rootId, name = "GroupB", times = oldTimes)

        val baseDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(groupA, groupB)))

        // Local 将 GroupA 移入 GroupB；Remote 将 GroupB 移入 GroupA → 合并后互指成环
        val localDb = KdbxDatabaseLite(
            createRootGroup(subgroups = listOf(groupA.copy(parentGroupId = groupBId), groupB))
        )
        val remoteDb = KdbxDatabaseLite(
            createRootGroup(subgroups = listOf(groupA, groupB.copy(parentGroupId = groupAId)))
        )

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 环路必须被打破且两组均不得丢失：至少一方被回退挂载到根组
        val foundA = result.mergedRoot.findGroup(groupAId)
        val foundB = result.mergedRoot.findGroup(groupBId)
        assertNotNull(foundA)
        assertNotNull(foundB)
        assertTrue(
            "A、B 互指成环未被打破",
            foundA?.parentGroupId == rootId || foundB?.parentGroupId == rootId
        )
    }

    @Test
    fun `测试双方修改同组不同属性自动合并`() {
        val groupId = KdbxUuid(ByteArray(16) { 71 })
        val baseTimes = KdbxTimes(
            creationTime = Instant.ofEpochMilli(500L),
            lastModificationTime = Instant.ofEpochMilli(500L)
        )
        val baseGroup = KdbxGroup(id = groupId, parentGroupId = rootId, name = "Base", times = baseTimes)

        val baseDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(baseGroup)))

        // Local 重命名分组
        val localGroup = baseGroup.copy(
            name = "RenamedByLocal",
            times = baseTimes.copy(lastModificationTime = Instant.ofEpochMilli(2000L))
        )
        val localDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(localGroup)))

        // Remote 修改备注
        val remoteGroup = baseGroup.copy(
            notes = "Remote Notes",
            times = baseTimes.copy(lastModificationTime = Instant.ofEpochMilli(1500L))
        )
        val remoteDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(remoteGroup)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 不同属性的修改必须自动合并保留双方变更，且不产生冲突
        assertTrue(result.conflicts.isEmpty())
        val merged = result.mergedRoot.findGroup(groupId)
        assertNotNull(merged)
        assertEquals("RenamedByLocal", merged?.name)
        assertEquals("Remote Notes", merged?.notes)
    }

    @Test
    fun `测试双方重命名同组时间戳晚者胜`() {
        val groupId = KdbxUuid(ByteArray(16) { 72 })
        val baseTimes = KdbxTimes(
            creationTime = Instant.ofEpochMilli(500L),
            lastModificationTime = Instant.ofEpochMilli(500L)
        )
        val baseGroup = KdbxGroup(id = groupId, parentGroupId = rootId, name = "Base", times = baseTimes)

        val baseDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(baseGroup)))

        val localGroup = baseGroup.copy(
            name = "LocalName",
            times = baseTimes.copy(lastModificationTime = Instant.ofEpochMilli(1500L))
        )
        val localDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(localGroup)))

        val remoteGroup = baseGroup.copy(
            name = "RemoteName",
            times = baseTimes.copy(lastModificationTime = Instant.ofEpochMilli(3000L))
        )
        val remoteDb = KdbxDatabaseLite(createRootGroup(subgroups = listOf(remoteGroup)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 同属性冲突按时间戳仲裁：远端更晚 → 远端名胜出
        val merged = result.mergedRoot.findGroup(groupId)
        assertNotNull(merged)
        assertEquals("RemoteName", merged?.name)
    }

    @Test
    fun `测试双方修改同字段本地时间戳更晚时采纳本地值`() {
        val entryUuid = KdbxUuid(ByteArray(16) { 73 })
        val baseEntry = KdbxEntry(
            id = entryUuid,
            fields = mapOf(KdbxConstants.Fields.PASSWORD to ProtectedString("pwd_base", true)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1000L))
        )

        val baseDb = KdbxDatabaseLite(createRootGroup(entries = listOf(baseEntry)))

        // Local 修改密码且时间戳更晚
        val localEntry = baseEntry.withField(KdbxConstants.Fields.PASSWORD, "pwd_local")
            .copy(times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(3000L)))
        val localDb = KdbxDatabaseLite(createRootGroup(entries = listOf(localEntry)))

        // Remote 修改密码但时间戳更早
        val remoteEntry = baseEntry.withField(KdbxConstants.Fields.PASSWORD, "pwd_remote")
            .copy(times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1500L)))
        val remoteDb = KdbxDatabaseLite(createRootGroup(entries = listOf(remoteEntry)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 仍须生成冲突清单供用户知晓，但字段级仲裁采纳时间戳更晚的本地值
        assertEquals(1, result.conflicts.size)
        assertTrue(result.conflicts.first().modifiedFields.contains("密码 (Password)"))
        val merged = result.mergedRoot.findEntry(entryUuid)
        assertNotNull(merged)
        assertEquals("pwd_local", merged?.password?.readString())
    }

    @Test
    fun `测试单侧删除字段自动合并生效`() {
        val entryUuid = KdbxUuid(ByteArray(16) { 74 })
        val baseEntry = KdbxEntry(
            id = entryUuid,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Base Title", false),
                KdbxConstants.Fields.NOTES to ProtectedString("Old Notes", false)
            ),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1000L))
        )

        val baseDb = KdbxDatabaseLite(createRootGroup(entries = listOf(baseEntry)))

        // Local 删除了 NOTES 字段
        val localEntry = baseEntry.copy(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Base Title", false)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(2000L))
        )
        val localDb = KdbxDatabaseLite(createRootGroup(entries = listOf(localEntry)))

        // Remote 未动该条目
        val remoteDb = KdbxDatabaseLite(createRootGroup(entries = listOf(baseEntry)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 单侧字段删除属修改行为：删除方胜，字段从合并结果中移除且不产生冲突
        assertTrue(result.conflicts.isEmpty())
        val merged = result.mergedRoot.findEntry(entryUuid)
        assertNotNull(merged)
        assertFalse(merged!!.fields.containsKey(KdbxConstants.Fields.NOTES))
        assertEquals("Base Title", merged.title)
    }

    @Test
    fun `测试双方新增同名自定义字段冲突时间戳仲裁`() {
        val entryUuid = KdbxUuid(ByteArray(16) { 75 })
        val baseEntry = KdbxEntry(
            id = entryUuid,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Base Title", false)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1000L))
        )

        val baseDb = KdbxDatabaseLite(createRootGroup(entries = listOf(baseEntry)))

        // Local 新增自定义字段 OTP（时间戳更晚）
        val localEntry = baseEntry.copy(
            customFields = listOf(KdbxCustomField("OTP", ProtectedString("local-otp", true))),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(3000L))
        )
        val localDb = KdbxDatabaseLite(createRootGroup(entries = listOf(localEntry)))

        // Remote 新增同名但不同值的自定义字段（时间戳更早）
        val remoteEntry = baseEntry.copy(
            customFields = listOf(KdbxCustomField("OTP", ProtectedString("remote-otp", true))),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1500L))
        )
        val remoteDb = KdbxDatabaseLite(createRootGroup(entries = listOf(remoteEntry)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 同名自定义字段不同值：生成冲突清单，字段级仲裁采纳时间戳更晚的本地值
        assertEquals(1, result.conflicts.size)
        assertTrue(result.conflicts.first().modifiedFields.contains("自定义字段: OTP"))
        val merged = result.mergedRoot.findEntry(entryUuid)
        assertNotNull(merged)
        assertEquals(1, merged?.customFields?.size)
        assertEquals("local-otp", merged?.customFields?.first()?.value?.readString())
    }

    @Test
    fun `测试单侧新建条目与分组不静默丢弃`() {
        val baseEntryId = KdbxUuid(ByteArray(16) { 81 })
        val newEntryId = KdbxUuid(ByteArray(16) { 82 })
        val newGroupId = KdbxUuid(ByteArray(16) { 83 })

        val baseEntry = KdbxEntry(
            id = baseEntryId,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Existing", false)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1000L))
        )
        val baseDb = KdbxDatabaseLite(createRootGroup(entries = listOf(baseEntry)))

        // Local 新建分组与条目（base 与远端均无记录、无墓碑）
        val newGroup = KdbxGroup(id = newGroupId, parentGroupId = rootId, name = "NewGroup")
        val newEntry = KdbxEntry(
            id = newEntryId,
            parentGroupId = newGroupId,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Created By Local", false)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(2000L))
        )
        val localDb = KdbxDatabaseLite(
            createRootGroup(subgroups = listOf(newGroup), entries = listOf(baseEntry, newEntry))
        )

        // Remote 保持基线不变
        val remoteDb = KdbxDatabaseLite(createRootGroup(entries = listOf(baseEntry)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 单侧新建对象必须完整保留，挂载关系不得丢失
        assertNotNull(result.mergedRoot.findGroup(newGroupId))
        val mergedNewEntry = result.mergedRoot.findEntry(newEntryId)
        assertNotNull(mergedNewEntry)
        assertEquals(newGroupId, mergedNewEntry?.parentGroupId)
        assertEquals("Created By Local", mergedNewEntry?.title)
        assertNotNull(result.mergedRoot.findEntry(baseEntryId))
    }

    @Test
    fun `测试冲突条目历史三方并集去重升序`() {
        val entryUuid = KdbxUuid(ByteArray(16) { 84 })

        fun historySnapshot(title: String, modMillis: Long) = KdbxEntry(
            id = entryUuid,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, false)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(modMillis))
        )

        // Base 持有最旧历史快照 (t=500)
        val baseEntry = KdbxEntry(
            id = entryUuid,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Base Title", false)),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1000L)),
            history = listOf(historySnapshot("Snapshot-500", 500L))
        )
        val baseDb = KdbxDatabaseLite(createRootGroup(entries = listOf(baseEntry)))

        // Local 修改密码并持有 t=2000 快照
        val localEntry = baseEntry.withField(KdbxConstants.Fields.PASSWORD, "pwd_local")
            .copy(
                times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(3000L)),
                history = listOf(
                    historySnapshot("Snapshot-500", 500L),
                    historySnapshot("Snapshot-2000", 2000L)
                )
            )
        val localDb = KdbxDatabaseLite(createRootGroup(entries = listOf(localEntry)))

        // Remote 修改密码并持有 t=1500 快照
        val remoteEntry = baseEntry.withField(KdbxConstants.Fields.PASSWORD, "pwd_remote")
            .copy(
                times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(2000L)),
                history = listOf(historySnapshot("Snapshot-1500", 1500L))
            )
        val remoteDb = KdbxDatabaseLite(createRootGroup(entries = listOf(remoteEntry)))

        val result = KdbxMerger.mergeDatabases(baseDb, localDb, remoteDb)

        // 三方历史并集按最后修改时间去重后升序排列（对齐官方 MergeIn）
        assertEquals(1, result.conflicts.size)
        val merged = result.mergedRoot.findEntry(entryUuid)
        assertNotNull(merged)
        assertEquals(
            listOf(500L, 1500L, 2000L),
            merged?.history?.map { it.times.lastModificationTime.toEpochMilli() }
        )
    }
}
