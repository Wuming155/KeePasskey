package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxEntry
import java.time.Instant

/**
 * 历史修订的**选择句柄**（ISSUE-P2-632）。
 *
 * ## 为什么不能用条目 UUID 当修订 id
 *
 * KDBX 规范要求 `<History>` 内每个快照的 `<UUID>` 与父条目相同（官方 `PwEntry.Clone` 语义），
 * 本仓解析 / 序列化 / 合并路径也据此实现（见 `KdbxEntryDuplicator` 的同类说明）。
 * 于是「修订 id = 快照 UUID」必然让**同一条目的全部历史行共享同一个 id**：
 * `history.firstOrNull { it.id == revisionId }` 恒命中列表首项——用户点第三行，
 * 拿到的永远是第一行的密码 / 快照 / 回滚目标。
 *
 * ## 句柄形态
 *
 * `"<history 下标>:<lastModificationTime 毫秒>"`：
 * - **下标**是「在列表里的位置」，负责精确定位；
 * - **时间戳**是「这份快照的身份指纹」，负责识别下标漂移（新修订头插、按龄修剪都会移位）。
 *
 * 解析规则（[resolve]，全部为确定行为，不允许「猜一个」）：
 * 1. 下标命中且该位置快照时间戳一致 ⇒ 精确命中（同时间戳的多份历史也因此可分别选中）；
 * 2. 下标漂移（列表头插 / 尾部修剪）但时间戳在列表中**唯一**命中 ⇒ 自愈命中；
 * 3. 时间戳命中 0 份（已被修剪）或多于 1 份（同时间戳且下标已变）⇒ **拒绝**（返回 null），
 *    调用方如实报「历史版本已不可用」，绝不静默退回首项。
 *
 * 句柄只承载「位置 + 非敏感时间元数据」，不含任何字段明文，可安全进 UI 状态。
 * 磁盘互操作格式不受影响：KDBX 里的历史 UUID 保持原样，本句柄仅存在于内存与 UI 投影。
 */
internal object EntryRevisionHandle {

    /** 句柄分隔符（下标与时间戳之间）。 */
    private const val SEPARATOR = ':'

    /** 定位结果：快照下标 + 该下标的快照实例（供 `HistoryManager.rollbackToSnapshot` 直接消费）。 */
    class LocatedRevision(
        val index: Int,
        val snapshot: KdbxEntry
    )

    /** 生成句柄（[index] 为该快照在 `entry.history` 中的下标）。 */
    fun encode(index: Int, modifiedAt: Instant): String =
        index.toString() + SEPARATOR + modifiedAt.toEpochMilli()

    /** 生成句柄（直接取快照自身的修改时间）。 */
    fun encode(index: Int, snapshot: KdbxEntry): String =
        encode(index, snapshot.times.lastModificationTime)

    /**
     * 按句柄定位快照；规则见类 KDoc。句柄格式非法 / 下标越界 / 已修剪 / 歧义一律返回 null。
     */
    fun resolve(history: List<KdbxEntry>, handle: String): LocatedRevision? {
        val separator = handle.indexOf(SEPARATOR)
        if (separator <= 0 || separator == handle.lastIndex) return null
        val index = handle.substring(0, separator).toIntOrNull() ?: return null
        val modifiedAtMillis = handle.substring(separator + 1).toLongOrNull() ?: return null
        if (index < 0) return null

        val atIndex = history.getOrNull(index)
        if (atIndex != null && atIndex.times.lastModificationTime.toEpochMilli() == modifiedAtMillis) {
            return LocatedRevision(index, atIndex)
        }

        // 下标漂移：仅当时间戳在列表中唯一命中时才认，歧义即拒绝（见类 KDoc 规则 3）
        var matchIndex = -1
        for (candidate in history.indices) {
            if (history[candidate].times.lastModificationTime.toEpochMilli() != modifiedAtMillis) continue
            if (matchIndex >= 0) return null
            matchIndex = candidate
        }
        return if (matchIndex >= 0) LocatedRevision(matchIndex, history[matchIndex]) else null
    }
}
