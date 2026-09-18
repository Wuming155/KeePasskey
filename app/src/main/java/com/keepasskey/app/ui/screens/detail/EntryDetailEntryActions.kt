package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.AutofillBlockState
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.CustomIconAdmin
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 详情页条目动作簇（ISSUE-P3-188 自 `EntryDetailViewModel` 结构性下沉，零行为变更）：
 * 克隆 / 删除 / 移动 / 库级自定义图标删除 / 自动填充屏蔽开关。
 * 结果一律经 [showMessage] 如实上浮（不乐观谎报），成功删除经 [onEntryDeleted] 发导航信号。
 */
internal class EntryDetailEntryActions(
    private val vaultRepository: VaultRepository,
    private val autofillBlocklistStore: AutofillBlocklistStore,
    private val customIconAdmin: CustomIconAdmin?,
    private val scope: CoroutineScope,
    private val currentEntryId: () -> String?,
    private val isReadOnly: () -> Boolean,
    private val boundPackage: () -> String?,
    private val boundCustomIconId: () -> String?,
    private val onEntrySwitched: (String) -> Unit,
    private val onEntryDeleted: () -> Unit,
    private val showMessage: (UiMessage) -> Unit
) {

    /**
     * 克隆当前条目（TASK-16）：全字段保真复制 + 新 UUID + 清历史，落库后
     * 详情页就地切换至克隆体。失败如实上浮（H3 语义）。
     */
    fun duplicateEntry() {
        val entryId = currentEntryId() ?: return
        scope.launch {
            when (val result = vaultRepository.duplicateEntry(entryId)) {
                is KdbxResult.Success -> {
                    showMessage(UiMessage(R.string.detail_duplicate_success))
                    onEntrySwitched(result.data)
                }
                is KdbxResult.Failure ->
                    showMessage(UiMessage(R.string.edit_save_failed, listOf(result.message)))
            }
        }
    }

    /**
     * ISSUE-P3-48：删除当前条目（单条入口）。
     *
     * 语义由仓库回收站分流决定：条目不在回收站内 → 软删移入回收站（可还原）；
     * 条目已在回收站内或回收站被禁用 → 物理删除并记录墓碑。
     * 只读会话 / 缺少条目 id 时为 no-op；成功置一次性信号供 Screen 回退导航，
     * 失败经 [KdbxResult.Failure] 如实上浮（不谎报成功）。
     */
    fun deleteEntry() {
        val entryId = currentEntryId() ?: return
        if (isReadOnly()) return
        scope.launch {
            when (val result = vaultRepository.deleteEntry(entryId)) {
                is KdbxResult.Success -> onEntryDeleted()
                is KdbxResult.Failure ->
                    showMessage(UiMessage(R.string.vault_op_failed, listOf(result.message)))
            }
        }
    }

    /**
     * ISSUE-P3-51：把当前条目移动到目标分组（null = 根目录）。
     * 复用仓库批量移动通道（单元素集合）；只读会话 / 缺条目 id 为 no-op；
     * 成功 / 失败经 [showMessage] 如实告知。
     */
    fun moveEntryToGroup(targetGroupId: String?) {
        val entryId = currentEntryId() ?: return
        if (isReadOnly()) return
        scope.launch {
            when (val result = vaultRepository.batchMoveEntries(setOf(entryId), targetGroupId)) {
                is KdbxResult.Success -> showMessage(UiMessage(R.string.detail_move_success))
                is KdbxResult.Failure ->
                    showMessage(UiMessage(R.string.vault_op_failed, listOf(result.message)))
            }
        }
    }

    /**
     * ISSUE-P3-02（TASK-49）：删除当前条目绑定的库级自定义图标。
     *
     * 自定义图标是**库级共享资源**：删除会移除 KDBX Meta 图标池条目，并把全部引用该图标的
     * 条目回退为默认图标，故 Screen 侧必须先经确认弹窗（[EntryDetailScreen] 的删除确认）；
     * 只读会话、无绑定图标或缺少注入通道时为 no-op / 如实失败，绝不谎报成功。
     */
    fun deleteCustomIcon() {
        val iconId = boundCustomIconId() ?: return
        if (isReadOnly()) return
        val admin = customIconAdmin
        if (admin == null) {
            showMessage(UiMessage(R.string.vault_icon_delete_failed))
            return
        }
        scope.launch {
            when (val result = admin.deleteCustomIcon(iconId)) {
                is KdbxResult.Success -> showMessage(UiMessage(R.string.vault_icon_delete_done))
                is KdbxResult.Failure ->
                    showMessage(UiMessage(R.string.vault_op_failed, listOf(result.message)))
            }
        }
    }

    /**
     * TASK-44：切换「为本应用禁用自动填充」——写入/移出自动填充黑名单。
     *
     * 仅当条目 URL 携带 `android://<包名>` 绑定（即凭据确有明确归属应用）时可用；
     * 未绑定应用的条目（如纯 Web 凭据）本入口不呈现。
     * 结果经 [showMessage] 如实告知用户（屏蔽 / 恢复），不做乐观谎报。
     *
     * ISSUE-P3-15：判定改用三态 [AutofillBlockState]。绑定包名缺失或非法（不可识别）时，
     * **不执行任何写操作**（不调 add / remove），亦不产出「已屏蔽 / 已恢复」语义，
     * 只如实提示「无法识别应用标识」——填充侧 fail-closed 判定不受本改动影响。
     */
    fun toggleAutofillBlockForApp() {
        val packageName = boundPackage()
        if (packageName == null) {
            showUnidentifiablePackageMessage()
            return
        }
        when (autofillBlocklistStore.resolveBlockState(packageName)) {
            AutofillBlockState.UnidentifiablePackage -> showUnidentifiablePackageMessage()
            AutofillBlockState.Blocked -> {
                autofillBlocklistStore.remove(packageName)
                showMessage(UiMessage(R.string.detail_autofill_unblocked, listOf(packageName)))
            }
            AutofillBlockState.NotBlocked -> {
                autofillBlocklistStore.add(packageName)
                showMessage(UiMessage(R.string.detail_autofill_blocked, listOf(packageName)))
            }
        }
    }

    /** ISSUE-P3-15：不可识别包名的如实提示（不含任何「已屏蔽 / 已恢复」语义） */
    private fun showUnidentifiablePackageMessage() {
        showMessage(UiMessage(R.string.autofill_block_unidentifiable_package))
    }
}
