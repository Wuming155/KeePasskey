package com.keepasskey.app.ui

import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 全局 Snackbar 事件（`ISSUE-P3-359` AC④）。
 *
 * @param message 资源化消息（展示层按当前语言解析，生产者不落硬编码文案）。
 * @param onUndo 撤销动作（`undoable` 消息专用）：**suspend 由外壳层宿主的协程作用域执行**——
 *   生产者 ViewModel 可能已随导航销毁（如详情页删除后回退），动作本体只捕获数据层引用，
 *   不依赖生产者存活；为 null 时不呈现撤销动作。
 */
class AppSnackbarEvent(
    val message: UiMessage,
    val onUndo: (suspend () -> Unit)? = null
)

/**
 * 进程内单次消费的全局 Snackbar 消息通道（`ISSUE-P3-359` AC④）。
 *
 * 十余个 Screen 此前各持 `SnackbarHostState` + 组合内 `showSnackbar`：`showSnackbar` 是挂起调用，
 * 导航离开即被组合销毁取消——消息既没显示也没 `clearUserMessage`，滞留到返回本页才弹出陈旧提示
 * （扫码导入后立即导航 / 数据库选择器 `popBackStack` 前发消息均属此型）。
 *
 * 收口为「生产者发事件 → 外壳层唯一宿主显示」：
 * - **Channel 单次消费**：每条事件至多被外壳宿主取走一次，一次性语义与既有 `clearUserMessage` 对齐；
 * - **缓冲 64 + DROP_OLDEST**：`trySend` 恒不阻塞、不失败，生产者（含测试）无需挂起；
 * - **宿主常驻**：外壳 `KeePasskeyApp` 全程组合，导航切换不中断进行中的显示与撤销回调。
 */
object AppSnackbarChannel {
    private val channel = Channel<AppSnackbarEvent>(
        capacity = CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 外壳宿主唯一消费流（生产环境仅外壳一个收集者；测试以 `first { … }` 消费并顺带排空旧事件）。 */
    val events: Flow<AppSnackbarEvent> = channel.receiveAsFlow()

    /** 发布一条消息（非挂起；缓冲满时丢弃最旧一条以保证调用方永不阻塞）。 */
    fun trySend(event: AppSnackbarEvent) {
        channel.trySend(event)
    }

    private val _hostActive = MutableStateFlow(false)

    /**
     * ISSUE-P3-446：全局宿主当前是否在组合中（即发入本通道的消息能否被**即时**显示）。
     * 由 [AppGlobalSnackbarHost] 进入 / 离开组合时置位 / 复位。
     * 非宿主可达场景（无前台主界面组合）的调用方——如剪贴板定时清空提示——
     * 据此改走 Toast 兜底，避免消息滞留缓冲、迟至下次打开主界面才弹出陈旧提示。
     */
    val hostActive: StateFlow<Boolean> get() = _hostActive

    /** 仅供 [AppGlobalSnackbarHost] 在组合进出时维护 [hostActive]；外部调用方不得使用。 */
    fun markHostActive(active: Boolean) {
        _hostActive.value = active
    }

    private const val CHANNEL_CAPACITY = 64
}
