package com.keepasskey.app.sync

import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 本守护的日志标签（异常类型与消息截断后落此；不含任何凭据明文）。 */
private const val TAG = "SyncGuardedLaunch"

/**
 * UI / 后台入口的**协程异常守护**（`ISSUE-P0-531`）。
 *
 * 存在的理由：全仓**没有**全局 `CoroutineExceptionHandler`，也没有
 * `Thread.setDefaultUncaughtExceptionHandler` ⇒ 任何逃逸到协程根的异常会直达线程默认处理器并
 * **直接杀死进程**。真机实测（`M332BF`，2026-10-08）：`DefaultDispatcher-worker-9` 上 UI 投影读到
 * 已清零的 `ProtectedString` ⇒ `IllegalStateException` ⇒ 同步后闪退，10 秒内连崩三次。
 *
 * 语义约定（三条，改前必读）：
 * - `CancellationException` **原样重抛**——协程取消不是错误，也不得被当成失败上报；
 * - 其余 `Throwable` 交给 [onFailure]，调用方必须给出**用户可理解**的提示（不得静默吞：
 *   本仓既有纪律「禁止空 catch 块或捕获后仅打印」在此同样适用）；
 * - 本函数**只管异常**，不代管状态复位——调用方在自己的 `try` / `finally` 内复位
 *   （如 `isSyncing`），否则一次失败会让指示器永久停在「同步中」。
 *
 * 与 `SyncCycleRunner.runSyncCycle` 的 `catch (Throwable)` 同口径：同步**引擎内**已自行收口并
 * 返回 `SyncOutcome`，本守护覆盖的是引擎**之外**的编排与收尾段（状态上浮、文案装配、产物收敛）
 * 以及回前台探测这类独立 scope 的后台任务。
 */
internal fun CoroutineScope.launchGuarded(
    onFailure: (Throwable) -> Unit,
    block: suspend () -> Unit
): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // `ISSUE-P3-535`（§473 复核 #6）：兜底**自身**抛异常会从本守护里再次逃逸——恰是本函数要
        // 消除的形态（`onFailure` 内的状态更新 / 文案装配同样可能抛）。故此处吞掉并留痕：守护的
        // 职责是「不让进程死」，不得成为新的崩溃源。留痕经 AppLog，不含任何敏感明文。
        try {
            onFailure(e)
        } catch (failureInFallback: Throwable) {
            AppLog.e(TAG, "同步入口守护的 onFailure 自身抛异常（已吞，避免二次逃逸）", failureInFallback)
        }
    }
}
