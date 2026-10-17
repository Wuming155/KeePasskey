package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.keepasskey.app.ui.model.textArg

/**
 * 历史修订的回滚与对比预解密（断点8 / TASK-31 / M2 / ISSUE-P2-15）。
 *
 * ISSUE-P3-31 批次 C：由 `EntryDetailViewModel`（原 708 行）按**纯结构性拆分**搬出。
 * 明文仍由 [EntryDetailSecrets] 唯一持有（本类只写它、不另设副本）；
 * 三条语义逐字保留：
 * 1. 快照缺失（已被修剪/清理）时**不得谎报「已回滚」**，如实暴露失败（TASK-31）；
 * 2. 回滚不再经 String / CharArray 中转任何明文——由仓库直接以历史条目落库（ISSUE-P2-633），
 *    对比用的修订密码仍全程 CharArray（M2 契约不变）；
 * 3. 关闭对比弹窗必须连修订密码映射一并清空，避免跨弹窗累积驻留明文（M1）。
 */
internal class EntryDetailRevisionController(
    private val vaultRepository: VaultRepository,
    private val secrets: EntryDetailSecrets,
    private val strings: StringsProvider,
    // `ISSUE-P3-188` §170：与同包的 EntryDetailCopyCoordinator / EntryDetailEntryActions 同形态，
    // 由协作者自行派发到 scope 并把结果消息回灌 UI —— ViewModel 侧只留事件入口的一行委托。
    private val scope: CoroutineScope,
    private val currentEntry: () -> UiVaultEntry?,
    private val currentEntryId: () -> String?,
    private val showMessage: (UiMessage) -> Unit
) {

    /** 事件入口：回滚**当前条目**到 [revision]（原为 ViewModel 内的 `launch` 包装，§170 归位至此）。 */
    fun rollbackToCurrentEntry(revision: UiEntryRevision) {
        val current = currentEntry() ?: return
        scope.launch { showMessage(rollback(current, revision)) }
    }

    /** 事件入口：为对比弹窗预解密**当前条目**的密码与目标修订密码。 */
    fun prepareDiffForCurrentEntry(revisionHandle: String) {
        val entryId = currentEntryId() ?: return
        scope.launch { prepareDiff(entryId, revisionHandle) }
    }

    /**
     * 回滚 [current] 到 [revision]：由仓库直接恢复该历史版本的完整条目（含附件字节与
     * 所有权），保存时由 HistoryManager 把回滚前的当前版本归档为最新历史。
     *
     * ISSUE-P2-633：整改前把历史**投影**交给通用 `saveEntry` 重建——投影没有历史附件
     * 字节来源，附件会被当前版本顶替或清空。现行路径不再经投影，也不再另行解密密码 /
     * TOTP（历史条目本身就是待落库内容）。
     *
     * @return 面向用户的结果消息（成功 / 快照不可用 / 保存失败），由调用方下发到 UI 状态。
     */
    suspend fun rollback(current: UiVaultEntry, revision: UiEntryRevision): UiMessage {
        return when (val result = vaultRepository.rollbackEntryToRevision(current.id, revision.id)) {
            is KdbxResult.Success -> UiMessage(R.string.detail_history_rolled_back)
            is KdbxResult.Failure -> if (result.code == KdbxError.HISTORY_REVISION_UNAVAILABLE) {
                // 句柄指向的快照已被修剪 / 歧义：如实报「历史版本已不可用」，绝不谎报已回滚
                UiMessage(R.string.detail_history_rollback_failed)
            } else {
                UiMessage(R.string.edit_save_failed, listOf(result.textArg(strings)))
            }
        }
    }

    /** 打开历史修订对比弹窗前按需解密：当前密码 + 目标修订密码。 */
    suspend fun prepareDiff(entryId: String, revisionHandle: String) {
        // ISSUE-P2-15：对比路径同样改走 CharArray 借用通道，String 物化收敛在展示边界
        val currentPw = secrets.revealedPassword.value
            ?: vaultRepository.getEntryPasswordChars(entryId).toDisplayString()
        val revisionPw = vaultRepository.getEntryRevisionPasswordChars(entryId, revisionHandle)
            .toDisplayString()
        secrets.revealedPassword.value = currentPw
        secrets.revealedRevisionPasswords.update { it + (revisionHandle to revisionPw.orEmpty()) }
    }

    /** 关闭对比弹窗：清空当前密码与全部修订密码明文。 */
    fun clearDiff() {
        secrets.revealedPassword.value = null
        secrets.revealedRevisionPasswords.value = emptyMap()
    }
}
