package com.keepasskey.app.testutil

import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * ISSUE-P2-58（审计 RUST-03）AC④ 测试支持：等待「已移出主线程的纯 CPU 工作」完成。
 *
 * 背景：整库健康扫描（`SettingsHealthController`）与详情页按需解密 + 熵估算
 * （`EntryDetailRevealController`）已改在 `Dispatchers.Default` 上执行。既有用例
 * 以 `runTest` 的**虚拟时间**推进（`runCurrent()` / `advanceUntilIdle()`），
 * 而真实线程上的工作**不随虚拟时间推进**——直接断言会读到「尚未回写」的中间态。
 *
 * 本助手在**真实时间**上轮询：每轮先推进虚拟时间（让回写续体得以执行），再真实睡眠片刻，
 * 直到 [condition] 成立或超时。**不改变被测语义**，只补齐等待。
 */
internal fun TestCoroutineScheduler.awaitOffMainComputation(
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    condition: () -> Boolean
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < deadline) {
        advanceUntilIdle()
        Thread.sleep(POLL_INTERVAL_MS)
    }
    // 最后一次推进：条件已满足时把可能仍在队列中的回写续体跑完
    advanceUntilIdle()
}

private const val DEFAULT_TIMEOUT_MS = 5_000L
private const val POLL_INTERVAL_MS = 5L
