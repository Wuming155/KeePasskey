package com.keepasskey.database.history

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import java.time.Duration
import java.time.Instant

/**
 * KDBX 条目历史版本与回滚管理器。
 * 遵循标准 KDBX 4.0 历史修订保留标准（对齐官方 KeePass `PwEntry.MaintainHistory`）：
 * 1. 每次修改条目前将当前快照追加至 history 列表（列表头部为最新、尾部为最旧）；
 * 2. 支持通过指定历史版本快照进行一键版本回滚（Rollback）；
 * 3. 修剪规则遵从库级 Meta 配置（[com.keepasskey.database.xml.KdbxMetaData] 的
 *    historyMaxItems / historyMaxSize），按官方规则执行两级修剪：
 *    - 数量上限：超出的最旧快照先行移除（-1 = 不限制，0 = 不保留历史）；
 *    - 体积上限：全部历史快照估算大小之和超过上限时，自最旧端逐项移除直至达标
 *      （-1 = 不限制）；估算口径对齐官方 `PwEntry.GetSize`。
 */
object HistoryManager {

    /** 官方默认：每条目最多保留 10 个历史快照 */
    const val DEFAULT_MAX_HISTORY_ITEMS = 10

    /** 官方默认：每条目历史总大小上限 6 MiB */
    const val DEFAULT_MAX_HISTORY_SIZE: Long = 6L * 1024 * 1024

    /** 官方语义：-1 表示不限制（数量上限） */
    const val UNLIMITED: Int = -1

    /** 官方语义：-1 表示不限制（体积上限，字节） */
    const val UNLIMITED_SIZE: Long = -1L

    /** 官方 PwEntry.GetSize 的近似固定开销（字节） */
    private const val ENTRY_FIXED_OVERHEAD_BYTES = 12L

    /**
     * 在更新条目内容时，将条目的旧版本安全归档至历史记录中，
     * 并按 [maxHistoryItems] / [maxHistorySize] 官方规则修剪超额历史。
     */
    fun recordHistorySnapshot(
        currentEntry: KdbxEntry,
        newEntry: KdbxEntry,
        maxHistoryItems: Int = DEFAULT_MAX_HISTORY_ITEMS,
        maxHistorySize: Long = DEFAULT_MAX_HISTORY_SIZE
    ): KdbxEntry {
        // 创建历史快照（剥离自身的 history，避免嵌套膨胀）
        val snapshot = currentEntry.copy(history = emptyList())
        val updatedHistory = pruneHistory(
            history = listOf(snapshot) + currentEntry.history,
            maxItems = maxHistoryItems,
            maxSize = maxHistorySize
        )

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
        historySnapshotIndex: Int,
        maxHistoryItems: Int = DEFAULT_MAX_HISTORY_ITEMS,
        maxHistorySize: Long = DEFAULT_MAX_HISTORY_SIZE
    ): KdbxEntry {
        require(historySnapshotIndex in currentEntry.history.indices) {
            "历史快照索引越界: " + historySnapshotIndex + ", 总条数: " + currentEntry.history.size
        }

        val targetSnapshot = currentEntry.history[historySnapshotIndex]

        // 还原字段与数据，并将回滚前的主版本归档为最新历史
        val previousCurrentSnapshot = currentEntry.copy(history = emptyList())
        val newHistory = pruneHistory(
            history = listOf(previousCurrentSnapshot) + currentEntry.history.filterIndexed { idx, _ -> idx != historySnapshotIndex },
            maxItems = maxHistoryItems,
            maxSize = maxHistorySize
        )

        return targetSnapshot.copy(
            id = currentEntry.id, // 保持条目 UUID 不变
            parentGroupId = currentEntry.parentGroupId,
            history = newHistory,
            times = targetSnapshot.times.copy(
                lastModificationTime = Instant.now()
            )
        )
    }

    /**
     * 官方数据库维护操作「删除 N 天前的历史条目」的单条目实现
     * （KeePass 2.61.1 `DatabaseOperationsForm` + `PwDatabase.MaintenanceHistoryDays`）：
     * 移除 [entry] 历史列表中 `lastModificationTime` 早于 `now - maintenanceHistoryDays` 的快照。
     *
     * 官方判据 `(dtNow - peHist.LastModificationTime) >= tsSpan` 即「保留期外」移除，
     * 等价于本实现的「仅保留 lastModificationTime 严格晚于 cutoff 的快照」。
     *
     * 安全取舍：`maintenanceHistoryDays <= 0` 时**不执行任何修剪**并原样返回。
     * 官方 uint 语义下 0 会删除全部历史，但本函数用于保存时的**自动**保留期维护，
     * 0/负值一律视为「未配置保留期」以免误删（缺省 365 天，与官方默认一致）。
     *
     * @return 修剪后的条目；若无历史或无快照超期，返回**同一实例**（便于调用方免拷贝判定）。
     */
    fun pruneHistoryByAge(
        entry: KdbxEntry,
        maintenanceHistoryDays: Int,
        now: Instant = Instant.now()
    ): KdbxEntry {
        if (maintenanceHistoryDays <= 0 || entry.history.isEmpty()) return entry
        val cutoff = now.minus(Duration.ofDays(maintenanceHistoryDays.toLong()))
        val kept = entry.history.filter { it.times.lastModificationTime.isAfter(cutoff) }
        if (kept.size == entry.history.size) return entry
        return entry.copy(history = kept)
    }

    /**
     * 对整棵分组树递归执行历史保留期维护（[pruneHistoryByAge]）。
     * 仅在实际发生修剪时才重建对应分组节点；全树无变化时返回**同一根实例**，
     * 供 [com.keepasskey.database.session.DatabaseSession] 在保存前免拷贝判定。
     */
    fun pruneGroupHistoryByAge(
        group: KdbxGroup,
        maintenanceHistoryDays: Int,
        now: Instant = Instant.now()
    ): KdbxGroup {
        if (maintenanceHistoryDays <= 0) return group

        var changed = false
        val newEntries = group.entries.map { entry ->
            val pruned = pruneHistoryByAge(entry, maintenanceHistoryDays, now)
            if (pruned !== entry) changed = true
            pruned
        }
        val newSubgroups = group.subgroups.map { sub ->
            val pruned = pruneGroupHistoryByAge(sub, maintenanceHistoryDays, now)
            if (pruned !== sub) changed = true
            pruned
        }
        return if (changed) group.copy(entries = newEntries, subgroups = newSubgroups) else group
    }

    /**
     * 官方两级修剪（KeePass MaintainHistory / KeePassXC truncateHistory）：
     * 1. 数量上限（maxItems &gt;= 0 时生效）：仅保留最新的 maxItems 个快照；
     * 2. 体积上限（maxSize &gt;= 0 时生效）：历史总大小超过上限时自最旧端逐项移除。
     * history 列表约定头部为最新、尾部为最旧，因此「移除最旧」即移除尾部。
     */
    private fun pruneHistory(history: List<KdbxEntry>, maxItems: Int, maxSize: Long): List<KdbxEntry> {
        val countTrimmed = if (maxItems >= 0) history.take(maxItems) else history
        if (maxSize < 0 || countTrimmed.isEmpty()) return countTrimmed

        var dropCount = 0
        var sum = countTrimmed.sumOf { estimateHistoryItemSize(it) }
        while (dropCount < countTrimmed.size && sum > maxSize) {
            sum -= estimateHistoryItemSize(countTrimmed[countTrimmed.size - 1 - dropCount])
            dropCount++
        }
        return if (dropCount > 0) countTrimmed.dropLast(dropCount) else countTrimmed
    }

    /**
     * 历史快照大小估算（字节），口径对齐官方 PwEntry.GetSize：
     * 字段键名与值长度 + 附件名与数据长度 + 标签 + AutoType 序列 + 固定开销。
     * [com.keepasskey.core.security.ProtectedString] 的 length 为驻留密文长度
     * （CTR 无填充，与明文等长），全程不解密、不物化明文。
     */
    private fun estimateHistoryItemSize(entry: KdbxEntry): Long {
        var size = ENTRY_FIXED_OVERHEAD_BYTES
        for ((key, value) in entry.fields) {
            size += key.length + value.length
        }
        for (customField in entry.customFields) {
            size += customField.key.length + customField.value.length
        }
        for (attachment in entry.attachments) {
            size += attachment.name.length + attachment.data.size
        }
        for (tag in entry.tags) {
            size += tag.length
        }
        entry.autoType?.let { autoType ->
            size += autoType.defaultSequence.length
            for (association in autoType.associations) {
                size += association.window.length + association.keystrokeSequence.length
            }
        }
        return size
    }
}
