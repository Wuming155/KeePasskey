package com.keepasskey.sync

import com.keepasskey.sync.provider.SyncProvider
import java.io.ByteArrayOutputStream

/**
 * ISSUE-P3-206 测试适配：流式下载契约（`download(remotePath, sink)`）的
 * **ByteArray 断言便利口**——把边读边写的内容收集进内存缓冲供逐字节断言。
 *
 * 仅测试源集可用：生产引擎（`SyncEngine` / `RemoteConsistencyProbe`）一律经
 * `SyncCache.receiveRemote` 流式接收落盘，不得经本扩展整份物化。
 */
suspend fun SyncProvider.downloadBytes(remotePath: String): Result<ByteArray> {
    val sink = ByteArrayOutputStream()
    return download(remotePath, sink).map { sink.toByteArray() }
}
