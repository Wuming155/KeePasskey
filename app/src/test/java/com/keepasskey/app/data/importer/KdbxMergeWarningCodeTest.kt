package com.keepasskey.app.data.importer

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-395：`.kdbx` 并入 0 新增时的报告警示（编码登记 + 语义组装）。
 */
class KdbxMergeWarningCodeTest {

    @Test
    fun `KDBX_MERGE_NO_REMOTE_UNIQUE 编码可反查且稳定`() {
        val reason = ImportWarningReason.fromCode("KDBX_MERGE_NO_REMOTE_UNIQUE")
        assertNotNull("并入 0 新增警示编码必须已登记", reason)
        assertEquals(ImportWarningReason.KDBX_MERGE_NO_REMOTE_UNIQUE, reason)
        assertEquals("KDBX_MERGE_NO_REMOTE_UNIQUE", ImportWarningReason.KDBX_MERGE_NO_REMOTE_UNIQUE.code)
    }

    @Test
    fun `未知编码反查为 null（UI 回退未知警告，不吞）`() {
        assertNull(ImportWarningReason.fromCode("NOT_REGISTERED_CODE"))
    }

    @Test
    fun `对端独有条目并入时不挂 NO_REMOTE_UNIQUE 警告`() {
        val delta = KdbxMergeOutcomeFactory.MergeDelta(
            entriesAdded = 1,
            groupsAdded = 0,
            conflicts = 0,
            customIconsAdded = 0
        )
        val warnings = KdbxMergeOutcomeFactory.mergeWarnings(delta)
        assertTrue(
            "有新增时不得声称「对端无独有对象」",
            warnings.none { it.reason == ImportWarningReason.KDBX_MERGE_NO_REMOTE_UNIQUE.code }
        )
    }

    @Test
    fun `0 新增 0 冲突时挂 NO_REMOTE_UNIQUE 警告`() {
        val delta = KdbxMergeOutcomeFactory.MergeDelta(
            entriesAdded = 0,
            groupsAdded = 0,
            conflicts = 0,
            customIconsAdded = 0
        )
        val warnings = KdbxMergeOutcomeFactory.mergeWarnings(delta)
        assertTrue(
            "0 新增必须给出语义提示",
            warnings.any { it.reason == ImportWarningReason.KDBX_MERGE_NO_REMOTE_UNIQUE.code }
        )
    }

    @Test
    fun `同 UUID 冲突时保留 KEEP_LOCAL 警告且不误报 NO_REMOTE_UNIQUE`() {
        val delta = KdbxMergeOutcomeFactory.MergeDelta(
            entriesAdded = 0,
            groupsAdded = 0,
            conflicts = 2,
            customIconsAdded = 0
        )
        val warnings = KdbxMergeOutcomeFactory.mergeWarnings(delta)
        assertTrue(warnings.any { it.reason.startsWith("conflicts_kept_local:") })
        assertTrue(
            "有冲突时不得声称「对端无独有对象」",
            warnings.none { it.reason == ImportWarningReason.KDBX_MERGE_NO_REMOTE_UNIQUE.code }
        )
    }

    @Test
    fun `空底版合并：对端独有 UUID 被计为新增（自库导出副本则 0 新增）`() {
        val localId = KdbxUuid.random()
        val remoteId = KdbxUuid.random()
        val shared = KdbxUuid.random()
        val rootId = KdbxUuid.random()

        val localEntries = listOf(
            entry(localId, "local"),
            entry(shared, "same", "pw-local")
        )
        val local = KdbxDatabaseLite(rootGroup = KdbxGroup(id = rootId, name = "Root", entries = localEntries))
        val emptyBase = KdbxDatabaseLite(rootGroup = KdbxGroup(id = rootId, name = "Root"))

        // 自库导出副本：UUID 全同 ⇒ 0 新增
        val remoteSelf = KdbxDatabaseLite(
            rootGroup = KdbxGroup(id = rootId, name = "Root", entries = localEntries)
        )
        val selfMerged = KdbxMerger.mergeDatabases(emptyBase, local, remoteSelf)
        assertEquals(
            0,
            selfMerged.mergedRoot.allEntries().map { it.id }.toSet().minus(localEntries.map { it.id }).size
        )
        assertTrue("自库副本合并无冲突", selfMerged.conflicts.isEmpty())

        // 另一库独有条目 ⇒ 计为新增
        val remoteOther = KdbxDatabaseLite(
            rootGroup = KdbxGroup(
                id = rootId,
                name = "Root",
                entries = localEntries + entry(remoteId, "remote-only")
            )
        )
        val otherMerged = KdbxMerger.mergeDatabases(emptyBase, local, remoteOther)
        val otherIds = otherMerged.mergedRoot.allEntries().map { it.id }.toSet()
        val localIds = localEntries.map { it.id }.toSet()
        assertEquals(1, (otherIds - localIds).size)
    }

    private fun entry(id: KdbxUuid, title: String, password: String = "secret"): KdbxEntry =
        KdbxEntry(
            id = id,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(title, false),
                KdbxConstants.Fields.PASSWORD to ProtectedString(password, true)
            ),
            times = KdbxTimes(
                creationTime = Instant.parse("2026-01-01T00:00:00Z"),
                lastModificationTime = Instant.parse("2026-01-02T00:00:00Z")
            )
        )
}
