package com.keepasskey.database.history

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * HistoryManager 版本历史归档与回滚单元测试
 */
class HistoryManagerTest {

    @Test
    fun `测试历史快照记录与一键回滚`() {
        val entryId = KdbxUuid(ByteArray(16) { 5 })
        val v1 = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Title V1", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("PassV1", isProtected = true)
            )
        )

        val v2Draft = v1.withField(KdbxConstants.Fields.TITLE, ProtectedString("Title V2", isProtected = false))
        val v2 = HistoryManager.recordHistorySnapshot(v1, v2Draft)

        assertEquals("Title V2", v2.title)
        assertEquals(1, v2.history.size)
        assertEquals("Title V1", v2.history.first().title)

        // 执行回滚至 V1
        val rolledBack = HistoryManager.rollbackToSnapshot(v2, 0)
        assertEquals("Title V1", rolledBack.title)
        assertEquals("PassV1", rolledBack.password?.readString())
        assertEquals(entryId, rolledBack.id)
    }

    private fun entryOf(title: String): KdbxEntry {
        return KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false)
            )
        )
    }

    private fun nextVersion(current: KdbxEntry, title: String): KdbxEntry {
        return current.withField(
            KdbxConstants.Fields.TITLE,
            ProtectedString(title, isProtected = false)
        )
    }

    /**
     * P3-4 回归：数量上限遵从传入的 maxHistoryItems（不再是写死的 10），
     * 修剪自最旧端移除（history 列表头部为最新）。
     */
    @Test
    fun `数量上限修剪遵从传入的 maxHistoryItems`() {
        var current = entryOf("V0")
        for (i in 1..5) {
            current = HistoryManager.recordHistorySnapshot(
                currentEntry = current,
                newEntry = nextVersion(current, "V$i"),
                maxHistoryItems = 3,
                maxHistorySize = HistoryManager.UNLIMITED_SIZE
            )
        }
        assertEquals(3, current.history.size)
        // 头部为最新：依次为 V4 / V3 / V2 快照，最旧的 V0/V1 被修剪
        assertEquals(listOf("V4", "V3", "V2"), current.history.map { it.title })
    }

    /**
     * P3-4 回归：-1 表示不限制——数量上限与体积上限均不修剪。
     */
    @Test
    fun `负一表示数量与体积均不限制`() {
        var current = entryOf("V0")
        for (i in 1..12) {
            current = HistoryManager.recordHistorySnapshot(
                currentEntry = current,
                newEntry = nextVersion(current, "V$i"),
                maxHistoryItems = HistoryManager.UNLIMITED,
                maxHistorySize = HistoryManager.UNLIMITED_SIZE
            )
        }
        assertEquals(12, current.history.size)
    }

    /**
     * P3-4 回归：体积上限按官方规则修剪——历史总大小（估算口径对齐官方
     * PwEntry.GetSize：字段键名+值长度+附件+标签+AutoType+固定开销）超过上限时
     * 自最旧端逐项移除直至达标。
     * 每个快照大小 = 12（固定） + 5+2（Title） + 5+1000（Notes）= 1024 字节。
     */
    @Test
    fun `体积上限修剪移除最旧历史`() {
        val notes = "x".repeat(1000)
        var current = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("V0", isProtected = false),
                KdbxConstants.Fields.NOTES to ProtectedString(notes, isProtected = false)
            )
        )
        for (i in 1..3) {
            current = HistoryManager.recordHistorySnapshot(
                currentEntry = current,
                newEntry = nextVersion(current, "V$i"),
                maxHistoryItems = HistoryManager.UNLIMITED,
                maxHistorySize = 2048L // 恰好容纳 2 个 1024 字节的快照
            )
        }
        // 3 个快照共 3072 字节 > 2048：最旧的 V0 快照被移除，保留最新的 V2 / V1
        assertEquals(2, current.history.size)
        assertEquals(listOf("V2", "V1"), current.history.map { it.title })
    }

    /**
     * P3-4 回归：数量上限为 0（官方语义：不保留历史）时不归档任何快照。
     */
    @Test
    fun `零数量上限不保留任何历史`() {
        val v1 = entryOf("V1")
        val v2 = HistoryManager.recordHistorySnapshot(
            currentEntry = v1,
            newEntry = nextVersion(v1, "V2"),
            maxHistoryItems = 0,
            maxHistorySize = HistoryManager.UNLIMITED_SIZE
        )
        assertEquals("V2", v2.title)
        assertTrue(v2.history.isEmpty())
    }

    /**
     * P3-4 回归：默认容量为官方标准值（10 条 / 6 MiB），未显式传参时按其修剪。
     */
    @Test
    fun `默认容量为官方 10 条与 6MiB`() {
        assertEquals(10, HistoryManager.DEFAULT_MAX_HISTORY_ITEMS)
        assertEquals(6L * 1024 * 1024, HistoryManager.DEFAULT_MAX_HISTORY_SIZE)

        var current = entryOf("V0")
        for (i in 1..15) {
            current = HistoryManager.recordHistorySnapshot(
                currentEntry = current,
                newEntry = nextVersion(current, "V$i")
            )
        }
        assertEquals(10, current.history.size)
        assertEquals("V14", current.history.first().title)
        assertEquals("V5", current.history.last().title)
    }

    /**
     * ISSUE-P1-03：官方 DatabaseOperationsForm「删除 N 天前的历史条目」维护算法——
     * 仅保留 lastModificationTime 晚于 (now - maintenanceHistoryDays) 的历史快照。
     */
    @Test
    fun `保留期修剪移除超期历史快照`() {
        val now = Instant.parse("2026-09-09T00:00:00Z")
        val recent = historySnapshot("recent", now.minusSeconds(86_400L * 10))   // 10 天前
        val stale = historySnapshot("stale", now.minusSeconds(86_400L * 400))    // 400 天前
        val ancient = historySnapshot("ancient", now.minusSeconds(86_400L * 800)) // 800 天前
        val entry = entryOf("current").copy(history = listOf(recent, stale, ancient))

        val pruned = HistoryManager.pruneHistoryByAge(entry, maintenanceHistoryDays = 365, now = now)

        assertEquals(1, pruned.history.size)
        assertEquals("recent", pruned.history.first().title)
        // 当前条目自身不受影响
        assertEquals("current", pruned.title)
    }

    /**
     * ISSUE-P1-03：maintenanceHistoryDays <= 0 视为「未配置保留期」，不修剪并原样返回同一实例。
     */
    @Test
    fun `保留期为零或负值不执行修剪`() {
        val now = Instant.parse("2026-09-09T00:00:00Z")
        val entry = entryOf("current").copy(
            history = listOf(historySnapshot("ancient", now.minusSeconds(86_400L * 2000)))
        )
        assertSame(entry, HistoryManager.pruneHistoryByAge(entry, 0, now))
        assertSame(entry, HistoryManager.pruneHistoryByAge(entry, -1, now))
    }

    /**
     * ISSUE-P1-03：无快照超期时返回同一实例（保存路径据此免拷贝）。
     */
    @Test
    fun `无超期快照时返回同一实例`() {
        val now = Instant.parse("2026-09-09T00:00:00Z")
        val entry = entryOf("current").copy(
            history = listOf(historySnapshot("fresh", now.minusSeconds(86_400L)))
        )
        assertSame(entry, HistoryManager.pruneHistoryByAge(entry, 365, now))
    }

    /**
     * ISSUE-P1-03：整树维护递归覆盖嵌套子分组，且全树无变化时返回同一根实例。
     */
    @Test
    fun `整树历史保留期维护递归且无变化时免拷贝`() {
        val now = Instant.parse("2026-09-09T00:00:00Z")

        // 场景 A：全树历史均在保留期内 → 返回同一根实例
        val freshEntry = entryOf("fresh").copy(
            history = listOf(historySnapshot("h", now.minusSeconds(86_400L)))
        )
        val unchangedRoot = KdbxGroup(name = "Root", entries = listOf(freshEntry))
        assertSame(
            unchangedRoot,
            HistoryManager.pruneGroupHistoryByAge(unchangedRoot, 365, now)
        )

        // 场景 B：嵌套子分组内条目含超期历史 → 递归修剪，仅保留期内快照
        val staleEntry = entryOf("deep").copy(
            history = listOf(
                historySnapshot("keep", now.minusSeconds(86_400L * 30)),
                historySnapshot("drop", now.minusSeconds(86_400L * 500))
            )
        )
        val subGroup = KdbxGroup(name = "Sub", entries = listOf(staleEntry))
        val root = KdbxGroup(name = "Root", subgroups = listOf(subGroup))

        val prunedRoot = HistoryManager.pruneGroupHistoryByAge(root, 365, now)
        val prunedDeep = prunedRoot.subgroups.first().entries.first()
        assertEquals(1, prunedDeep.history.size)
        assertEquals("keep", prunedDeep.history.first().title)
    }

    private fun historySnapshot(title: String, lastModified: Instant): KdbxEntry {
        return KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false)),
            times = KdbxTimes(lastModificationTime = lastModified)
        )
    }
}
