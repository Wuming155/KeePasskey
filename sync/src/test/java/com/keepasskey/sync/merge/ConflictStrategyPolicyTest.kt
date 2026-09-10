package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ISSUE-P3-03 (43a)：冲突策略决策与「每次询问」决策清单扩充单测。
 *
 * 验收要点：
 * 1. 四种策略 → 处置分支映射穷尽；
 * 2. `PROMPT_USER` 把「双方各自修改过的条目」纳入决策清单（相较自动合并的真实行为差异）；
 * 3. base 不可信（缺失）时不扩充，避免把全库条目变成询问项；
 * 4. 合并引擎已列出的冲突项不重复登记。
 */
class ConflictStrategyPolicyTest {

    private val rootId = KdbxUuid(ByteArray(16) { 0 })

    private fun group(entries: List<KdbxEntry>): KdbxGroup =
        KdbxGroup(id = rootId, name = "Root", entries = entries)

    private fun entry(
        uuidByte: Byte,
        title: String,
        url: String = "https://example.com",
        modifiedAt: Long = 1000L
    ): KdbxEntry = KdbxEntry(
        id = KdbxUuid(ByteArray(16) { uuidByte }),
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            KdbxConstants.Fields.URL to ProtectedString(url, false)
        ),
        times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(modifiedAt))
    )

    @Test
    fun `四种策略映射到互不相同的处置分支`() {
        val dispositions = SyncConflictStrategy.entries.map { ConflictStrategyPolicy.dispositionOf(it) }

        assertEquals(4, dispositions.size)
        assertEquals(4, dispositions.toSet().size)
        assertTrue(dispositions.contains(ConflictDisposition.AutoMerge))
        assertTrue(dispositions.contains(ConflictDisposition.PromptUser))
        assertTrue(dispositions.contains(ConflictDisposition.TakeRemote))
        assertTrue(dispositions.contains(ConflictDisposition.TakeLocal))
    }

    @Test
    fun `每次询问把双方各自修改的条目纳入决策清单`() {
        // base：条目 a 的 URL 为 v1；local 改了 URL，remote 改了标题（不同字段 → 自动合并不会列为冲突）
        val base = KdbxDatabaseLite(group(listOf(entry(1, title = "T", url = "https://v1", modifiedAt = 1000L))))
        val local = KdbxDatabaseLite(group(listOf(entry(1, title = "T", url = "https://v2", modifiedAt = 2000L))))
        val remote = KdbxDatabaseLite(group(listOf(entry(1, title = "T-remote", url = "https://v1", modifiedAt = 3000L))))

        val merged = KdbxMerger.mergeDatabases(base, local, remote)
        assertTrue("不同字段分歧不产生条目级冲突", merged.conflicts.isEmpty())

        val expanded = BothModifiedEntryCollector.collect(
            trustedBase = base,
            local = local,
            remote = remote,
            alreadyConflicted = merged.conflicts
        )

        assertEquals(1, expanded.size)
        assertEquals(entry(1, "T").id.toHexString(), expanded.single().entryId)
        assertTrue(
            "差异字段键须含标题",
            expanded.single().modifiedFields.contains(KdbxConstants.Fields.TITLE)
        )
    }

    @Test
    fun `仅单方修改的条目不进入决策清单`() {
        val base = KdbxDatabaseLite(group(listOf(entry(2, title = "T", modifiedAt = 1000L))))
        val local = KdbxDatabaseLite(group(listOf(entry(2, title = "T", modifiedAt = 1000L))))
        val remote = KdbxDatabaseLite(group(listOf(entry(2, title = "T-remote", modifiedAt = 3000L))))

        val expanded = BothModifiedEntryCollector.collect(base, local, remote, emptyList())

        assertTrue("只有远端修改，无需询问用户", expanded.isEmpty())
    }

    @Test
    fun `base 不可信时不扩充决策清单`() {
        val local = KdbxDatabaseLite(group(listOf(entry(3, title = "A", modifiedAt = 2000L))))
        val remote = KdbxDatabaseLite(group(listOf(entry(3, title = "B", modifiedAt = 3000L))))
        val engineConflicts = KdbxMerger.mergeDatabases(
            KdbxDatabaseLite(group(emptyList())), local, remote
        ).conflicts

        val expanded = BothModifiedEntryCollector.collect(
            trustedBase = null,
            local = local,
            remote = remote,
            alreadyConflicted = engineConflicts
        )

        assertEquals("base 缺失时必须原样返回引擎冲突清单", engineConflicts, expanded)
    }

    @Test
    fun `引擎已列出的冲突项不重复登记`() {
        val base = KdbxDatabaseLite(group(listOf(entry(4, title = "T", modifiedAt = 1000L))))
        val local = KdbxDatabaseLite(group(listOf(entry(4, title = "local", modifiedAt = 2000L))))
        val remote = KdbxDatabaseLite(group(listOf(entry(4, title = "remote", modifiedAt = 3000L))))

        val merged = KdbxMerger.mergeDatabases(base, local, remote)
        assertEquals("同字段分叉必产生条目级冲突", 1, merged.conflicts.size)

        val expanded = BothModifiedEntryCollector.collect(
            trustedBase = base,
            local = local,
            remote = remote,
            alreadyConflicted = merged.conflicts
        )

        assertEquals(1, expanded.size)
        assertEquals(merged.conflicts.single().entryId, expanded.single().entryId)
    }
}
