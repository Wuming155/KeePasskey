package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.database.file.KdbxDatabase

/**
 * 会话库「内容是否变化」的**纯比较**（`ISSUE-P2-91`：自 [SyncContentChangeDetector] 抽出以便直测，
 * 原为三个 private 函数，逻辑逐字搬运）。
 *
 * **本比较回答的问题**：`current` 相对 `reference`（内存基线 / 缓存快照）是否存在
 * **需要重新序列化并上传**的内容变更——调用方在判「无变化」时会**直接复用旧缓存字节**上传
 * （见 `SyncCycleRunner`），故漏判的后果是「本地编辑不上云」；多判的后果是
 * 「无意义重传 + 前移远端 ETag」（多设备协同噪声）。
 *
 * ## 口径（刻意如此，勿「顺手对齐」）
 *
 * 本比较与合并侧的 [com.keepasskey.sync.merge.KdbxEntryMerger.isModified] **不是同一个问题**，
 * 两者口径**刻意不同**且各自写在自己的 KDoc 里：
 *
 * - **合并侧**回答「条目相对 base 是否被修改」，用于冲突裁决，**必须**把
 *   `times.lastModificationTime` 计入（否则时间戳更新过的条目会被判成未改、不参与合并）；
 * - **本比较**回答「是否需要重新序列化上传」，**刻意不比 `times`**：`KdbxTimes` 同时承载
 *   **使用性**字段（`lastAccessTime` / `usageCount`）与 `lastModificationTime`，而
 *   `KdbxEntry.withField` 会经 `withModified()` 一次性改写三者 ⇒ 纳入比较会让「触碰但内容等同」
 *   被判成变更，触发重序列化 + 上传 + 前移远端 ETag，正是本检测器存在的理由
 *   （KDBX4 随机 IV 使重序列化字节必然漂移；F5 记录的原始动机）。
 *
 * ## 前提复核结论（`ISSUE-P2-91` AC 的「补齐 `times` 比较」**未照原样实施**）
 *
 * 2026-09-17 开工复核：**生产代码不存在只改 `times` 的编辑入口**——
 * `expires` / `expiryTime` 在全仓生产代码中**无任何写入者**（仅 `KdbxXmlTimesNode` 读取端与
 * `KdbxXmlTimeHelper` 默认值构造），`times` 的改动一律伴随 `fields`（`withField` 经 `withModified`）
 * 或 `customFields`（passkey 计数器补丁）变化，而两者本就在比较集内。
 * ⇒ 「仅 `times` 变化的本地编辑被静默丢弃」在既有可达面上**不成立**；
 * 该口径边界与解除条件登记于 `docs/architecture/已知工程限界.md` §10。
 *
 * ## 已知不覆盖（登记同处）
 *
 * `history` 只比**条数**、不比内容。可达的历史变更（追加快照 / 按保留期修剪 / 合并并集）
 * **必然改变条数**，故当前不构成漏判；若日后引入「等条数原地改写历史」的路径，
 * 须同步改本处判据（该分支由用例显式锁定为「刻意不判为变化」）。
 *
 * ProtectedString.equals 为字节数组内容比较，整字段比较不会物化明文密码。
 */
internal object KdbxContentComparator {

    /** `current` 相对 [reference] 是否存在内容变更（`deletedObjects` 为根级判据） */
    fun changed(current: KdbxDatabase, reference: KdbxDatabase): Boolean {
        if (current.deletedObjects != reference.deletedObjects) return true
        return groupChanged(current.rootGroup, reference.rootGroup)
    }

    /** 分组递归比较（name / notes / 图标 / 父组 / tags / customData 及其子项） */
    fun groupChanged(a: KdbxGroup, b: KdbxGroup): Boolean {
        if (a.id != b.id || a.name != b.name || a.notes != b.notes ||
            a.iconId != b.iconId || a.customIconId != b.customIconId ||
            a.parentGroupId != b.parentGroupId ||
            // P1-8 配套：分组 tags / customData 已为一等持久化字段，纳入变化检测，
            // 防止仅修改分组标签的编辑被误判为「无变化」而把旧字节上传云端
            a.tags != b.tags || a.customData != b.customData
        ) {
            return true
        }
        if (a.entries.size != b.entries.size) return true
        val bEntries = b.entries.associateBy { it.id }
        for (entry in a.entries) {
            val ref = bEntries[entry.id] ?: return true
            if (entryChanged(entry, ref)) return true
        }
        if (a.subgroups.size != b.subgroups.size) return true
        val bSubgroups = b.subgroups.associateBy { it.id }
        for (sub in a.subgroups) {
            val ref = bSubgroups[sub.id] ?: return true
            if (groupChanged(sub, ref)) return true
        }
        return false
    }

    /**
     * 条目比较：全部**内容**字段 + 历史**条数**（`times` 刻意不在此列，理由见类 KDoc）。
     */
    fun entryChanged(a: KdbxEntry, b: KdbxEntry): Boolean {
        return a.fields != b.fields ||
                a.customFields != b.customFields ||
                a.tags != b.tags ||
                a.attachments != b.attachments ||
                a.iconId != b.iconId ||
                a.customIconId != b.customIconId ||
                a.overrideUrl != b.overrideUrl ||
                a.qualityCheck != b.qualityCheck ||
                a.parentGroupId != b.parentGroupId ||
                a.previousParentGroup != b.previousParentGroup ||
                a.customData != b.customData ||
                a.autoType != b.autoType ||
                a.backgroundColor != b.backgroundColor ||
                a.foregroundColor != b.foregroundColor ||
                a.history.size != b.history.size
    }
}
