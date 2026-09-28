package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.model.UiMessage

/**
 * 列表页消息发布（`ISSUE-P3-359` AC④ 自 `VaultListViewModel` 结构性下沉——母文件 500 行零余量，
 * 发布逻辑不得内联加行；`userMessageFlow` 相应放宽为 `internal` 供本文件消费，行为零变更）。
 *
 * 双写语义：
 * 1. `userMessageFlow` 照旧记录（既有 `uiState.userMessage` 断言与一次性消费口径不变）；
 * 2. 同一条消息同步发往 [AppSnackbarChannel]——**在 ViewModel 方法体内完成**，与后续
 *    `navigate` / `popBackStack` 谁先谁后都无关：旧形态由屏内组合 `showSnackbar` 消费，
 *    导航先到即取消消费，扫码导入「发消息后立即导航」的消息因此丢失、返回才弹陈旧提示。
 *
 * [UiMessage.undoable] 的软删除消息附带撤销动作（列表页仍在栈上，入口走既有
 * `undoPendingSoftDelete`）；外壳宿主只在 `SnackbarResult.ActionPerformed` 时执行。
 */
internal fun VaultListViewModel.publishVaultMessage(message: UiMessage) {
    userMessageFlow.value = message
    AppSnackbarChannel.trySend(
        AppSnackbarEvent(
            message = message,
            onUndo = if (message.undoable) {
                { undoPendingSoftDelete() }
            } else {
                null
            }
        )
    )
}
