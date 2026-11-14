package com.keepasskey.app.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.keepasskey.app.R
import com.keepasskey.app.coroutines.safeLaunch
import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 协程兜底与撤销失败的日志标签（不含任何凭据或条目内容）。 */
private const val TAG = "AppGlobalSnackbarHost"

/**
 * 全局唯一 Snackbar 宿主（`ISSUE-P3-359` AC④，挂载于 [AppShellScaffold] 的 `snackbarHost` 槽）。
 *
 * 消费循环常驻外壳组合：**导航切换不销毁**——生产者在 `navigate` / `popBackStack` 的同一调用链里
 * 发出的消息照常显示（旧形态是各屏组合内 `showSnackbar` 被取消，消息丢失、返回才弹陈旧提示）。
 *
 * 撤销（`UiMessage.undoable`）在**本层协程作用域**执行 [AppSnackbarEvent.onUndo]：
 * 批量删除（列表页）、单条软删除（详情页，其 ViewModel 随回退已销毁）的恢复动作都不依赖生产者存活；
 * 只有 `SnackbarResult.ActionPerformed` 才触发，超时 / 划走一律不恢复
 * （口径同原屏级 `VaultListSnackbarEffect`——该件已随本迁移退役，判据由接线守卫改锚本文件）。
 *
 * 撤销失败（写库失败、SAF 授权失效、目标条目已被他端删除）不杀进程：留痕后以同一宿主提示
 * [R.string.undo_failed]，让「内容未恢复」这件事对用户是明说而不是静默。
 *
 * 文案解析走外壳持有的本地化 [android.content.Context]（`LocalContext` 已由 `KeePasskeyApp` 的
 * `CompositionLocalProvider` 换成随语言切换的派生上下文），格式化参数按资源占位符就地展开。
 *
 * §373：无撤销动作时才尊重 [com.keepasskey.app.ui.model.UiMessage.durationMillis]
 * （同步状态类反馈 ≈2s）；有撤销动作恒走 Material Long（≈10s），不得被收短。
 */
@Composable
internal fun AppGlobalSnackbarHost() {
    val hostState = remember { SnackbarHostState() }
    // rememberUpdatedState：收集协程以 hostState 为唯一 key——语言切换只更新取值引用，
    // 不重启收集（重启会截断进行中的 showSnackbar，丢失该条的 ActionPerformed 回执）
    val contextState = rememberUpdatedState(LocalContext.current)

    LaunchedEffect(hostState) {
        // ISSUE-P3-446：进入组合即向通道登记「宿主可达」，离开组合（LaunchedEffect 取消）
        // 时复位——finally 在任何取消路径（导航销毁 / Activity 销毁）都会执行。
        // 非组合期的生产者据此改走 Toast 兜底，消息不滞留缓冲形成迟到的陈旧提示。
        try {
            AppSnackbarChannel.markHostActive(true)
            // 展示器独立成子协程：收集循环不被 showSnackbar 挂起占满——新事件到达即可**替换**
            // 正在展示的旧条（复刻原「各屏本地宿主被下一条 showSnackbar dismiss」的语义，
            // 否则连续复制类反馈会排成 4 秒 × N 的队列）；被替换条的 await 随取消返回，
            // 未触发的撤销不执行——与旧形态「旧条被 dismiss 即 Dismissed」一致。
            var showJob: Job? = null
            AppSnackbarChannel.events.collect { event ->
                showJob?.cancel()
                hostState.currentSnackbarData?.dismiss()
                // safeLaunch：消息装配（资源格式化）/ 展示 / 撤销三段的未预期异常一律拦截并留痕，
                // 不得从组合期协程逃逸杀掉进程——撤销回调用户侧最易触发（写库 / SAF 授权失效）。
                showJob = safeLaunch(TAG) {
                    val context = contextState.value
                    val message = event.message
                    val text = context.getString(message.resId, *message.args.toTypedArray())
                    val undoLabel = if (message.undoable && event.onUndo != null) {
                        context.getString(R.string.btn_undo)
                    } else {
                        null
                    }
                    // §373：无撤销动作时才尊重自定义时长；有撤销动作恒走 Material Long
                    val overrideMs = if (undoLabel == null) message.durationMillis else null
                    val dismissJob = if (overrideMs != null) {
                        launch {
                            delay(overrideMs)
                            hostState.currentSnackbarData?.dismiss()
                        }
                    } else {
                        null
                    }
                    val result = hostState.showSnackbar(
                        message = text,
                        actionLabel = undoLabel,
                        duration = if (undoLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
                    )
                    dismissJob?.cancel()
                    if (result == SnackbarResult.ActionPerformed) {
                        val undo = event.onUndo ?: return@safeLaunch
                        runSnackbarUndo(
                            onUndo = undo,
                            onFailure = { hostState.showSnackbar(context.getString(R.string.undo_failed)) }
                        )
                    }
                }
            }
        } finally {
            AppSnackbarChannel.markHostActive(false)
        }
    }

    SnackbarHost(hostState = hostState)
}

/**
 * 撤销动作的失败策略（`ISSUE-P3-359` 迁移后撤销在宿主作用域执行，本函数把它与「失败怎么办」解耦）。
 *
 * - **必须**在 `NonCancellable` 里执行：撤销（恢复落库）一旦开始，不得被「下一条消息替换本条」的
 *   `showJob` 取消打断——恢复是数据写入，中断会留下半截状态；
 * - `CancellationException` 原样重抛（取消不是错误）；
 * - 其余异常如实留痕后调 [onFailure] 告知用户「内容未恢复」——用户以为已撤销而实际数据没回来，
 *   比闪退更难排查。异常原文不入 UI，只出类型名。
 */
internal suspend fun runSnackbarUndo(
    onUndo: suspend () -> Unit,
    onFailure: suspend () -> Unit
) {
    try {
        withContext(NonCancellable) { onUndo() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        AppLog.e(TAG, "撤销动作失败（已拦截，进程不退出）：${failure.javaClass.simpleName}", failure)
        onFailure()
    }
}
