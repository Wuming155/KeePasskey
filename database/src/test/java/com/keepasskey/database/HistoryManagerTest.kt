package com.keepasskey.database.history

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
