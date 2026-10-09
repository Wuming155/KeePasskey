package com.keepasskey.sync.network

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` 的**「不吞协程取消」**版本（ISSUE-P3-555）。
 *
 * ## 为什么需要它
 *
 * `kotlinx.coroutines.CancellationException` 在 JVM 上是
 * `java.util.concurrent.CancellationException` 的别名，而后者继承 `IllegalStateException`
 * ⇒ **任何 `runCatching` / `catch (e: Exception)` / `catch (t: Throwable)` 都会把它一起吞掉**。
 * 吞掉取消的后果不是「少取消一次」：结构化并发依赖取消异常沿调用链传播来收敛作用域，
 * 被吞后上层会拿到一个看似正常的 `Result.failure` 并按**业务失败**处置——
 * `SyncCycleRunner` 会据此产出 `SyncOutcome.Error`、写缓存、甚至提示用户「同步失败」，
 * 于是一次「用户退出 / 切换密码库」被记录成一次失败同步，且日志里看不出真实原因。
 *
 * 本仓同模块的 `TransientHttpRetry` 早已显式重抛（该处是**例外而非通例**，
 * 整改前全 `sync` 模块 grep `CancellationException` 仅此一处），故本文件把口径**单点化**：
 * 与 `runCatching` 用法逐字等价，唯一差异是取消必重抛。
 *
 * ## 用法
 *
 * ```kotlin
 * runCatchingCancellable { provider.doNetworkThing() }
 * ```
 *
 * 不得用于**非挂起**且明确需要「连取消一起归一」的场景（本仓目前无此需求）。
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Result.failure(e)
    }
