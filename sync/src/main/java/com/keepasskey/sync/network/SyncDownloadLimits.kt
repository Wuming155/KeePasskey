package com.keepasskey.sync.network

import com.keepasskey.sync.model.SyncException
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 下载体入口封顶（ISSUE-P0-09 / ISSUE-P2-75）。
 *
 * 威胁面：远端服务器（或系统 CA 级 MITM——本项目零证书固定）是**唯一可单方面触发**
 * 超大响应的一方。`response.body?.bytes()` / `body?.string()` 把下载体**整体物化**，
 * 超大响应直接 OOM；此前 `RemoteFileMetadata.contentLength` 只作元数据传递、从未作为守卫。
 *
 * 双重封顶语义（AC①「流式 + 声明尺寸双重封顶，超限即拒绝，不物化」）：
 * 1. **声明尺寸预检**：`Content-Length` 如实声明且超限时，不建立任何缓冲直接拒绝；
 * 2. **流式累积封顶**：声明缺失（chunked）或撒谎（声明值小于实际）时，按块累积，
 *    累计字节一旦越过上限立即抛错拒绝——缓冲总量恒被上限约束，绝不 OOM。
 */
object SyncDownloadLimits {

    /**
     * 数据库下载体上限：128 MiB。
     * 合法 `.kdbx` 库（附件 >1 MiB 已落盘磁盘缓存，不入库字节流）远小于该值；
     * 上限取两个数量级裕量，仅拦截「远端单方面灌大响应」的异常形态。
     */
    const val MAX_DOWNLOAD_BYTES: Long = 128L * 1024 * 1024

    /**
     * PROPFIND 元数据响应上限：16 MiB。
     * `Depth: 0` 的 multistatus 报文通常仅数 KB，该上限已极宽裕，
     * 防「元数据通道被灌大」的整体物化（`body.string()`）。
     */
    const val MAX_PROPFIND_BYTES: Long = 16L * 1024 * 1024

    /**
     * 有界读取 [input] 全部字节，超出 [maxBytes] 即抛 [SyncException.ProtocolError]（HTTP 413 语义）。
     *
     * @param declaredLength 服务端声明的内容长度（`-1` 表示未声明 / chunked）
     * @param label 错误信息中的通道标识（如 "WebDAV" / "S3" / "PROPFIND"）
     */
    fun readBounded(
        input: InputStream,
        declaredLength: Long,
        maxBytes: Long = MAX_DOWNLOAD_BYTES,
        label: String
    ): ByteArray {
        // ① 声明尺寸预检：不建立缓冲直接拒绝
        if (declaredLength > maxBytes) {
            throw SyncException.ProtocolError(
                413,
                "$label 响应体超出上限（声明 $declaredLength 字节 > 上限 $maxBytes 字节），已拒绝接收"
            )
        }
        // ② 流式累积封顶：缓冲总量恒被 maxBytes 约束，声明缺失或撒谎均在读取中途被拒
        val out = ByteArrayOutputStream(
            if (declaredLength in 1..maxBytes) declaredLength.toInt() else DEFAULT_BUFFER_HINT
        )
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw SyncException.ProtocolError(
                        413,
                        "$label 响应体超出上限（> $maxBytes 字节），已拒绝接收"
                    )
                }
                out.write(buffer, 0, n)
            }
        } finally {
            input.close()
        }
        return out.toByteArray()
    }

    private const val BUFFER_SIZE = 64 * 1024
    private const val DEFAULT_BUFFER_HINT = 64 * 1024
}
