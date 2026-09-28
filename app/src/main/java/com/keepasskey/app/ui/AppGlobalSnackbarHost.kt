package com.keepasskey.app.ui

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.keepasskey.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * 文案解析走外壳持有的本地化 [android.content.Context]（`LocalContext` 已由 `KeePasskeyApp` 的
 * `CompositionLocalProvider` 换成随语言切换的派生上下文），格式化参数按资源占位符就地展开。
 */
@Composable
internal fun AppGlobalSnackbarHost() {
    val hostState = remember { SnackbarHostState() }
    // rememberUpdatedState：收集协程以 hostState 为唯一 key——语言切换只更新取值引用，
    // 不重启收集（重启会截断进行中的 showSnackbar，丢失该条的 ActionPerformed 回执）
    val contextState = rememberUpdatedState(LocalContext.current)

    LaunchedEffect(hostState) {
        // 展示器独立成子协程：收集循环不被 showSnackbar 挂起占满——新事件到达即可**替换**
        // 正在展示的旧条（复刻原「各屏本地宿主被下一条 showSnackbar dismiss」的语义，
        // 否则连续复制类反馈会排成 4 秒 × N 的队列）；被替换条的 await 随取消返回，
        // 未触发的撤销不执行——与旧形态「旧条被 dismiss 即 Dismissed」一致。
        var showJob: Job? = null
        AppSnackbarChannel.events.collect { event ->
            showJob?.cancel()
            hostState.currentSnackbarData?.dismiss()
            showJob = launch {
                val context = contextState.value
                val message = event.message
                val text = context.getString(message.resId, *message.args.toTypedArray())
                val undoLabel = if (message.undoable && event.onUndo != null) {
                    context.getString(R.string.btn_undo)
                } else {
                    null
                }
                val result = hostState.showSnackbar(message = text, actionLabel = undoLabel)
                if (result == SnackbarResult.ActionPerformed) {
                    // NonCancellable：撤销（恢复落库）一旦开始，不得被「下一条消息替换本条」
                    // 的 showJob 取消打断——恢复是数据写入，中断会留下半截状态
                    withContext(NonCancellable) { event.onUndo?.invoke() }
                }
            }
        }
    }

    SnackbarHost(hostState = hostState)
}
