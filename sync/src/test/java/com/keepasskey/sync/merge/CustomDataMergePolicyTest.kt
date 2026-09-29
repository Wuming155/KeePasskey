package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ISSUE-P2-385 / P2-387：customData 合并与 SignCount max 特例（JVM）。
 */
class CustomDataMergePolicyTest {

    private fun entry(
        id: KdbxUuid = KdbxUuid.random(),
        customData: Map<String, String> = emptyMap(),
        customFields: List<KdbxCustomField> = emptyList(),
        modifiedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
    ): KdbxEntry = KdbxEntry(
        id = id,
        parentGroupId = null,
        fields = emptyMap(),
        customFields = customFields,
        customData = customData,
        times = KdbxTimes(lastModificationTime = modifiedAt)
    )

    @Test
    fun `isModified 含 customData 差异`() {
        val id = KdbxUuid.random()
        val base = entry(id, customData = emptyMap())
        val local = entry(id, customData = emptyMap())
        val remote = entry(id, customData = mapOf("KeePassXC.Browser" to "key1"))
        assertFalse(KdbxEntryMerger.isModified(base, local))
        assertTrue(KdbxEntryMerger.isModified(base, remote))
        assertTrue(KdbxEntryMerger.isModified(local, remote))
    }

    @Test
    fun `远端新增 customData 键在合并后保留`() {
        val id = KdbxUuid.random()
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        val base = entry(id, emptyMap(), modifiedAt = t0)
        val local = entry(id, emptyMap(), modifiedAt = t0)
        val remote = entry(
            id,
            customData = mapOf("KeePassXC.Browser" to "browser-key"),
            modifiedAt = t0.plusSeconds(60)
        )
        val merged = KdbxEntryMerger.mergeCustomData(base, local, remote)
        assertEquals(mapOf("KeePassXC.Browser" to "browser-key"), merged)
    }

    @Test
    fun `双侧同键不同值按 LMT 取胜`() {
        val id = KdbxUuid.random()
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        val t1 = t0.plusSeconds(60)
        val base = entry(id, emptyMap(), modifiedAt = t0)
        val local = entry(id, customData = mapOf("k" to "local"), modifiedAt = t0)
        val remote = entry(id, customData = mapOf("k" to "remote"), modifiedAt = t1)
        val merged = KdbxEntryMerger.mergeCustomData(base, local, remote)
        assertEquals(mapOf("k" to "remote"), merged)
    }

    @Test
    fun `SignCount 取 max 而非 LWW`() {
        val id = KdbxUuid.random()
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        val t1 = t0.plusSeconds(1)
        val signKey = PasskeyData.FIELD_SIGN_COUNT
        fun field(v: String) = listOf(
            KdbxCustomField(signKey, ProtectedString(v, isProtected = false))
        )
        // 远端更晚但计数更小：max 应取本地
        val local = entry(id, customFields = field("10"), modifiedAt = t0)
        val remote = entry(id, customFields = field("3"), modifiedAt = t1)
        val base = entry(id, customFields = field("1"), modifiedAt = t0)
        val pairs = KdbxEntryMerger.mergeSurvivingEntries(
            allEntryUuids = setOf(id),
            baseEntries = mapOf(id to base),
            localEntries = mapOf(id to local),
            remoteEntries = mapOf(id to remote),
            localDeleted = emptyMap(),
            remoteDeleted = emptyMap()
        )
        val merged = pairs.first.single()
        val sign = merged.customFields.first { it.key == signKey }.value.readString()
        assertEquals("10", sign)
    }

    @Test
    fun `组级 customData 合并保留第三方键`() {
        val gid = KdbxUuid.random()
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        val t1 = t0.plusSeconds(30)
        val base = KdbxGroup(
            id = gid,
            parentGroupId = null,
            name = "G",
            customData = emptyMap(),
            times = KdbxTimes(lastModificationTime = t0)
        )
        val local = base.copy(customData = emptyMap())
        val remote = base.copy(
            customData = mapOf("KeePassXC.SecretService" to "exposed"),
            times = KdbxTimes(lastModificationTime = t1)
        )
        val merged = KdbxGroupMerger.mergeCustomDataMap(base.customData, local.customData, remote.customData, t0, t1)
        assertEquals(mapOf("KeePassXC.SecretService" to "exposed"), merged)
    }

    @Test
    fun `组级 isGroupModified 计入 customData 差异`() {
        // 双方都改了 name 时 customData 应并入结果
        val gid = KdbxUuid.random()
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        val t1 = t0.plusSeconds(10)
        val baseRoot = KdbxGroup(
            id = gid,
            parentGroupId = null,
            name = "Base",
            customData = emptyMap(),
            times = KdbxTimes(lastModificationTime = t0)
        )
        val localRoot = baseRoot.copy(
            name = "Local",
            customData = mapOf("a" to "1"),
            times = KdbxTimes(lastModificationTime = t0.plusSeconds(20))
        )
        val remoteRoot = baseRoot.copy(
            name = "Remote",
            customData = mapOf("b" to "2"),
            times = KdbxTimes(lastModificationTime = t1.plusSeconds(40))
        )
        val result = KdbxMerger.mergeDatabases(
            base = com.keepasskey.sync.merge.KdbxDatabaseLite(baseRoot),
            local = com.keepasskey.sync.merge.KdbxDatabaseLite(localRoot),
            remote = com.keepasskey.sync.merge.KdbxDatabaseLite(remoteRoot)
        )
        assertEquals(mapOf("a" to "1", "b" to "2"), result.mergedRoot.customData)
    }
}
