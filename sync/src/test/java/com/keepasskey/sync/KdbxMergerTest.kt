package com.keepasskey.sync

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * KdbxMerger 冲突解决与合并产物回归测试。
 */
class KdbxMergerTest {

    private val rootId = KdbxUuid(ByteArray(16) { 9 })

    private fun entryWith(
        id: KdbxUuid,
        title: String,
        modifiedMillis: Long,
        history: List<KdbxEntry> = emptyList()
    ) = KdbxEntry(
        id = id,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false)
        ),
        times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(modifiedMillis)),
        history = history
    )

    private fun rootWith(entries: List<KdbxEntry>) = KdbxGroup(
        id = rootId,
        name = "Root",
        entries = entries
    )

    @Test
    fun `测试 DUPLICATE_BOTH 冲突副本换新 UUID 且本地原条目保留`() {
        val sharedId = KdbxUuid(ByteArray(16) { 7 })
        val localEntry = entryWith(sharedId, "Entry", modifiedMillis = 2000L).copy(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Entry", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("LocalPwd", isProtected = true)
            )
        )
        val remoteEntry = localEntry.copy(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Entry", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("RemotePwd", isProtected = true)
            )
        )
        val pair = ConflictedEntryPair(
            entryId = sharedId.toHexString(),
            localEntry = localEntry,
            remoteEntry = remoteEntry,
            modifiedFields = listOf("密码 (Password)")
        )

        val resolved = KdbxMerger.resolveConflict(pair, ConflictResolutionChoice.DUPLICATE_BOTH)

        assertEquals(2, resolved.size)
        // 本地原条目保留原 UUID 与原密码
        assertEquals(sharedId, resolved[0].id)
        assertEquals(ProtectedString("LocalPwd", isProtected = true), resolved[0].password)
        // 副本换新 UUID（KDBX 要求 UUID 全局唯一，同 UUID 副本会在应用回分组树时覆盖原条目）
        assertNotEquals(sharedId, resolved[1].id)
        assertEquals("Entry (云端冲突副本)", resolved[1].title)
        assertEquals(ProtectedString("RemotePwd", isProtected = true), resolved[1].password)
    }

    @Test
    fun `测试双方修改合并时历史版本三方并集`() {
        val sharedId = KdbxUuid(ByteArray(16) { 3 })
        val baseHistory = entryWith(sharedId, "v0", modifiedMillis = 1000L)
        val localHistory = entryWith(sharedId, "v1-local", modifiedMillis = 2000L)
        val remoteHistory = entryWith(sharedId, "v1-remote", modifiedMillis = 3000L)

        val base = KdbxDatabaseLite(
            rootWith(listOf(entryWith(sharedId, "v0", modifiedMillis = 1000L))),
            deletedObjects = emptyList()
        )
        val local = KdbxDatabaseLite(
            rootWith(
                listOf(
                    entryWith(sharedId, "v2-local", modifiedMillis = 5000L)
                        .copy(history = listOf(baseHistory, localHistory))
                )
            )
        )
        val remote = KdbxDatabaseLite(
            rootWith(
                listOf(
                    entryWith(sharedId, "v2-remote", modifiedMillis = 6000L)
                        .copy(history = listOf(baseHistory, remoteHistory))
                )
            )
        )

        val result = KdbxMerger.mergeDatabases(base, local, remote)

        // 标题双方均修改且不同 -> 冲突清单
        assertTrue(result.conflicts.isNotEmpty())
        // 合并产物（mergedRoot）中的条目历史应为三方并集并按时间升序
        val merged = result.mergedRoot.allEntries().first { it.id == sharedId }
        val historyTimes = merged.history.map { it.times.lastModificationTime.toEpochMilli() }
        assertEquals(listOf(1000L, 2000L, 3000L), historyTimes)
    }

    @Test
    fun `测试单侧新建条目与分组进入合并产物`() {
        val sharedId = KdbxUuid(ByteArray(16) { 4 })
        val localNewId = KdbxUuid(ByteArray(16) { 6 })
        val remoteNewId = KdbxUuid(ByteArray(16) { 5 })
        val newGroupId = KdbxUuid(ByteArray(16) { 8 })

        val base = KdbxDatabaseLite(
            rootWith(listOf(entryWith(sharedId, "shared", modifiedMillis = 1000L)))
        )
        val local = KdbxDatabaseLite(
            KdbxGroup(
                id = rootId,
                name = "Root",
                entries = listOf(
                    entryWith(sharedId, "shared", modifiedMillis = 1000L),
                    entryWith(localNewId, "local-new", modifiedMillis = 5000L)
                ),
                subgroups = listOf(KdbxGroup(id = newGroupId, name = "local-new-group"))
            )
        )
        val remote = KdbxDatabaseLite(
            rootWith(
                listOf(
                    entryWith(sharedId, "shared", modifiedMillis = 1000L),
                    entryWith(remoteNewId, "remote-new", modifiedMillis = 6100L)
                )
            )
        )

        val result = KdbxMerger.mergeDatabases(base, local, remote)

        val entryIds = result.mergedRoot.allEntries().map { it.id }
        val groupIds = result.mergedRoot.allGroups().map { it.id }
        // 单侧新建的条目（双方各自新建）与分组都必须存活，不得被合并静默丢弃
        assertTrue(entryIds.contains(localNewId))
        assertTrue(entryIds.contains(remoteNewId))
        assertTrue(groupIds.contains(newGroupId))
    }

    @Test
    fun `测试存在冲突时远端新增条目仍保留在合并产物中`() {
        val sharedId = KdbxUuid(ByteArray(16) { 4 })
        val remoteNewId = KdbxUuid(ByteArray(16) { 5 })

        val base = KdbxDatabaseLite(
            rootWith(listOf(entryWith(sharedId, "v1", modifiedMillis = 1000L)))
        )
        val local = KdbxDatabaseLite(
            rootWith(listOf(entryWith(sharedId, "v2-local", modifiedMillis = 5000L)))
        )
        val remote = KdbxDatabaseLite(
            rootWith(
                listOf(
                    entryWith(sharedId, "v2-remote", modifiedMillis = 6000L),
                    entryWith(remoteNewId, "remote-new-entry", modifiedMillis = 6100L)
                )
            )
        )

        val result = KdbxMerger.mergeDatabases(base, local, remote)

        // 存在同字段冲突 -> 需用户决策
        assertTrue(result.conflicts.isNotEmpty())
        // 但远端新增条目必须已进入合并产物：resolveConflicts 以 mergedRoot 为底版应用决策，
        // 决策路径不得丢失远端新增数据
        val ids = result.mergedRoot.allEntries().map { it.id }
        assertTrue(ids.contains(remoteNewId))
    }
}
