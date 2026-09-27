package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 编辑页「解除通行密钥绑定」会话（`ISSUE-P3-342`，自 `EntryEditViewModel` 按职责拆出）。
 *
 * ## 为何存在
 *
 * 旧实现 `onTogglePasskey` 只翻一个草稿布尔，而 `isPasskey` **从不落盘**（既不在
 * `saveMergedEntry` 的字段清单里、也不在 `mapUiEntryToKdbx` 里，读路径反而按「凭据字段是否存在」
 * 重算它）⇒ 用户点「解除」并保存后，**凭据原封不动、仍会被 Credential Manager 列进候选并签名**。
 * 那是一个会骗人的安全控件。本会话给它一条真实通路：请求 → 确认 → 落库删除 → 重载回显。
 *
 * ## 口径（裁决见 `PD-50`）
 *
 * - **不可逆**：私钥与 PRF 种子随字段一并消失（仅历史快照留有旧值），故**必须**先经确认对话框，
 *   本类绝不在 `request()` 里写库；
 * - **只读会话与未落库条目直接拒绝**，连对话框都不弹（弹了也只能失败，那是噪声不是确认）；
 * - **未绑定态是 no-op**：没有可解除的磁盘状态；
 * - 落库后**由仓库重算并回显**绑定态（[EntryEditPasskeyUnbindHost.onUnbound] 触发重载），
 *   本类不自行把 `isPasskey` 改成 false —— 那会制造第二个真相源。
 *
 * 确认态只存活于内存，**不经**路由参数 / `SavedStateHandle`（P2-105 红线）。
 */
internal class EntryEditPasskeyUnbind(
    private val scope: CoroutineScope,
    private val repository: VaultRepository,
    /** 直接吃编辑页的状态流：绑定态 / 只读态 / 条目 id 三者同源，避免为每个字段写一条取值闭包。 */
    private val uiState: StateFlow<EntryEditUiState>,
    private val onNotice: (Int) -> Unit,
    private val onReload: () -> Unit
) {

    /** 非空即「解除绑定」确认对话框可见。 */
    private val _confirm = MutableStateFlow(false)
    val confirm: StateFlow<Boolean> = _confirm.asStateFlow()

    /** 请求解除：只做前置校验与挂确认态，**不写库**。 */
    fun request() {
        val state = uiState.value
        if (!state.isPasskey) return
        val rejectionRes = when {
            state.isReadOnly -> R.string.readonly_save_rejected
            state.entryId == null -> R.string.edit_passkey_import_requires_saved
            else -> null
        }
        if (rejectionRes != null) {
            onNotice(rejectionRes)
            return
        }
        _confirm.value = true
    }

    /** 取消：只关对话框，不写库（AC② 的取消路径）。 */
    fun dismiss() {
        _confirm.value = false
    }

    /**
     * 确认：摘掉本条目的凭据字段。
     *
     * 未命中条目 / 落库未生效 ⇒ 如实提示且**不改草稿、不假装成功**；
     * 成功才触发重载回显。对话框在动作发起前即关闭（两条结果路径都不留悬空态）。
     */
    fun confirmUnbind() {
        val entryId = uiState.value.entryId
        _confirm.value = false
        if (entryId == null) {
            onNotice(R.string.edit_passkey_unbind_failed)
            return
        }
        scope.launch {
            if (repository.clearPasskeyOnEntry(entryId) == null) {
                onNotice(R.string.edit_passkey_unbind_failed)
                return@launch
            }
            onNotice(R.string.edit_passkey_unbind_done)
            onReload()
        }
    }

    /** 编辑页销毁 / 会话锁定时的兜底收口：确认态不得跨会话残留。 */
    fun reset() {
        _confirm.value = false
    }
}
