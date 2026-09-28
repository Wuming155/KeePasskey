package com.keepasskey.app.ui.screens.edit

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

/**
 * 编辑页 IME 动作链辅助（`ISSUE-P3-359` AC①）。
 *
 * 此前编辑页各 `OutlinedTextField` 均未配置 `keyboardOptions`（默认无 Next 链），
 * 密码框的 `onDone` 又落到默认 `{}` 把框架收键盘行为吞掉——按完成键无反应。
 * 本文件把三类动作收敛为可复用的单点定义：
 * - [entryEditNextKeyboardOptions]：title → url → username → tags 等单行字段的「下一项」；
 * - [entryEditDoneKeyboardOptions]：备注 / Override URL 等末端字段的「完成」；
 * - [rememberEntryEditNextKeyboardActions]：Next → 焦点移交下一可聚焦字段；
 * - [rememberEntryEditDoneKeyboardActions] / [rememberEntryEditHideKeyboard]：
 *   Done → 收起键盘（与解锁页等既有收键盘语义一致；不触发保存，避免误提交）。
 *
 * 声明为顶层属性 / 顶层函数：调用点只写一行，键盘链路的实现细节不渗入各分节。
 */

/** 「下一项」键盘配置（单行字段 Next 链共用）。 */
internal val entryEditNextKeyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)

/** 「完成」键盘配置（备注 / Override URL 等末端字段共用）。 */
internal val entryEditDoneKeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)

/**
 * Next 动作：把焦点交给表单中的下一个可聚焦字段。
 * `FocusManager` 经组合局部读取后以 `remember` 闭包捕获，避免每次重组生成新实例。
 */
@Composable
internal fun rememberEntryEditNextKeyboardActions(): KeyboardActions {
    val focusManager = LocalFocusManager.current
    return remember(focusManager) {
        KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) })
    }
}

/** Done 动作（键盘链路版）：收起软键盘。 */
@Composable
internal fun rememberEntryEditDoneKeyboardActions(): KeyboardActions {
    val keyboardController = LocalSoftwareKeyboardController.current
    return remember(keyboardController) {
        KeyboardActions(onDone = { keyboardController?.hide() })
    }
}

/**
 * Done 动作（自有 `onDone` 通路字段版，如 [SecurePasswordField]）：仅返回收键盘闭包。
 *
 * `SecurePasswordField` 内部固定 `KeyboardActions(onDone = { onDone() })`——
 * 默认 `{}` 会把框架自带的收键盘行为吞掉（按完成键无反应），调用方必须显式接线。
 */
@Composable
internal fun rememberEntryEditHideKeyboard(): () -> Unit {
    val keyboardController = LocalSoftwareKeyboardController.current
    return remember(keyboardController) { { keyboardController?.hide() } }
}
