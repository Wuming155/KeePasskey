package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * `ISSUE-P2-632`：历史修订选择句柄的定位规则。
 *
 * 缺陷背景：KDBX 历史快照的 `<UUID>` 与父条目相同，整改前把该 UUID 当修订 id，
 * `history.firstOrNull { it.id == revisionId }` 恒命中首项 ⇒ 选任何一行都读到第一版。
 *
 * 本用例逐条锁定 [EntryRevisionHandle.resolve] 的四种确定行为：精确命中 / 下标漂移自愈 /
 * 已修剪拒绝 / 同时间戳歧义拒绝。全部为纯函数断言，不触碰会话与磁盘。
 */
class EntryRevisionHandleTest {

    private val entryId = KdbxUuid.random()

    /** 一条历史快照：**UUID 与父条目相同**（KDBX 规范），只有修改时间不同。 */
    private fun snapshot(modifiedAt: Instant): KdbxEntry = KdbxEntry(
        id = entryId,
        times = KdbxTimes(
            creationTime = modifiedAt,
            lastModificationTime = modifiedAt,
            lastAccessTime = modifiedAt,
            expiryTime = modifiedAt,
            locationChanged = modifiedAt
        )
    )

    /** 同 UUID、不同修改时间的三条历史（正是真实库里的形态：UUID 全部相同）。 */
    private fun threeRevisions(): Pair<List<KdbxEntry>, List<Instant>> {
        val newest = Instant.ofEpochMilli(1_700_000_300_000)
        val middle = Instant.ofEpochMilli(1_700_000_200_000)
        val oldest = Instant.ofEpochMilli(1_700_000_100_000)
        return listOf(snapshot(newest), snapshot(middle), snapshot(oldest)) to listOf(newest, middle, oldest)
    }

    @Test
    fun `每条历史的句柄互不相同且定位到自身`() {
        val (history, times) = threeRevisions()

        val handles = history.mapIndexed { index, item -> EntryRevisionHandle.encode(index, item) }
        assertEquals("三条历史必须得到三个不同句柄", 3, handles.toSet().size)

        handles.forEachIndexed { index, handle ->
            val located = EntryRevisionHandle.resolve(history, handle)
            assertEquals(index, located?.index)
            assertEquals(times[index], located?.snapshot?.times?.lastModificationTime)
        }
    }

    @Test
    fun `同时间戳的两条历史按下标仍可分别选中`() {
        val shared = Instant.ofEpochMilli(1_700_000_500_000)
        val history = listOf(snapshot(shared), snapshot(shared))

        assertEquals(0, EntryRevisionHandle.resolve(history, EntryRevisionHandle.encode(0, shared))?.index)
        assertEquals(1, EntryRevisionHandle.resolve(history, EntryRevisionHandle.encode(1, shared))?.index)
    }

    @Test
    fun `列表头插导致下标漂移时按修改时间自愈`() {
        val (history, times) = threeRevisions()
        // 用户打开详情页时选中「第 2 行」（中间那份）
        val handle = EntryRevisionHandle.encode(1, history[1])

        // 期间又保存了一次 ⇒ 列表头插新快照，原下标整体后移一位
        val shifted = listOf(snapshot(Instant.ofEpochMilli(1_700_000_400_000))) + history

        val located = EntryRevisionHandle.resolve(shifted, handle)
        assertEquals("必须落到同一份快照（新下标 2）", 2, located?.index)
        assertEquals(times[1], located?.snapshot?.times?.lastModificationTime)
    }

    @Test
    fun `快照已被修剪时拒绝而不是退回首项`() {
        val (history, _) = threeRevisions()
        val handle = EntryRevisionHandle.encode(2, history[2])

        // 只剩最新的两份：目标（最旧）已被修剪
        val trimmed = history.take(2)
        assertNull("已修剪的快照必须解析失败，不得静默落到首项", EntryRevisionHandle.resolve(trimmed, handle))
    }

    @Test
    fun `同时间戳且下标已漂移时按歧义拒绝`() {
        val shared = Instant.ofEpochMilli(1_700_000_600_000)
        val history = listOf(snapshot(shared), snapshot(shared))
        val handle = EntryRevisionHandle.encode(0, shared)

        // 头插一份不同时间戳的快照 ⇒ 原下标不再命中，且同时间戳快照有两份 ⇒ 无法判断是哪一份
        val shifted = listOf(snapshot(Instant.ofEpochMilli(1_700_000_700_000))) + history
        assertNull(EntryRevisionHandle.resolve(shifted, handle))
    }

    @Test
    fun `非法句柄一律返回 null`() {
        val (history, _) = threeRevisions()
        listOf("", "abc", "0", ":123", "0:", "x:1", "-1:1", "0:abc", "1:1:1").forEach { raw ->
            assertNull("非法句柄 [$raw] 必须解析失败", EntryRevisionHandle.resolve(history, raw))
        }
        assertNull("越界下标且时间戳不命中必须解析失败", EntryRevisionHandle.resolve(history, "9:123"))
    }
}
