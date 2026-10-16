package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid

/**
 * `ISSUE-P1-631`（审计发现）：条目「双方保留 / 冲突副本」的**单一复制实现**。
 *
 * 缺陷形态：冲突副本必须换新 UUID（KDBX 要求 UUID 全局唯一，且同 UUID 在应用回树时会命中同一
 * 槽位而互相覆盖），但复制条目时**history 也必须同批跟随**——KDBX 规范中 history 条目的 UUID
 * 与父条目相同。此前两处副本实现（同步侧 `KdbxMerger.resolveConflict` 的 `DUPLICATE_BOTH`、
 * 漂移侧「双方保留」）都只换条目自身的 UUID，`history` 仍带着**原条目**的 UUID：
 * 于是副本的 history 与「仍然存活的原条目」共享同一 UUID，历史修订定位
 * （本仓按 UUID 定位历史，见 `ISSUE-P2-632`）会把两条不同条目的历史混为一谈。
 *
 * 故本件提供唯一实现，两处副本路径统一调用；新增副本路径必须复用，禁止再写一份
 * `copy(id = random())`。
 */
object KdbxEntryDuplicator {

    /**
     * 复制条目并换新 UUID；`history` 的每条 UUID 同步改为新 UUID（KDBX：history UUID == 父条目 UUID）。
     *
     * @param newId 注入点供测试固定 UUID；生产不传即随机
     */
    fun duplicateWithNewId(entry: KdbxEntry, newId: KdbxUuid = KdbxUuid.random()): KdbxEntry =
        entry.copy(
            id = newId,
            history = entry.history.map { it.copy(id = newId) }
        )
}
