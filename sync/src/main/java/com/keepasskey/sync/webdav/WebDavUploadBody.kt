package com.keepasskey.sync.webdav

import com.keepasskey.sync.network.SyncTransferOptions
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/**
 * WebDAV PUT 请求体工厂（ISSUE-P3-03 43a）。
 *
 * 分块上传的**唯一落点**：把「整块一次性写出」与「按窗口分块流式写出」的形态选择
 * 收敛在此，WebDAV Provider 只负责调用，不承载传输策略分支（单一职责）。
 */
internal object WebDavUploadBody {

    private val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()

    /**
     * 构造请求体：
     * - 分块开关关闭，或数据量不超过单块阈值 → 定长单次写出（`Content-Length` 明确，
     *   与接线前实现完全一致，关闭开关即零行为变更）；
     * - 否则 → [ChunkedStreamingBody]（`Transfer-Encoding: chunked`，逐窗口 flush）。
     */
    fun create(data: ByteArray, transferOptions: SyncTransferOptions): RequestBody =
        if (!transferOptions.chunkedUploadEnabled ||
            data.size.toLong() <= transferOptions.chunkSizeBytes
        ) {
            data.toRequestBody(OCTET_STREAM)
        } else {
            ChunkedStreamingBody(data, transferOptions.chunkSizeBytes, OCTET_STREAM)
        }
}

/**
 * 分块流式请求体：以 [chunkSizeBytes] 为窗口顺序写出字节数组，每个窗口结束即 `flush()`，
 * 从而在 HTTP 分块传输编码下形成独立 chunk（弱网下避免整块缓冲 + 便于服务端流式落盘）。
 *
 * 取值为 [UNKNOWN_LENGTH] 使 OkHttp 采用 `Transfer-Encoding: chunked`；这是本实现
 * 相对定长写法的**唯一**协议差异，不改变请求方法、目标 URL 与预条件头。
 */
internal class ChunkedStreamingBody(
    private val data: ByteArray,
    private val chunkSizeBytes: Long,
    private val mediaType: MediaType
) : RequestBody() {

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = UNKNOWN_LENGTH

    override fun writeTo(sink: BufferedSink) {
        // 窗口长度必须先收敛到 Int：ByteArray 单次写出上限为 Int.MAX_VALUE
        val window = chunkSizeBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        var offset = 0
        while (offset < data.size) {
            val length = minOf(window, data.size - offset)
            sink.write(data, offset, length)
            sink.flush()
            offset += length
        }
    }

    companion object {
        /** OkHttp 约定：-1 表示长度未知 → 使用分块传输编码 */
        const val UNKNOWN_LENGTH = -1L
    }
}
