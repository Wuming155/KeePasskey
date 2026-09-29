package com.keepasskey.sync.merge

import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-384：`.kdbx` 并入的合并语义（空底版 + UUID 三方）。
 *
 * 本组用例锁定「另一库独有条目并入、同 UUID 冲突由 KdbxMerger 产出 conflict 清单」
 * 的生产语义——`KdbxMergeController` 正是按此调用 `KdbxMerger.mergeDatabases`。
 */
class KdbxImportMergeSemanticsTest {

    private fun entry(id: KdbxUuid, title: String, password: String = "secret"): KdbxEntry =
        KdbxEntry(
            id = id,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(title, false),
                KdbxConstants.Fields.PASSWORD to ProtectedString(password, true),
                KdbxConstants.Fields.USER_NAME to ProtectedString("user@example.com", false),
                KdbxConstants.Fields.URL to ProtectedString("https://example.com", false)
            ),
            times = KdbxTimes(
                creationTime = Instant.parse("2026-01-01T00:00:00Z"),
                lastModificationTime = Instant.parse("2026-01-02T00:00:00Z")
            )
        )

    private fun rootWith(vararg entries: KdbxEntry): KdbxGroup =
        KdbxGroup(id = ROOT_ID, name = "Root", entries = entries.toList())

    @Test
    fun `另一库独有条目经空底版合并被并入`() {
        val localId = KdbxUuid.random()
        val remoteId = KdbxUuid.random()
        val local = KdbxDatabaseLite(rootGroup = rootWith(entry(localId, "local-only")))
        val remote = KdbxDatabaseLite(rootGroup = rootWith(entry(remoteId, "remote-only")))
        val base = KdbxDatabaseLite(
            rootGroup = KdbxGroup(id = ROOT_ID, name = "Root")
        )

        val result = KdbxMerger.mergeDatabases(base, local, remote)
        val titles = result.mergedRoot.allEntries().map { it.title }.toSet()
        assertTrue(titles.contains("local-only"))
        assertTrue(titles.contains("remote-only"))
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `同 UUID 字段冲突产生 conflict 清单`() {
        val shared = KdbxUuid.random()
        val local = KdbxDatabaseLite(
            rootGroup = rootWith(entry(shared, "same", password = "local-pw"))
        )
        val remote = KdbxDatabaseLite(
            rootGroup = rootWith(entry(shared, "same", password = "remote-pw"))
        )
        val base = KdbxDatabaseLite(rootGroup = KdbxGroup(id = ROOT_ID, name = "Root"))

        val result = KdbxMerger.mergeDatabases(base, local, remote)
        assertEquals(1, result.conflicts.size)
        assertEquals(shared.toHexString(), result.conflicts.first().entryId)
    }

    @Test
    fun `KEEP_LOCAL 语义下冲突条目仍保留本地实例`() {
        // 与 KdbxMergeController 一致：冲突时采用本地树（不把远端密码写回当前库）
        val shared = KdbxUuid.random()
        val local = KdbxDatabaseLite(
            rootGroup = rootWith(entry(shared, "same", password = "local-pw"))
        )
        val remote = KdbxDatabaseLite(
            rootGroup = rootWith(entry(shared, "same", password = "remote-pw"))
        )
        val base = KdbxDatabaseLite(rootGroup = KdbxGroup(id = ROOT_ID, name = "Root"))

        val result = KdbxMerger.mergeDatabases(base, local, remote)
        val surviving = result.mergedRoot.allEntries().first { it.id == shared }
        assertEquals("local-pw", surviving.password?.readString())
    }

    @Test
    fun `合并后墓碑包含对端独有删除`() {
        val deletedRemote = KdbxUuid.random()
        val keptLocal = KdbxUuid.random()
        val local = KdbxDatabaseLite(
            rootGroup = rootWith(entry(keptLocal, "kept"))
        )
        val remote = KdbxDatabaseLite(
            rootGroup = KdbxGroup(id = ROOT_ID, name = "Root"),
            deletedObjects = listOf(
                DeletedObject(
                    id = deletedRemote,
                    deletionTime = Instant.parse("2026-02-01T00:00:00Z")
                )
            )
        )
        val base = KdbxDatabaseLite(rootGroup = KdbxGroup(id = ROOT_ID, name = "Root"))

        val result = KdbxMerger.mergeDatabases(base, local, remote)
        assertTrue(result.mergedDeletedObjects.any { it.id == deletedRemote })
        assertTrue(result.mergedRoot.allEntries().any { it.id == keptLocal })
    }

    private companion object {
        val ROOT_ID: KdbxUuid = KdbxUuid.random()
    }
}
