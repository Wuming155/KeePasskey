package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.passkey.PasskeyImportDraft
import com.keepasskey.app.passkey.PasskeyImportDraftHost
import com.keepasskey.app.passkey.PasskeyImportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 编辑页通行密钥导入的宿主快照与回召通道（`ISSUE-P3-337` Q1）。
 *
 * §284 的参数对象化口径：本类只被 [EntryEditPasskeyImport] 一处消费，四个读取回调 + 两个
 * 动作回调收成一个宿主量，避免反向持有 ViewModel。
 */
internal data class EntryEditPasskeyImportHost(
    /** 会话只读判据（只读一律硬拒绝）。 */
    val isReadOnly: () -> Boolean,
    /** 当前编辑条目的 id；null = 尚未落库的新建表单（无「当前条目」可挂）。 */
    val entryId: () -> String?,
    /** 表单是否有未保存改动（此时导入会让重载悄悄吞掉它们，见 [EntryEditPasskeyImport.confirm]）。 */
    val hasUnsavedEdits: () -> Boolean,
    /** 静态文案通道（资源 id → 宿主包成 `UiMessage`）。 */
    val onNotice: (Int) -> Unit,
    /** 替换成功后的重载指令：把库内新状态读回表单（见 [EntryEditPasskeyImport.confirm]）。 */
    val onReplaced: () -> Unit
)

/**
 * 编辑页的「扫码 / 相册导入通行密钥」会话（`ISSUE-P3-337` Q1 + 口径 5，AC①③⑩⑪）。
 *
 * ## 与顶栏入口的分岔
 *
 * 解析、草案承载、擦除与静态拒收文案全在 [PasskeyImportDraftHost]（两个入口共用一份，
 * AC⑤ 的「不允许复制第二份」在草案层面同口径适用）；**只有落库语义不同**：
 * 顶栏新建并落当前分组，本页**按条目 id 原地替换**（`replacePasskeyOnEntry`）。
 *
 * ## 为什么不走 `saveOrReplacePasskeyEntry`（口径 5 原文的偏离，第 3 片已登记）
 *
 * 那个入口按「rpId + 用户名」检索可复用条目，库里同站点同用户名有**另一条**时它会写到那条，
 * 而不是用户正在编辑的这一条 ⇒ Q1 的「挂当前条目」必须按 id 定位（`PasskeyEntryCoordinator`
 * 新增 `replacePasskeyOnEntry`，会话 Mutex 内单次原子替换，非 passkey 字段按引用保留）。
 *
 * ## 为什么「有未保存改动」时拒绝导入
 *
 * 替换写的是**库内**条目，表单里的改动还一行未落；若替换后重载，用户刚打的字会被库内旧值
 * 顶掉且没有任何提示——那是静默丢数据。宁可让用户先保存（一次点击）也不悄悄吃改动。
 *
 * ## 为什么替换后要重载
 *
 * 表单里那些 passkey 自定义字段（含受保护字段的明文副本）此刻已属**上一把凭据**；
 * 不读回新状态，用户随后点保存就会把旧凭据写回去，导入白做。
 * 新建表单（[EntryEditPasskeyImportHost.entryId] 为 null）没有「当前条目」可挂，故入口只在
 * 已保存条目上出现。
 */
internal class EntryEditPasskeyImport(
    private val scope: CoroutineScope,
    private val repository: VaultRepository,
    private val host: EntryEditPasskeyImportHost
) {
    private val drafts = PasskeyImportDraftHost(
        isReadOnly = host.isReadOnly,
        onNotice = host.onNotice
    )

    /** 非空即「等待确认」的草案（宿主据此显示与顶栏同一个 [com.keepasskey.app.ui.screens.vault.PasskeyImportConfirmDialog]）。 */
    val pendingPasskeyImport: StateFlow<PasskeyImportDraft?> = drafts.pending

    /** 分流判定为通行密钥形态后的入口：解析成功即挂起等确认，失败按静态文案拒绝。 */
    fun beginFromScan(decoded: CharArray) {
        drafts.begin(decoded)
    }

    /** 用户取消：擦除草案、不写库（AC③ 取消路径）。 */
    fun dismiss() = drafts.dismiss()

    /** 编辑页销毁 / 会话锁定时的兜底擦除（`clearAllSecrets` 同批接入）。 */
    fun wipeAll() = drafts.wipeAll()

    /**
     * 用户点「替换」：草案 → [com.keepasskey.core.model.PasskeyData] → **按当前条目 id** 原地替换。
     *
     * 草案在**每一条**路径上都被擦除（含前置校验失败与写库返回 null）——它是私钥明文的唯一持有者。
     * 写库返回 null＝条目已被删除 / id 非法，此时如实提示且**不重载、不假装成功**。
     */
    fun confirm() {
        val draft = drafts.takeForImport() ?: return
        val entryId = host.entryId()
        val rejectionRes = when {
            host.isReadOnly() -> R.string.readonly_save_rejected
            entryId == null || host.hasUnsavedEdits() -> R.string.edit_passkey_import_requires_saved
            else -> null
        }
        if (rejectionRes != null) {
            draft.wipe()
            host.onNotice(rejectionRes)
            return
        }
        val data = PasskeyImportFactory.toPasskeyData(draft.credential)
        draft.wipe()
        scope.launch {
            if (repository.replacePasskeyOnEntry(requireNotNull(entryId), data) == null) {
                host.onNotice(R.string.passkey_import_entry_missing)
                return@launch
            }
            // ISSUE-P3-342：文案须**如实说明这条通路不经「保存」**——用户此前据此认为
            // 右上角保存与导入按钮功能重叠（实际两者写的是完全不同的字段集）。
            host.onNotice(R.string.edit_passkey_import_saved_now)
            host.onReplaced()
        }
    }
}
