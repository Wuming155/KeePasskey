package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.model.UiMessage

/**
 * 详情页消息发布（`ISSUE-P3-359` AC④）：状态层双写 + 全局通道单发。
 *
 * 1. `userMessageFlow` 照旧记录（既有 `uiState.userMessage` 断言与一次性消费口径不变）；
 * 2. 同步发往 [AppSnackbarChannel]——**在 ViewModel 方法体内完成**，删除确认后立即回退导航
 *    也不会丢消息（旧形态由屏内组合 `showSnackbar`，回退先到即取消消费）。
 *
 * [onUndo] 供软删除撤销（`UiMessage.undoable`）携带：由外壳宿主的协程作用域执行——
 * 详情页 ViewModel 随回退已销毁，动作本体只捕获数据层引用，不依赖生产者存活。
 */
internal fun EntryDetailViewModel.publishDetailMessage(
    message: UiMessage,
    onUndo: (suspend () -> Unit)? = null
) {
    userMessageFlow.value = message
    AppSnackbarChannel.trySend(AppSnackbarEvent(message = message, onUndo = onUndo))
}
