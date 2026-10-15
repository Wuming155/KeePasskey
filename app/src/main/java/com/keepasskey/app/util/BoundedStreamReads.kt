package com.keepasskey.app.util

import java.io.IOException
import java.io.InputStream

/**
 * 带硬字节上限的分块读取超限信号（`ISSUE-P3-617`）。
 *
 * 语义为 fail-closed 的 IO 失败：调用方须按「输入过大」处理（拒绝 + 提示），
 * 不得回落为无上限重读。消息仅含上限数值，不含任何流内容。
 */
class StreamLimitExceededException(message: String) : IOException(message)

/**
 * 带硬字节上限的分块读取（`ISSUE-P3-617`）。
 *
 * `InputStream.readBytes()` 的整体物化先把**全部**字节读进堆才返回——未知或错误申报长度
 * 可绕过读取前检查，超大输入在解析器 / 导入限制生效前即分配完整 `ByteArray`（OOM/ANR）。
 * 本函数按块累积，累计字节一旦越过 [maxBytes] 立即抛 [StreamLimitExceededException] 中止，
 * 缓冲总量恒被上限约束：
 * - 读前申报长度检查仍由调用方保留（已知超大根本不建流，最快拒绝）；
 * - 未知长度（描述符 null / `UNKNOWN_LENGTH`）与撒谎长度（申报小于实际）一律由本函数的
 *   累计上限兜底——这正是旧「整流后检查实际长度」形态的缺口。
 *
 * 敏感数据：累积缓冲在超限抛异常与成功返回两条路径上都被擦除后丢弃——成功时返回精确长度的
 * 独立副本（内部倍增缓冲就地清零），超限时内部缓冲就地清零后才抛（不把超限字节留在堆上）。
 * 返回副本的清零责任随之转移给调用方（附件 / 图标字节的后续归属见各调用点）。
 *
 * 与既有同形实现的关系：`sync` 的 `SyncDownloadLimits.readBounded`（远端下载体 128 MiB）与
 * 导入器的 `VaultImportController.readCapped`（导入文件上限）是同语义的通道专用实现；
 * 本函数是应用内各通道（漂移合并源文件、附件 / 图标 SAF 流）共用的收口，新通道一律走本函数，
 * 不再各写一份分块循环（`ISSUE-P3-617` 建议口径）。
 *
 * @param maxBytes 累计字节上限（闭区间，恰等于上限可通过）；须在 `0..Int.MAX_VALUE - 1` 内
 *   （`ByteArray` 索引上限；本函数服务的通道上限均为 MiB 量级，远低于此）
 * @return 恰好全流长度的独立副本（空流返回空数组）
 * @throws StreamLimitExceededException 累计字节越过 [maxBytes]（已累积缓冲已清零）
 */
internal fun InputStream.readBoundedBytes(maxBytes: Long): ByteArray {
    require(maxBytes in 0..(Int.MAX_VALUE - 1)) {
        "readBoundedBytes 上限须在 0..${Int.MAX_VALUE - 1} 闭区间内"
    }
    val limit = maxBytes.toInt()
    var buffer = ByteArray(minOf(READ_CHUNK_BYTES, limit + 1).coerceAtLeast(1))
    var total = 0
    try {
        while (true) {
            if (total == buffer.size) {
                // 缓冲已满：按倍增扩容（`ErasableByteSink` 同策略，均摊线性），但恒以 limit + 1 为顶——
                // 无配额再读 1 字节时直接判超限，不分配。
                val grownSize = minOf(limit + 1L, maxOf(total + READ_CHUNK_BYTES.toLong(), total.toLong() * 2))
                if (grownSize <= total) {
                    throw StreamLimitExceededException(
                        "流超出字节上限（>$limit 字节），已拒绝接收"
                    )
                }
                val grown = ByteArray(grownSize.toInt())
                buffer.copyInto(grown, 0, 0, total)
                buffer.fill(0)
                buffer = grown
            }
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
            if (total > limit) {
                throw StreamLimitExceededException(
                    "流超出字节上限（>$limit 字节），已拒绝接收"
                )
            }
        }
    } catch (t: Throwable) {
        buffer.fill(0)
        throw t
    }
    val result = buffer.copyOf(total)
    buffer.fill(0)
    return result
}

/** 分块读取粒度（与 `sync` / 导入器同形实现的 64 KiB 缓冲对齐）。 */
private const val READ_CHUNK_BYTES = 64 * 1024
