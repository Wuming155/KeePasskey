package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.history.HistoryManager
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 合并历史截断（**ISSUE-P3-292** AC①②）。
 *
 * 缺陷背景：`KdbxEntryMerger` 合并历史为 `local + remote + base` 的**三方并集**
 * （仅按 `lastModificationTime` 去重），而合并路径此前**不经任何截断**——
 * 未被用户再编辑过的条目其历史永久留存，内存逐快照增长，并与附件池引用计费耦合
 * （限界表 §29 的 `MAX_REFERENCES_PER_POOL_ITEM = 1024` 正是按此取的宽值）。
 *
 * 本用例锁定三件事：
 * 1. **截断确已接线**：合并产物经 [truncateMergedHistory] 后条目历史 ≤ 库级 Meta 上限；
 * 2. **裁掉的是「最旧」而非「最新」**：`pruneHistory` 是**位置口径**（保留头部），
 *    因此合并历史必须与本仓约定同向（**头部最新**）。整改前合并器按升序输出，
 *    截断会裁到最新一端（数据损失方向）——本用例以「保留集恰为最新 10 条」把方向钉死；
 * 3. **两条产出路径方向一致**：合并路径（降序）与记录路径（`recordHistorySnapshot` 头插）
 *    产出的列表同为「头部最新」，故 `HistoryManagerTest` 锁定的位置口径对二者都成立。
 */
class MergedHistoryTruncationTest {

    private val rootId = KdbxUuid(ByteArray(16) { 9 })
    private val sharedId = KdbxUuid(ByteArray(16) { 7 })

    private fun entryWith(
        id: KdbxUuid,
        title: String,
        modifiedMillis: Long,
        history: List<KdbxEntry> = emptyList()
    ) = KdbxEntry(
        id = id,
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false)),
        times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(modifiedMillis)),
        history = history
    )

    /** 单条历史快照（自身不带嵌套历史） */
    private fun snapshot(millis: Long) = entryWith(sharedId, "h$millis", millis)

    private fun rootWith(entries: List<KdbxEntry>) = KdbxGroup(id = rootId, name = "Root", entries = entries)

    private fun timesOf(entry: KdbxEntry): List<Long> =
        entry.history.map { it.times.lastModificationTime.toEpochMilli() }

    private fun dbWith(root: KdbxGroup, maxItems: Int = 10, maxSize: Long = DEFAULT_MAX_SIZE) = KdbxDatabase(
        header = KdbxHeader.createDefault(useArgon2 = false),
        rootGroup = root,
        historyMaxItems = maxItems,
        historyMaxSize = maxSize
    )

    // ========== AC②：双侧各 6 条历史合并后按上限截断 ==========

    @Test
    fun `双侧各 6 条历史合并后按库级上限截断且保留最新`() {
        // local: 1100..1600（6 条）；remote: 2100..2600（6 条）——并集 12 条，上限 10
        val localHistory = (1..6).map { snapshot(1_000L + it * 100L) }
        val remoteHistory = (1..6).map { snapshot(2_000L + it * 100L) }

        val base = KdbxDatabaseLite(rootWith(listOf(entryWith(sharedId, "v0", 500L))))
        val local = KdbxDatabaseLite(
            rootWith(listOf(entryWith(sharedId, "v-local", 5_000L, localHistory)))
        )
        val remote = KdbxDatabaseLite(
            rootWith(listOf(entryWith(sharedId, "v-remote", 6_000L, remoteHistory)))
        )

        val mergedRoot = KdbxMerger.mergeDatabases(base, local, remote).mergedRoot
        val mergedEntry = mergedRoot.allEntries().first { it.id == sharedId }

        // 前置断言：合并产物确为**未截断**的并集（否则下面的截断断言会「恒真」）
        assertEquals("三方并集应为 12 条", 12, mergedEntry.history.size)
        assertEquals(
            "合并历史须为降序（头部最新）——本仓 HistoryManager 的位置口径依赖该方向",
            (26 downTo 21).map { it * 100L } + (16 downTo 11).map { it * 100L },
            timesOf(mergedEntry)
        )

        val truncated = truncateMergedHistory(dbWith(mergedRoot, maxItems = 10))
        val kept = timesOf(truncated.rootGroup.allEntries().first { it.id == sharedId })

        assertEquals("截断后应恰为上限条数", 10, kept.size)
        assertEquals(
            "被裁的必须是**最旧**的两条（1100 / 1200）：保留集应恰为最新 10 条",
            (26 downTo 21).map { it * 100L } + (16 downTo 13).map { it * 100L },
            kept
        )
    }

    @Test
    fun `未超上限时返回同一实例不做无谓重建`() {
        val entry = entryWith(sharedId, "v", 5_000L, (1..3).map { snapshot(1_000L + it * 100L) })
        val db = dbWith(rootWith(listOf(entry)), maxItems = 10)

        val result = truncateMergedHistory(db)

        assertSame("无超额时不得重建整库对象", db, result)
    }

    // ========== 方向一致性：两条产出路径同为「头部最新」 ==========

    @Test
    fun `合并路径与记录路径产出的历史方向一致`() {
        val descending = (1..6).map { snapshot(1_000L + it * 100L) }.reversed() // 头=最新
        val current = entryWith(sharedId, "v", 9_000L, descending)

        // 记录路径：再建一次快照（HistoryManager 头插）
        val recorded = HistoryManager.recordHistorySnapshot(
            currentEntry = current,
            newEntry = current.copy(times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(9_500L))),
            maxHistoryItems = 10,
            maxHistorySize = -1L
        )

        assertEquals(
            "记录路径同样头插（头=最新）——与合并路径同向，位置口径才成立",
            listOf(9_000L, 1_600L, 1_500L, 1_400L, 1_300L, 1_200L, 1_100L),
            recorded.history.map { it.times.lastModificationTime.toEpochMilli() }
        )
    }

    @Test
    fun `体积上限同样自最旧端（列表尾部）移除`() {
        // 三条快照尺寸刻意量级分离（约 10k / 20k / 30k 字节），上限取 45k：
        // 求和 60k > 45k ⇒ 移除最旧（10k）⇒ 50k > 45k ⇒ 再移除次旧（20k）⇒ 30k ≤ 45k 停。
        // 量级分离使断言不依赖 estimateHistoryItemSize 的固定开销细节。
        val descending = listOf(
            entryWith(sharedId, "c".repeat(30_000), 1_300L), // 最新，居首
            entryWith(sharedId, "b".repeat(20_000), 1_200L),
            entryWith(sharedId, "a".repeat(10_000), 1_100L) // 最旧，居尾
        )

        val pruned = HistoryManager
            .pruneGroupHistoryByLimit(rootWith(listOf(entryWith(sharedId, "v", 9_000L, descending))), 10, 45_000L)
            .allEntries().first { it.id == sharedId }

        assertEquals("体积超限须自最旧端逐项移除至达标", 1, pruned.history.size)
        assertEquals("留下的必须是**最新**的一条（1300）", listOf(1_300L), timesOf(pruned))
    }

    private companion object {
        const val DEFAULT_MAX_SIZE = 6L * 1024 * 1024
    }
}
