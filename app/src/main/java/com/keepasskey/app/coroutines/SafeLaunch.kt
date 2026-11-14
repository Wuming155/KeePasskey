package com.keepasskey.app.coroutines

import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * UI / Activity 层协程的**出口兜底**（[guardedScope] 的调用点级补充）。
 *
 * ## 为什么需要它
 *
 * `viewModelScope` / `lifecycleScope` 只带 `SupervisorJob`，**没有** `CoroutineExceptionHandler`：
 * 逃逸到协程根的异常直达线程默认处理器并**直接杀死进程**。真机表现为「点撤销即闪退」、
 * SAF 授权失效或 DataStore 损坏时无预告闪退——用户看到的是应用消失，导出日志里连类型都没有。
 *
 * `ISSUE-P0-531` 只在同步链路逐点加了 `launchGuarded`；UI 投影、Flow 收集、撤销回调等
 * 大量启动点仍无兜底，且新代码不会自动继承。本函数把「捕获 → 脱敏留痕 → 调用点复位」
 * 收成一处，**新增 UI 侧 `launch` 一律经本函数**。
 *
 * ## 语义约定（三条）
 *
 * - `CancellationException` **原样重抛**：取消不是错误，也不得被当成失败上报；
 * - 其余 `Throwable` 先经 [AppLog.e] 落脱敏留痕（异常类名 + 有界栈帧，**不含**异常 message），
 *   再交 [onError]——调用点据此复位自身状态 / 给出用户可理解提示；
 * - [onError] 自身抛异常同样被吞并留痕：兜底不得成为新的崩溃源。进程不因单个 UI 任务失败退出。
 *
 * ## 为什么上下文里还要挂 handler
 *
 * 协程体内部再 `launch` 的子协程不经过本函数的 `try`：它的异常会先取消父协程，再由父协程
 * 以「根协程」身份交给上下文里的处理器。把处理器放进本函数启动的上下文，子协程继承同一兜底，
 * 无需逐层改造嵌套 `launch`。
 *
 * @param tag 归因标签（调用点自身名字，落日志用；必填，避免所有拦截挤进同一 TAG）
 * @param context 追加到启动上下文（例如 `Dispatchers.IO`）
 * @param onError 失败复位（非挂起回调；状态复位、用户提示）。默认空实现即「仅留痕」。
 */
internal fun CoroutineScope.safeLaunch(
    tag: String,
    context: CoroutineContext = EmptyCoroutineContext,
    onError: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit
): Job = launch(context + safeLaunchHandler(tag)) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        reportSafeLaunchFailure(tag, onError, failure)
    }
}

/** 子协程（协程体内再 `launch`）的兜底出口：同一标签、同一留痕口径。 */
private fun safeLaunchHandler(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, failure -> reportSafeLaunchFailure(tag, onError = {}, failure) }

private fun reportSafeLaunchFailure(tag: String, onError: (Throwable) -> Unit, failure: Throwable) {
    AppLog.e(tag, "未捕获的协程异常（已拦截，进程不退出）：${failure.javaClass.simpleName}", failure)
    try {
        onError(failure)
    } catch (failureInFallback: Throwable) { // cancel-n/a: 兜底分支：onError 为非挂起回调，取消已在上一分支排除
        AppLog.e(tag, "协程兜底的 onError 自身抛异常（已吞，避免二次逃逸）", failureInFallback)
    }
}
