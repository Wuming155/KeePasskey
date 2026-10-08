package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.passkey.ScanPayloadClassifier
import com.keepasskey.app.passkey.ScanPayloadKind
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import kotlinx.coroutines.flow.StateFlow

/**
 * VaultListViewModel 的写操作 / 同步动作对外门面（§280 规模门禁同批自 [VaultListViewModel]
 * **逐字迁出**——扩展函数形态，调用点语法不变，语义零变化；编排本体在
 * [VaultListActionController] / [VaultListSyncController]）。
 */

fun VaultListViewModel.copyPassword(entry: UiVaultEntry) = actions.copyPassword(entry)

fun VaultListViewModel.copyUsername(entry: UiVaultEntry) = actions.copyUsername(entry)

/** ISSUE-P3-184：列表行徽标一次点击复制当前 TOTP 验证码（HOTP 由 actions 硬拒绝）。 */
fun VaultListViewModel.copyTotpCode(entry: UiVaultEntry) = actions.copyTotpCode(entry)

fun VaultListViewModel.clearUserMessage() {
    userMessageFlow.value = null
}

// ===== ISSUE-P3-442：搜索词「写回条目 URL」自愈链（判定内核见 SearchWriteBackPolicy） =====

/** 待确认的写回提示（null = 无）；页面据此渲染询问对话框。 */
val VaultListViewModel.searchWriteBackPrompt: StateFlow<SearchWriteBackPrompt?>
    get() = searchWriteBack.prompt

/**
 * 搜索态下用户点开列表结果：命中自愈条件时置起询问并返回 true
 * （调用方**不得**直接导航，须等用户处置后再调 `onEntryClick`）；false＝照常导航。
 */
fun VaultListViewModel.prepareSearchWriteBack(entryId: String): Boolean =
    searchWriteBack.prepareEntryOpen(entryId)

/** 用户确认写入（[rememberChoice]＝勾选了「本次会话不再询问」）。 */
fun VaultListViewModel.confirmSearchWriteBack(rememberChoice: Boolean) =
    searchWriteBack.confirm(rememberChoice)

/** 用户取消写入（[rememberChoice] 同上）。 */
fun VaultListViewModel.dismissSearchWriteBack(rememberChoice: Boolean) =
    searchWriteBack.dismiss(rememberChoice)

// 批量管理操作

fun VaultListViewModel.startBatchMode(initialEntryId: String) = actions.startBatchMode(initialEntryId)

fun VaultListViewModel.toggleEntrySelection(entryId: String) = actions.toggleEntrySelection(entryId)

fun VaultListViewModel.selectAllEntries() = actions.selectAllEntries()

fun VaultListViewModel.clearBatchSelection() = actions.clearBatchSelection()

fun VaultListViewModel.batchMoveSelected(targetGroupId: String?) = actions.batchMoveSelected(targetGroupId)

fun VaultListViewModel.batchDeleteSelected() = actions.batchDeleteSelected()

fun VaultListViewModel.undoPendingSoftDelete() = actions.undoBatchDelete() // ISSUE-P2-357 AC②：软删除 Snackbar 的撤销入口

/** 下拉手势同步触发：真实执行 SyncCoordinator 全量同步（不再使用演示性假桩） */
fun VaultListViewModel.triggerPullRefresh() = syncController.triggerPullRefresh()

/**
 * `ISSUE-P2-291` AC②：库身份绑定不符待确认位（整库覆盖确认对话框的可见性）。
 */
val VaultListViewModel.pendingBindingTakeover: StateFlow<Boolean> get() = syncController.pendingBindingTakeover

/** 用户确认整库覆盖云端副本并改绑当前库 */
fun VaultListViewModel.confirmBindingTakeover() = syncController.confirmBindingTakeover()

/** 用户取消整库覆盖（保持本地与云端现状） */
fun VaultListViewModel.dismissBindingTakeover() = syncController.dismissBindingTakeover()

/** `ISSUE-P2-529` AC①：先把云端副本另存为本地独立库，再整库覆盖并改绑（两份都留） */
fun VaultListViewModel.confirmBindingTakeoverKeepingCopy() =
    syncController.confirmBindingTakeoverKeepingCopy()

fun VaultListViewModel.createGroup(name: String, iconName: String = "folder") = actions.createGroup(name, iconName)

fun VaultListViewModel.renameGroup(group: VaultGroup, newName: String) = actions.renameGroup(group, newName)

fun VaultListViewModel.changeGroupIcon(group: VaultGroup, newIcon: String) = actions.changeGroupIcon(group, newIcon)

fun VaultListViewModel.deleteGroup(groupId: String) = actions.deleteGroup(groupId)

fun VaultListViewModel.restoreEntry(entryId: String) = actions.restoreEntry(entryId)

fun VaultListViewModel.purgeEntry(entryId: String) = actions.purgeEntry(entryId)

fun VaultListViewModel.emptyRecycleBin() = actions.emptyRecycleBin()

/**
 * 顶栏「扫码」解码上行（PD-47 同链路：框架边界 String 已由对话框转 CharArray，
 * 擦除义务移交写编排）。
 *
 * `ISSUE-P3-337` 口径 1：**先分流再处理**——`otpauth:` 走既有 TOTP 链（一字未改），
 * JSON 形态走通行密钥链（解析后只进确认草案），其余一律如实拒绝。
 * **禁止回退式猜测**（不得「先按 TOTP 解、失败再按通行密钥解」）：两个解析器都留了
 * 宽容面，顺序猜错就是把任意文本当口令种子落库、或错拒一把完好凭据。
 */
fun VaultListViewModel.onQrCodeDecoded(decoded: CharArray) {
    when (ScanPayloadClassifier.classify(decoded)) {
        ScanPayloadKind.Totp -> actions.addEntryFromScannedOtpauth(decoded)
        ScanPayloadKind.Passkey -> actions.beginPasskeyImportFromScan(decoded)
        ScanPayloadKind.Unknown -> actions.rejectUnknownScannedQr(decoded)
    }
}

/** 待确认的通行密钥导入草案（非空即确认对话框可见；仅内存持有，见 `PasskeyImportDraft`）。 */
val VaultListViewModel.pendingPasskeyImport get() = actions.pendingPasskeyImport

/** 用户在确认对话框点「导入」。 */
fun VaultListViewModel.confirmPasskeyImport() = actions.confirmPasskeyImport()

/** 用户取消导入（草案即刻擦除，不落库不导航）。 */
fun VaultListViewModel.dismissPasskeyImport() = actions.dismissPasskeyImport()

/** 导入成功后待打开的条目 id（一次性消费；只带 id，不承载任何凭据值）。 */
val VaultListViewModel.openEntryEditId get() = actions.openEntryEditId

fun VaultListViewModel.consumeOpenEntryEditId() = actions.consumeOpenEntryEditId()
