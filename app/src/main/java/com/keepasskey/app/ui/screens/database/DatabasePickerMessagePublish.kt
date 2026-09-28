package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.model.UiMessage

/**
 * 数据库选择页消息发布（`ISSUE-P3-359` AC④ 自 `DatabasePickerViewModel` 结构性下沉）。
 *
 * 双写语义：
 * 1. `userMessageFlow` 照旧记录（既有 `uiState.userMessage` 断言与一次性消费口径不变）；
 * 2. 同一条消息同步发往 [AppSnackbarChannel]——**在 ViewModel 方法体内完成**：
 *    打开库成功的消息与 `DatabaseSelected`（宿主随即 `popBackStack`）在同一调用链发出，
 *    旧形态由屏内组合消费，退栈先到即取消——消息既没显示也没 clear，正是
 *    `DatabasePickerViewModel` KDoc 自认的「Snackbar 往往来不及渲染」缺陷；全局宿主常驻即无此窗口。
 */
internal fun DatabasePickerViewModel.publishPickerMessage(message: UiMessage) {
    userMessageFlow.value = message
    AppSnackbarChannel.trySend(AppSnackbarEvent(message = message))
}
