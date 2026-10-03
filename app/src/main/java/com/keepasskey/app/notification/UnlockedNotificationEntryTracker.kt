package com.keepasskey.app.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「已解锁」常驻通知复制动作的**作用对象**：最近查看过的条目 id（`ISSUE-P3-440`）。
 *
 * 为什么需要它：常驻通知是**全局**的（不含任何条目），而「复制用户名 / 验证码」必须有明确的
 * 作用对象。取「最近在详情页查看过的条目」是与 keepass2android 前台通知等价的最贴近语义
 * （其 `CopyToClipboardService` 的通知本身就挂在当前打开的条目上）。
 *
 * 存续口径（安全复核定案 `PD-69` ②「锁库超时窗口内通知动作的存活口径」）：
 * - 仅**进程内存**态，不落盘、不进 `SavedStateHandle`（条目 id 属用户数据，不得写进持久化载体）；
 * - 库锁定 / 关闭时由 [UnlockedNotificationController.cancel] 一并清除 ⇒ 动作随通知整体撤销；
 * - 进程重启后为空（内存态）⇒ 不出现「上次会话的条目」被复制。
 */
@Singleton
class UnlockedNotificationEntryTracker @Inject constructor() {

    private val _entryId = MutableStateFlow<String?>(null)

    /** 最近查看的条目 id；null = 本进程内尚无（此时通知不挂复制动作）。 */
    val entryId: StateFlow<String?> = _entryId.asStateFlow()

    /** 登记最近查看的条目（空白视为清除）。 */
    fun set(id: String?) {
        _entryId.value = id?.takeIf { it.isNotBlank() }
    }

    /** 清除（锁库 / 关闭库 / 通知撤销）。 */
    fun clear() {
        _entryId.value = null
    }
}
