package com.keepasskey.app.data.repository

import com.keepasskey.core.result.KdbxResult

/**
 * 条目历史回滚端口（`ISSUE-P2-633`）。
 *
 * 单列一个接口而非继续堆进 `VaultRepository`：回滚是**整条替换**语义（内容取历史快照、
 * 身份取当前条目），与 `saveEntry` 的「按 UI 意图逐段合并」是两条独立通路；
 * 拆开后两者的契约各自可读，主接口也不必因此跨过行数档位阈值。
 */
interface VaultHistoryRestorePort {

    /**
     * 把条目回滚到 [revisionHandle] 指向的历史版本——**直接恢复历史 `KdbxEntry` 的内容与
     * 附件所有权**，不经 UI 投影重建（投影只有附件名 / 下标，没有历史字节来源，
     * 按当前条目匹配会顶替或清空历史附件）。
     *
     * 语义：
     * - 内容（标准字段 / 自定义字段 / 附件 / 标签 / AutoType / 图标 / 过期）取历史快照；
     * - 条目 UUID、父组、`customData`（收藏等库内元数据）取当前条目；
     * - 回滚前的当前版本按既有历史策略归档为最新快照；
     * - [revisionHandle] 失效（已修剪 / 歧义）⇒ `KdbxError.HISTORY_REVISION_UNAVAILABLE`
     *   且**零写入**；落盘失败按既有失败码如实返回，绝不谎报已回滚（TASK-31）。
     */
    suspend fun rollbackEntryToRevision(
        entryId: String,
        revisionHandle: String
    ): KdbxResult<Unit>
}
