package com.keepasskey.app.data.importer

/**
 * 可擦除的字节累积器（1PUX 子批内部设施，`ISSUE-P3-581`）。
 *
 * ### 为何不用 `ByteArrayOutputStream`
 * `ByteArrayOutputStream` 的内部缓冲是私有字段、**对调用方完全不可达**，`toByteArray()` 只把内容
 * **复制**出去；于是「解压出的明文」在缓冲里驻留到 GC，调用方无从擦除。`export.data` 整份含明文口令，
 * 这种「不可达的明文副本」属 `AGENTS.md` §3 敏感数据铁律的合规缺口。
 *
 * ### 两个清零点（缺一不可）
 * 1. **容量增长**：新缓冲分配后旧缓冲先 `fill(0)` 再切引用（旧缓冲曾承载已写入明文）；
 * 2. **[wipe]**：由 `PuxArchiveReader.readBounded` 在 `finally` 中调用，成功返回与超限抛异常两路径一致。
 *
 * [copyOut] 返回**独立副本**（与内部缓冲无别名），其清零责任随之转移给调用方
 * （`OnePasswordPuxImporter.parseOrThrow` 的 `finally`）。
 */
internal class ErasableByteSink(initialCapacity: Int) {

    /**
     * 内部缓冲。**内部可见仅为测试观测**（`PuxIntermediateWipeTest` 断言「增长 / 擦除后全零」）；
     * 生产路径只经 [copyOut] 取值、绝不读取本引用，[wipe] 是唯一的清零入口。
     */
    internal var buffer: ByteArray = ByteArray(initialCapacity.coerceAtLeast(MIN_CAPACITY_BYTES))
        private set

    /** 已写入字节数。 */
    var size: Int = 0
        private set

    /** 追加 [length] 字节；容量不足时按 [CAPACITY_GROWTH] 倍数增长（旧缓冲被就地擦除）。 */
    fun write(source: ByteArray, offset: Int, length: Int) {
        ensureCapacity(size + length)
        System.arraycopy(source, offset, buffer, size, length)
        size += length
    }

    /** 取出**独立副本**（与内部缓冲无别名；调用方用毕须自行清零）。 */
    fun copyOut(): ByteArray = buffer.copyOf(size)

    /** 就地擦除内部缓冲并归零计数。 */
    fun wipe() {
        buffer.fill(0)
        size = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        val grown = buffer.copyOf(maxOf(required, buffer.size * CAPACITY_GROWTH))
        buffer.fill(0)
        buffer = grown
    }

    private companion object {
        /** 容量至少 1 字节（`ByteArray(0)` 无法承载任何写入）。 */
        const val MIN_CAPACITY_BYTES = 1

        /** 增长倍数。 */
        const val CAPACITY_GROWTH = 2
    }
}
