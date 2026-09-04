package com.keepasskey.database.history

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import java.time.Instant

/**
 * KDBX 条目历史版本与回滚管理器。
 * 遵循标准 KDBX 4.0 历史修订保留标准：
 * 1. 每次修改条向前将当前快照追加至 history 列表中；
 * 2. 支持通过指定历史版本快照进行一键版本回滚（Rollback）；
 * 3. 自动修整超额历史版本（默认保留最多 10 个历史快照）。
 */
object HistoryManager {

    private const val DEFAULT_MAX_HISTORY_ITEMS = 10

    /**
     * 在更新条目内容时，将条目的旧版本安全归档至历史记录中
     */
    fun recordHistorySnapshot(
        currentEntry: KdbxEntry,
        newEntry: KdbxEntry,
        maxHistoryItems: Int = DEFAULT_MAX_HISTORY_ITEMS
    ): KdbxEntry {
        // 创建历史快照（剥离自身的 history，避免嵌套膨胀）
        val snapshot = currentEntry.copy(history = emptyList())
        val updatedHistory = (listOf(snapshot) + currentEntry.history).take(maxHistoryItems)

        val updatedTimes = newEntry.times.copy(
            lastModificationTime = Instant.now()
        )

        return newEntry.copy(
            history = updatedHistory,
            times = updatedTimes
        )
    }

    /**
     * 将指定历史快照还原为当前条目内容
     * @param currentEntry 包含历史列表的当前条目
     * @param historySnapshotIndex 要恢复的历史快照在 history 列表中的索引
     */
    fun rollbackToSnapshot(
        currentEntry: KdbxEntry,
        historySnapshotIndex: Int
    ): KdbxEntry {
        require(historySnapshotIndex in currentEntry.history.indices) {
            "历史快照索引越界: $historySnapshotIndex, 总条数: ${currentEntry.history.size}"
        }

        val targetSnapshot = currentEntry.history[historySnapshotIndex]

        // 还原字段与数据，并将回滚前的主版本归档为最新历史
        val previousCurrentSnapshot = currentEntry.copy(history = emptyList())
        val newHistory = listOf(previousCurrentSnapshot) + currentEntry.history.filterIndexed { idx, _ -> idx != historySnapshotIndex }

        return targetSnapshot.copy(
            id = currentEntry.id, // 保持条目 UUID 不变
            parentGroupId = currentEntry.parentGroupId,
            history = newHistory,
            times = targetSnapshot.times.copy(
                lastModificationTime = Instant.now()
            )
        )
    }
}
