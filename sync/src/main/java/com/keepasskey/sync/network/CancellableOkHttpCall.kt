package com.keepasskey.sync.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 在挂起边界执行 OkHttp 阻塞式请求，并将结构化取消传递到底层活动 Call。
 *
 * 调用方应在 Dispatchers.IO 上调用。取消与响应到达竞态时，若 continuation 已取消，
 * 响应会立即关闭，避免把未消费的 body 留给脱离调用方的网络任务。
 */
internal suspend fun executeCancellable(call: Call): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    try {
        val response = call.execute()
        continuation.resume(response) { _, value, _ -> value.close() }
    } catch (error: Throwable) {
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}
