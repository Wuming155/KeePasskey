package com.keepasskey.database.file

import java.io.FilterInputStream
import java.io.InputStream

/**
 * ISSUE-P3-368：KDBX 读写链路进度的分段取值（0..1 确定进度；null = 分段不确定段）。
 *
 * 分段口径（AC①「至少覆盖打开（解密→解压→XML）与保存两段」）：
 * - 打开链：[START]（起读）→ [HEADER_PARSED]（外层头 + SHA + HMAC 读毕）→ null（KDF 派生，
 *   单次同步 JNI、原子无回调，不可细分）→ [PAYLOAD_START] 起由 [ProgressCountingInputStream]
 *   按「已消费密文字节 / 文件总长」推进到 [DONE]，单一字节数进度覆盖解密→解压→SAX 全程；
 *   文件总长未知（SAF Uri 流）时载荷段整体退 null 分段不确定态，结束仍到 [DONE]；
 * - 保存链：[START] → [SAVE_PREPARED]（附件去重 / 新种子就绪）→ null（KDF 派生）→
 *   [SAVE_PAYLOAD_START]（外层头已写、载荷序列化开始——压缩在 buffer 内不知总长，只能分段）→
 *   [SAVE_SERIALIZED]（整库密文物化完成）→ 落盘后由会话层补 [DONE]。
 *
 * **AC③ 硬约束**：进度事件只承载 Float / null 数值，不持有流、字节或密钥引用，即发即弃、
 * 不缓存历史——进度层不新增任何敏感缓冲驻留点。
 */
internal object KdbxProgress {
    /** 链路起点（读头前 / 保存入口） */
    const val START = 0f

    /** 打开链：外层 Header + SHA-256 + 头部 HMAC 摘要读取完成 */
    const val HEADER_PARSED = 0.05f

    /** 打开链：载荷段起点（其后由字节进度展开到 [DONE]；总长未知时改发 null 不确定态） */
    const val PAYLOAD_START = 0.10f

    /** 保存链：附件去重与新种子就绪（此前为修剪/准备段） */
    const val SAVE_PREPARED = 0.05f

    /** 保存链：外层头已写、载荷序列化开始（本段占保存耗时大头，但压缩在 buffer 内不知总长） */
    const val SAVE_PAYLOAD_START = 0.30f

    /** 保存链：整库密文物化完成（等待落盘） */
    const val SAVE_SERIALIZED = 0.90f

    /** 终态（链路完成） */
    const val DONE = 1f
}

/**
 * ISSUE-P3-368：进度上报唯一出口（AC③ 防御边界）。
 *
 * - **回调异常一律吞掉**：进度回调不得打断打开/保存主流程，更不得越过 `KdbxFile.load` /
 *   `save` 的 `finally` 密钥清零路径——否则「进度层」会成为擦除纪律的破坏点；
 * - **确定进度恒单调不减**：回退值被钳到已发最大值；null（分段不确定段）不改变已发水位，
 *   恢复确定段时从水位继续；
 * - 每次打开/保存一实例（跨操作不共享），无回调时全程快速返回。
 */
internal class KdbxProgressReporter(private val onProgress: ((Float?) -> Unit)?) {

    /** 已发出的最大确定进度（单调水位） */
    private var maxEmitted = 0f

    fun emit(value: Float?) {
        val sink = onProgress ?: return
        val out = if (value == null) {
            null
        } else {
            value.coerceIn(0f, 1f).coerceAtLeast(maxEmitted).also { maxEmitted = it }
        }
        try {
            sink(out)
        } catch (t: Throwable) {
            // AC③：回调异常不上抛（进度是旁路观察，绝不允许影响解密/序列化与清零语义）
        }
    }
}

/**
 * ISSUE-P3-368：按「已消费字节 / 文件总字节数」推进载荷段进度的只读包装流。
 *
 * 覆盖原理：HMAC 块流 → 解密 → GZIP → SAX 全部逐层从**同一底层流**拉取，
 * 故底层消费比例即整条 解密→解压→XML 链路的统一度量（不物化、不回读，流式契约不变）。
 *
 * 上报门控：[startReporting] 之前只计数不上报（头部 / KDF 阶段由分段进度负责），
 * 开启后映射到 `[PAYLOAD_START, DONE]` 并按 1% 桶节流，避免逐 read() 高频写状态流。
 * 仅单线程顺序读取语义下使用（与 load 管线一致）。
 */
internal class ProgressCountingInputStream(
    source: InputStream,
    private val totalBytes: Long,
    private val reporter: KdbxProgressReporter
) : FilterInputStream(source) {

    private var consumed = 0L
    private var reporting = false
    private var lastBucket = -1

    /** 载荷段起点调用：此后读取按字节比例上报（此前只累计计数） */
    fun startReporting() {
        reporting = true
    }

    override fun read(): Int {
        val b = `in`.read()
        if (b >= 0) {
            consumed++
            report()
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = `in`.read(b, off, len)
        if (n > 0) {
            consumed += n
            report()
        }
        return n
    }

    private fun report() {
        if (!reporting) return
        val ratio = (consumed.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        val fraction = (KdbxProgress.PAYLOAD_START +
            (KdbxProgress.DONE - KdbxProgress.PAYLOAD_START) * ratio)
            .coerceAtMost(KdbxProgress.DONE)
        val bucket = (fraction * BUCKET_COUNT).toInt()
        if (bucket > lastBucket) {
            lastBucket = bucket
            reporter.emit(fraction)
        }
    }

    private companion object {
        /** 进度上报粒度：1%（KdbxProgressReporter 另有单调钳制兜底） */
        const val BUCKET_COUNT = 100
    }
}
