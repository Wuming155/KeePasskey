package com.keepasskey.sync.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * 请求级瞬时错误重试（ISSUE-P3-298 ③）。
 *
 * 仅针对**传输层瞬时失败**（[IOException] 家族：连接重置 / 超时 / DNS 抖动）与**瞬时可重试
 * 的 HTTP 状态**（[isRetryableStatus]：408 / 425 / 429 / 5xx）做指数退避重试，使单次抖动不再
 * 把整个同步周期打成失败、只能等下个周期（对照 kp2a `BackgroundSyncService` 按状态码重试）。
 *
 * 重试安全边界（与 `ISSUE-P1-275` 乐观锁语义合流，AC③「禁重试即无条件 PUT」）：
 * - 读请求（PROPFIND / GET / HEAD / DELETE）与携带**服务器预条件**的写请求（`If-Match` /
 *   `If` / `If-None-Match`）才允许重试——预条件在服务端**逐次重验**，重试天然满足
 *   「重试前重新校验基线」：若基线已被他人推进，重试得到 412 →
 *   `SyncException.ConflictError`，绝不静默覆盖他人数据；
 * - **不携带预条件的写请求（无条件 PUT）禁止重试**——调用方须以「请求实际附带了预条件头」
 *   为闸门决定是否包裹本重试（见 `WebDavSyncProvider.executeTransientRetryable`）；
 * - `SyncException` 等**业务语义异常**（鉴权失败 / 协议错误 / 冲突 / 文件不存在）不是
 *   [IOException]，一律不重试——它们是确定性结论，重试只会放大无效流量。
 */
object TransientHttpRetry {

    /** 退避上限：避免弱网下单请求重试拖长整个周期 */
    const val DEFAULT_MAX_DELAY_MS = 4_000L

    /**
     * 瞬时可重试的 HTTP 状态码：请求超时 408 / Too Early 425 / 限流 429 / 服务端 5xx。
     * 401/403（鉴权）、404（不存在）、412（预条件失败）等属确定性结论，不在重试面。
     */
    fun isRetryableStatus(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code in 500..599

    /**
     * 传输层失败是否瞬时可重试：仅 [IOException] 家族（含 [RetryableStatus]）。
     * [CancellationException] 不是 [IOException]，协程取消不重试。
     */
    fun isRetryableFailure(t: Throwable): Boolean = t is IOException

    /**
     * 「响应已收到、但状态码瞬时可重试」的信号异常：调用方在重试包裹内关闭响应体后抛出，
     * 由 [run] 按退避重试；重试耗尽后作为最终失败上抛。
     */
    class RetryableStatus(val code: Int) : IOException("HTTP $code (transient retryable)")

    /**
     * 按「指数退避（`base × 2^attempt`，封顶 [maxDelayMs]）」执行 [block]，最多 [maxAttempts] 次。
     * 纯调度内核：可重试性判定全部委托给 [isRetryableFailure]，不含任何协议细节。
     */
    suspend fun <T> run(
        maxAttempts: Int,
        baseDelayMs: Long,
        maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
        block: suspend (attempt: Int) -> T
    ): T {
        require(maxAttempts >= 1) { "maxAttempts 必须 ≥ 1" }
        for (attempt in 0 until maxAttempts) {
            try {
                return block(attempt)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (attempt == maxAttempts - 1 || !isRetryableFailure(t)) throw t
            }
            delay((baseDelayMs shl attempt).coerceAtMost(maxDelayMs))
        }
        error("不可达：循环内必然 return 或 throw")
    }
}
