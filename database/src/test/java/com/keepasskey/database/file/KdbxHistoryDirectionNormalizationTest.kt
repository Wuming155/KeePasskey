package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.history.HistoryManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * ISSUE-P3-306：读取侧历史方向归一回归。
 *
 * 官方 KeePass 写出的 `<History>` 为最旧在前（`PwEntry.CreateBackup` 追加到尾部），
 * 而本仓内存约定为头部最新。归一前，官方形态的库按文档序装配后方向倒置，
 * 保存路径 `pruneHistory`（`take(maxItems)` 保留头部）会按位置误裁最新端。
 */
class KdbxHistoryDirectionNormalizationTest {

    private val password = "history-direction-probe".toCharArray()

    private fun snapshot(title: String, lastModified: Instant): KdbxEntry = KdbxEntry(
        id = KdbxUuid.random(),
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false)),
        times = KdbxTimes(lastModificationTime = lastModified)
    )

    private fun vaultWithAscendingHistory(): KdbxDatabase {
        // 官方形态：最旧在前（升序）
        val history = listOf(
            snapshot("V1", Instant.parse("2026-01-01T00:00:00Z")),
            snapshot("V2", Instant.parse("2026-01-02T00:00:00Z")),
            snapshot("V3", Instant.parse("2026-01-03T00:00:00Z"))
        )
        val entry = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("current", isProtected = false)),
            times = KdbxTimes(lastModificationTime = Instant.parse("2026-01-04T00:00:00Z")),
            history = history
        )
        return KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
    }

    private fun roundTrip(db: KdbxDatabase): KdbxDatabase {
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)
        return KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), password)
    }

    /** 读侧归一：升序（官方形态）输入的库，加载后头部为最新，且只调序不改写快照内容。 */
    @Test
    fun `升序官方形态库经读侧归一为头部最新且只调序`() {
        val original = vaultWithAscendingHistory().rootGroup.entries.single()
        val originalSet = original.history.map { it.title to it.times.lastModificationTime }

        val loaded = roundTrip(vaultWithAscendingHistory()).rootGroup.entries.single()

        assertEquals(listOf("V3", "V2", "V1"), loaded.history.map { it.title })
        // 只调序：快照集合（标题 + 时间戳）逐项不变
        assertEquals(
            originalSet.toSet(),
            loaded.history.map { it.title to it.times.lastModificationTime }.toSet()
        )
    }

    /** 归一后保存路径的位置口径恒正确：数量裁剪裁掉的是最旧一端（V1），不是最新端。 */
    @Test
    fun `归一后数量上限裁剪移除最旧端`() {
        val loaded = roundTrip(vaultWithAscendingHistory())

        val pruned = HistoryManager.pruneGroupHistoryByLimit(
            loaded.rootGroup,
            maxItems = 2,
            maxSize = HistoryManager.UNLIMITED_SIZE
        )

        val prunedEntry = pruned.entries.single()
        assertEquals(listOf("V3", "V2"), prunedEntry.history.map { it.title })
    }

    /** 归一后官方形态库的修订列表顺序与本仓自产库一致。 */
    @Test
    fun `官方形态库归一后修订列表顺序与本仓自产库一致`() {
        // 本仓自产路径：recordHistorySnapshot 头插新快照 → 头部最新
        var current = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("V0", isProtected = false))
        )
        for (i in 1..3) {
            current = HistoryManager.recordHistorySnapshot(
                currentEntry = current,
                newEntry = current.copy(
                    fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("V$i", isProtected = false))
                )
            )
        }
        // 自产路径的快照是被归档的旧版本：V3 的历史 = [V2, V1, V0]，同为头部最新
        val selfProducedTitles = current.history.map { it.title }
        assertEquals(listOf("V2", "V1", "V0"), selfProducedTitles)

        val loaded = roundTrip(vaultWithAscendingHistory()).rootGroup.entries.single()

        // 两者的修订列表方向一致：头部均为时间上最新的一端（自产快照标题比当前版本旧一档）
        assertEquals(
            loaded.history.map { it.times.lastModificationTime }.sortedDescending(),
            loaded.history.map { it.times.lastModificationTime }
        )
        assertEquals(
            current.history.map { it.times.lastModificationTime }.sortedDescending(),
            current.history.map { it.times.lastModificationTime }
        )
    }
}
