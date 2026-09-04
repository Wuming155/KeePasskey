package com.keepasskey.sync

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.KdbxMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * KdbxMerger 三方同步合并算法单元测试
 */
class KdbxMergerTest {

    @Test
    fun `测试两端无冲突自动合并（一端新增、一端未修改）`() {
        val lastSync = 1000L
        val entry1 = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 1 }),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(2000L))
        )
        val entry2 = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 2 }),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(2000L))
        )

        val (merged, conflicts) = KdbxMerger.detectConflictsAndMergeAuto(
            localEntries = listOf(entry1),
            remoteEntries = listOf(entry2),
            lastSyncTimestamp = lastSync
        )

        assertEquals(2, merged.size)
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `测试两端同时修改触发冲突识别与解决`() {
        val lastSync = 1000L
        val uuid = KdbxUuid(ByteArray(16) { 3 })

        val localEntry = KdbxEntry(
            id = uuid,
            fields = mapOf(
                com.keepasskey.core.model.KdbxConstants.Fields.TITLE to ProtectedString("Shared Entry", isProtected = false),
                com.keepasskey.core.model.KdbxConstants.Fields.PASSWORD to ProtectedString("PasswordLocal#1", isProtected = true)
            ),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1500L))
        )

        val remoteEntry = KdbxEntry(
            id = uuid,
            fields = mapOf(
                com.keepasskey.core.model.KdbxConstants.Fields.TITLE to ProtectedString("Shared Entry", isProtected = false),
                com.keepasskey.core.model.KdbxConstants.Fields.PASSWORD to ProtectedString("PasswordRemote#2", isProtected = true)
            ),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1600L))
        )

        val (merged, conflicts) = KdbxMerger.detectConflictsAndMergeAuto(
            localEntries = listOf(localEntry),
            remoteEntries = listOf(remoteEntry),
            lastSyncTimestamp = lastSync
        )

        assertEquals(0, merged.size)
        assertEquals(1, conflicts.size)
        val conflict = conflicts.first()
        assertTrue(conflict.modifiedFields.contains("密码 (Password)"))

        // 测试解决冲突：保留副本
        val resolved = KdbxMerger.resolveConflict(conflict, ConflictResolutionChoice.DUPLICATE_BOTH)
        assertEquals(2, resolved.size)
    }
}
