package com.keepasskey.sync.network

import com.keepasskey.sync.model.SyncException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

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
     *
     * ISSUE-P3-206 更正此前失实前提（「合法 `.kdbx` 远小于该值」）：
     * 附件密文随库体存在（KDBX 4 将附件收编进内层头；外层 XML 内联附件形态亦不经
     * 附件磁盘缓存）⇒ 数十~百 MiB 的**合法**库正落于该上限邻域。
     * 故本上限只承担「远端单方面灌大响应」的**封顶**语义（拒绝显然异常的体量），
     * **不**承担「合法库远小于此、可以整份缓冲」的假设——下载接受路径必须流式
     * （[copyBounded] 边读边写，接受路径不再有 ~2×S 的整份物化）。
     */
    const val MAX_DOWNLOAD_BYTES: Long = 128L * 1024 * 1024

    /**
     * PROPFIND 元数据响应上限：16 MiB。
     * `Depth: 0` 的 multistatus 报文通常仅数 KB，该上限已极宽裕，
     * 防「元数据通道被灌大」的整体物化（`body.string()`）。
     */
    const val MAX_PROPFIND_BYTES: Long = 16L * 1024 * 1024

    /**
     * 有界流式搬运（ISSUE-P3-206）：把 [input] 以固定缓冲**边读边写**进 [sink] 并累计封顶。
     *
     * 双重封顶语义与 [readBounded] 一致（AC「流式 + 声明尺寸双重封顶，超限即拒绝」），
     * 区别只在产出物：不构建堆内累积缓冲，而是直接写入调用方提供的 [sink]
     * （缓存 tmp 文件 / 摘要计数流等），下载期堆占用为常量（缓冲 64 KiB）。
     *
     * @param declaredLength 服务端声明的内容长度（`-1` 表示未声明 / chunked）
     * @param label 错误信息中的通道标识（如 "WebDAV" / "S3"）
     * @return 实际搬运的字节数（成功时即响应体总长）
     * @throws SyncException.ProtocolError 声明超限或累计越过 [maxBytes]（HTTP 413 语义）
     */
    fun copyBounded(
        input: InputStream,
        declaredLength: Long,
        maxBytes: Long = MAX_DOWNLOAD_BYTES,
        label: String,
        sink: OutputStream
    ): Long {
        // ① 声明尺寸预检：未读取任何字节即拒绝
        if (declaredLength > maxBytes) {
            throw SyncException.ProtocolError(
                413,
                "$label 响应体超出上限（声明 $declaredLength 字节 > 上限 $maxBytes 字节），已拒绝接收"
            )
        }
        // ② 流式累计封顶：声明缺失或撒谎均在读取中途被拒，堆占用恒为常量缓冲
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
                sink.write(buffer, 0, n)
            }
        } finally {
            input.close()
        }
        return total
    }

    /**
     * 有界读取 [input] 全部字节，超出 [maxBytes] 即抛 [SyncException.ProtocolError]（HTTP 413 语义）。
     *
     * ISSUE-P3-206 起本函数**仅保留给小体量的元数据通道**（PROPFIND multistatus，16 MiB 上限）
     * ——它会整体物化（累积缓冲 + `toByteArray()` 复制，峰值 ~2×S）；数据库下载体一律改走
     * [copyBounded] 流式契约，不再经本函数。
     *
     * @param declaredLength 服务端声明的内容长度（`-1` 表示未声明 / chunked）
     * @param label 错误信息中的通道标识（如 "PROPFIND"）
     */
    fun readBounded(
        input: InputStream,
        declaredLength: Long,
        maxBytes: Long = MAX_DOWNLOAD_BYTES,
        label: String
    ): ByteArray {
        val out = ByteArrayOutputStream(
            if (declaredLength in 1..maxBytes) declaredLength.toInt() else DEFAULT_BUFFER_HINT
        )
        copyBounded(input, declaredLength, maxBytes, label, out)
        return out.toByteArray()
    }

    private const val BUFFER_SIZE = 64 * 1024
    private const val DEFAULT_BUFFER_HINT = 64 * 1024
}
