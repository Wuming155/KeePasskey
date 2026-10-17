package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.history.HistoryManager
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 历史修订回滚（`ISSUE-P2-633`）。
 *
 * ## 缺陷背景
 *
 * 整改前的回滚把历史**UI 投影**交给通用 `saveEntry`：投影里只有附件的名称与 `refIndex`，
 * 没有历史字节来源 ⇒ `AttachmentMergePolicy` 只能按当前条目的同下标 / 同名项取数据，
 * 未命中就构造空附件。结果是「回滚成功」但附件被当前版本顶替、或直接变空。
 *
 * ## 现行实现
 *
 * 回滚**不再从投影重建条目**，而是在会话内按 [EntryRevisionHandle] 定位到真实的历史
 * `KdbxEntry`，由 [HistoryManager.rollbackToSnapshot] 生成「内容 = 历史快照、身份 = 当前条目」的
 * 结果后原位落树：
 * - 附件（含落盘 `BinarySource` 引用）连同字节所有权一起恢复，不经 UI 投影、不做名称/下标匹配；
 * - 回滚前的当前版本被归档为最新历史（[HistoryManager.rollbackToSnapshot] 既有语义）；
 * - 条目 UUID / 父组 / `customData`（收藏等库内元数据）取**当前条目**，其余字段取历史快照；
 * - 句柄失效（已修剪 / 歧义）返回 [KdbxError.HISTORY_REVISION_UNAVAILABLE]，**零写入**。
 *
 * 本类只做这一件事：不合并 UI 字段、不碰附件策略、不重复实现历史修剪口径。
 */
internal class VaultEntryRevisionRollback(
    private val databaseSession: DatabaseSession,
    private val persistSession: suspend () -> KdbxResult<Unit>
) {

    /** 回滚 [entryId] 到 [revisionHandle] 指向的历史快照（句柄语义见 [EntryRevisionHandle]）。 */
    suspend fun rollback(entryId: String, revisionHandle: String): KdbxResult<Unit> =
        withContext(Dispatchers.Default) {
            val targetUuid = parseKdbxUuidOrNull(entryId) ?: return@withContext unavailable(entryId)
            val db = databaseSession.databaseFlow.first() ?: return@withContext unavailable(entryId)
            val entry = db.rootGroup.findEntry(targetUuid) ?: return@withContext unavailable(entryId)
            val located = EntryRevisionHandle.resolve(entry.history, revisionHandle)
                ?: return@withContext unavailable(entryId)

            val restored = restoreFrom(db, entry, located)
            databaseSession.saveEntry(restored)
            persistSession()
        }

    /**
     * 历史快照 → 可落树的条目：内容取快照，身份与库内元数据取当前条目。
     *
     * `customData` 刻意取当前条目：它承载收藏标记等**库内元数据**而非修订内容
     * （整改前的投影路径同样不携带它，回滚不会翻转收藏状态）。
     */
    private fun restoreFrom(
        db: KdbxDatabase,
        current: KdbxEntry,
        located: EntryRevisionHandle.LocatedRevision
    ): KdbxEntry = HistoryManager.rollbackToSnapshot(
        currentEntry = current,
        historySnapshotIndex = located.index,
        maxHistoryItems = db.historyMaxItems,
        maxHistorySize = db.historyMaxSize
    ).copy(customData = current.customData)

    /** 句柄无法落到唯一快照：**未做任何写入**，如实失败（不得谎报已回滚，TASK-31）。 */
    private fun unavailable(entryId: String): KdbxResult.Failure = KdbxResult.Failure(
        IllegalStateException("history revision unavailable for entry=$entryId"),
        code = KdbxError.HISTORY_REVISION_UNAVAILABLE
    )
}
