package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「搜索词写回条目 URL」自愈链的编排（`ISSUE-P3-442`）。
 *
 * 生命周期与去重语义（AC③）：
 * - 「已询问条目」集合与「本次会话不再询问」标记都活在**本协调器实例**上，
 *   而实例挂在 `VaultListViewModel` ⇒ 作用域＝**列表页 ViewModel 的存活期**（锁库 / 退栈即随之销毁，
 *   下一次会话重新开始询问）。刻意不落盘：这是一次性的匹配纠正提示，跨会话记住会把
 *   「用户这次没要」误解释成永久拒绝。
 * - 判定与写回分离：本类只决定「问不问」与执行写回；导航仍由页面负责——`prepareEntryOpen`
 *   返回 true 时调用方**先处理询问、不得直接导航**。
 *
 * 写回只改 `URL` 一个字段（`VaultEntryWriteCoordinator.updateEntryUrl`，不经 HistoryManager），
 * 故 `Override URL` 与自定义字段（含 `{REF}` 原文）不可能被顺手覆盖。
 */
internal class VaultSearchWriteBackCoordinator(
    private val repository: VaultRepository,
    private val scope: CoroutineScope,
    /** 当前搜索词（取**即时回显**值，与输入框同拍） */
    private val currentQuery: () -> String,
    private val currentEntry: (String) -> UiVaultEntry?,
    private val isReadOnly: () -> Boolean,
    private val isInsideRecycleBin: () -> Boolean,
    private val onMessage: (UiMessage) -> Unit
) {

    private val promptFlow = MutableStateFlow<SearchWriteBackPrompt?>(null)

    /** 待确认提示；null = 无（同条目一次会话只置起一次） */
    val prompt: StateFlow<SearchWriteBackPrompt?> = promptFlow.asStateFlow()

    private val askedEntryIds = mutableSetOf<String>()

    private var sessionSuppressed = false

    /**
     * 用户点开列表结果时的入口裁决。
     *
     * @return true＝已置起询问（调用方须等用户处置后再导航）；false＝无需询问，照常导航。
     */
    fun prepareEntryOpen(entryId: String): Boolean {
        val entry = currentEntry(entryId) ?: return false
        val offer = SearchWriteBackPolicy.shouldOffer(
            query = currentQuery(),
            entryUrl = entry.url,
            entryBlocked = isReadOnly() || isInsideRecycleBin() ||
                SearchWriteBackPolicy.hasReferenceUrl(entry.url),
            alreadyAsked = entryId in askedEntryIds,
            sessionSuppressed = sessionSuppressed
        )
        if (!offer) return false
        askedEntryIds += entryId
        promptFlow.value = SearchWriteBackPrompt(
            entryId = entryId,
            entryTitle = entry.title,
            currentUrl = entry.url,
            proposedUrl = currentQuery().trim()
        )
        return true
    }

    /** 用户取消（[rememberChoice]＝勾选了「本次会话不再询问」）。 */
    fun dismiss(rememberChoice: Boolean) {
        if (rememberChoice) sessionSuppressed = true
        promptFlow.value = null
    }

    /** 用户确认写入：先在后台完成写回，再如实回报成败（不阻断导航）。 */
    fun confirm(rememberChoice: Boolean) {
        val target = promptFlow.value ?: return
        if (rememberChoice) sessionSuppressed = true
        promptFlow.value = null
        scope.launch {
            val result = try {
                repository.updateEntryUrl(target.entryId, target.proposedUrl)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                null
            }
            onMessage(
                if (result is KdbxResult.Success) {
                    UiMessage(R.string.search_write_back_done, listOf(target.proposedUrl))
                } else {
                    UiMessage(R.string.search_write_back_failed, isError = true)
                }
            )
        }
    }
}
