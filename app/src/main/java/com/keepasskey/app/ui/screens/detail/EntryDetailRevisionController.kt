package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 历史修订的回滚与对比预解密（断点8 / TASK-31 / M2 / ISSUE-P2-15）。
 *
 * ISSUE-P3-31 批次 C：由 `EntryDetailViewModel`（原 708 行）按**纯结构性拆分**搬出。
 * 明文仍由 [EntryDetailSecrets] 唯一持有（本类只写它、不另设副本）；
 * 三条语义逐字保留：
 * 1. 快照缺失（已被修剪/清理）时**不得谎报「已回滚」**，如实暴露失败（TASK-31）；
 * 2. 回滚路径全程 `CharArray`，不经 String 中转，副本按 `saveEntry` 擦除契约由仓库清零（M2）；
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
    fun prepareDiffForCurrentEntry(revisionId: String) {
        val entryId = currentEntryId() ?: return
        scope.launch { prepareDiff(entryId, revisionId) }
    }

    /**
     * 回滚 [current] 到 [revision]：取整修订快照（含解密后的受保护字段与 TOTP 配置）全字段回滚，
     * 保存时由 HistoryManager 把回滚前的当前版本归档为最新历史。
     *
     * @return 面向用户的结果消息（成功 / 快照缺失 / 保存失败），由调用方下发到 UI 状态。
     */
    suspend fun rollback(current: UiVaultEntry, revision: UiEntryRevision): UiMessage {
        val entryId = current.id
        val snapshot = vaultRepository.getEntryRevisionSnapshot(entryId, revision.id)
            ?: return UiMessage(R.string.detail_history_rollback_failed)

        val revisionPasswordChars = vaultRepository.getEntryRevisionPasswordChars(entryId, revision.id)
        val updated = snapshot.entry.copy(
            groupId = current.groupId,
            updatedAt = strings.get(R.string.detail_rollback_updated_at)
        )
        val result = vaultRepository.saveEntry(
            updated,
            passwordChars = revisionPasswordChars,
            totpSecretChars = snapshot.totpSecretChars
        )
        return if (result is KdbxResult.Success) {
            UiMessage(R.string.detail_history_rolled_back)
        } else {
            UiMessage(R.string.edit_save_failed, listOf((result as KdbxResult.Failure).message))
        }
    }

    /** 打开历史修订对比弹窗前按需解密：当前密码 + 目标修订密码。 */
    suspend fun prepareDiff(entryId: String, revisionId: String) {
        // ISSUE-P2-15：对比路径同样改走 CharArray 借用通道，String 物化收敛在展示边界
        val currentPw = secrets.revealedPassword.value
            ?: vaultRepository.getEntryPasswordChars(entryId).toDisplayString()
        val revisionPw = vaultRepository.getEntryRevisionPasswordChars(entryId, revisionId)
            .toDisplayString()
        secrets.revealedPassword.value = currentPw
        secrets.revealedRevisionPasswords.update { it + (revisionId to revisionPw.orEmpty()) }
    }

    /** 关闭对比弹窗：清空当前密码与全部修订密码明文。 */
    fun clearDiff() {
        secrets.revealedPassword.value = null
        secrets.revealedRevisionPasswords.value = emptyMap()
    }
}
