package com.keepasskey.app.data.importer

import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 1PUX 子批的**原始输入读取层**（ISSUE-P3-19 交付物 3）：
 * 本文件只承载 [PuxArchiveReader]（ZIP 容器）；容器内 `export.data` 的 JSON 文本解析器
 * [ImportJson]（两个 JSON 系解析器 Bitwarden / 1PUX 共享）已拆至 `ImportJson.kt`
 * （行数闸门；拆分**零行为变更**，只做结构搬运）。
 */

/**
 * 1PUX（ZIP 容器）纯内存读取器。
 *
 * ### 职责与边界
 * 只做一件事：从 ZIP 字节流中取出**单个指定条目**的原始字节；全程不落盘，
 * 且读到目标条目即停止（`files/` 下的附件对被导入库无用，不读即不解压，
 * 兼得性能与「附件撑爆内存」的天然免疫）。
 *
 * ### 防御面（fail-closed）
 * 1. **Zip Slip**：条目名含 `..`、以 `/` 或 `\` 开头（绝对路径）、第二位为盘符冒号、
 *    含 NUL 或为空，一律判非法并**拒绝整包**（本类不写盘，此检查属纵深防御）；
 * 2. **Zip 炸弹**：容器自身体积 [Limits.maxArchiveBytes]、条目数 [Limits.maxEntries]、
 *    单条目体积 [Limits.maxEntryBytes]、解压总量 [Limits.maxTotalUncompressedBytes] 四道闸门，
 *    任一超限立即抛 [ImportLimitExceededException]（解压过程中即中止，不先把字节留在堆上）；
 * 3. 归档结构损坏由 [ZipInputStream] 抛 `IOException`，由调用方归一为 Failure
 *    （原始消息可能含条目名，绝不透传上浮）。
 *
 * [Limits] 默认值即生产上限；单测注入更小上限即可低成本覆盖各条闸门。
 */
internal object PuxArchiveReader {

    /** 四道体积 / 数量闸门；默认值为生产上限，单测可注入更小值覆盖超限分支。 */
    internal data class Limits(
        val maxArchiveBytes: Long = ImportLimits.MAX_IMPORT_BYTES.toLong(),
        val maxEntries: Int = MAX_ENTRIES,
        val maxEntryBytes: Long = MAX_ENTRY_BYTES,
        val maxTotalUncompressedBytes: Long = MAX_TOTAL_UNCOMPRESSED_BYTES
    )

    /**
     * 读取 [archive] 中名为 [entryName] 的条目字节。
     *
     * @throws ImportFormatException 归档为空、条目名非法、缺少 [entryName]
     * @throws ImportLimitExceededException 容器体积 / 条目数 / 单条目体积 / 解压总量超限
     */
    fun readEntry(archive: ByteArray, entryName: String, limits: Limits = Limits()): ByteArray {
        if (archive.isEmpty()) throw ImportFormatException("1PUX 归档为空")
        if (archive.size.toLong() > limits.maxArchiveBytes) {
            throw ImportLimitExceededException("1PUX 归档体积超出上限（${limits.maxArchiveBytes} 字节）")
        }
        // 解压分片读缓冲逐段承载明文（条目缓冲之外的另一份）：用毕在 finally 中擦除（ISSUE-P3-581）
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            ZipInputStream(archive.inputStream()).use { zip ->
                val budget = ByteBudget(limits.maxTotalUncompressedBytes)
                var entryCount = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > limits.maxEntries) {
                        throw ImportLimitExceededException("1PUX 归档条目数超出上限（${limits.maxEntries}）")
                    }
                    val name = entry.name ?: throw ImportFormatException("1PUX 归档存在无名条目")
                    if (!isSafeEntryName(name)) {
                        throw ImportFormatException("1PUX 归档存在非法条目名（疑似路径穿越），已拒绝整包")
                    }
                    if (entry.isDirectory) continue
                    if (name == entryName) {
                        val payload = readBounded(zip, entry, limits.maxEntryBytes, buffer)
                        budget.add(payload.size)
                        return payload
                    }
                    drainBounded(zip, budget, buffer)
                }
                throw ImportFormatException("1PUX 归档缺少 $entryName")
            }
        } finally {
            buffer.fill(0)
        }
    }

    /**
     * 条目名合法性（Zip Slip 纵深防御）。1PUX 的合法条目仅
     * `export.data` / `export.attributes` / `files/...`，均不含 `..`，故一律严格拒绝。
     */
    internal fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.contains(NUL_CHAR)) return false
        if (name.startsWith(PATH_FORWARD) || name.startsWith(PATH_BACKWARD)) return false
        if (name.contains(PARENT_SEGMENT)) return false
        return name.length <= DRIVE_LETTER_INDEX || name[DRIVE_LETTER_INDEX] != DRIVE_SEPARATOR
    }

    /**
     * 读取目标条目，累计超过 [maxBytes] 立即中止（不把超限字节继续留在堆上）；
     * 内部缓冲改由 [ErasableByteSink] 承载——成功返回与超限抛异常两路径都在 `finally` 中擦除，
     * 替代内部缓冲对调用方不可达的 `ByteArrayOutputStream`（ISSUE-P3-581）。
     */
    private fun readBounded(
        zip: ZipInputStream,
        entry: ZipEntry,
        maxBytes: Long,
        buffer: ByteArray
    ): ByteArray {
        val sink = ErasableByteSink(initialCapacity(entry.size, maxBytes))
        try {
            while (true) {
                val read = zip.read(buffer, 0, buffer.size)
                if (read < 0) return sink.copyOut()
                if (sink.size.toLong() + read > maxBytes) {
                    throw ImportLimitExceededException("1PUX 条目体积超出上限（$maxBytes 字节）")
                }
                sink.write(buffer, 0, read)
            }
        } finally {
            sink.wipe()
        }
    }

    /** 丢弃非目标条目的数据并计入解压总量预算（解压即计数，超限立刻中止）。 */
    private fun drainBounded(zip: ZipInputStream, budget: ByteBudget, buffer: ByteArray) {
        while (true) {
            val read = zip.read(buffer, 0, buffer.size)
            if (read < 0) return
            budget.add(read)
        }
    }

    /** 头声明大小可信时按其预分配；未知（-1）或超限时退化为小初值，交由闸门拦截。 */
    private fun initialCapacity(declaredSize: Long, maxBytes: Long): Int =
        if (declaredSize in 1..maxBytes) declaredSize.toInt() else DEFAULT_CAPACITY_BYTES

    /** 解压总量预算：每读一段即累加，越过上限立刻抛异常。 */
    private class ByteBudget(private val limit: Long) {
        private var used = 0L

        fun add(bytes: Int) {
            used += bytes
            if (used > limit) throw ImportLimitExceededException("1PUX 解压总量超出上限（$limit 字节）")
        }
    }

    /** 条目数上限：正常 1PUX 为 export.attributes + export.data + 附件若干。 */
    internal const val MAX_ENTRIES = 512

    /** 单条目体积上限：`export.data` 远小于该值。 */
    internal const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    /** 解压总量上限（Zip 炸弹闸门）。 */
    internal const val MAX_TOTAL_UNCOMPRESSED_BYTES = 64L * 1024 * 1024

    private const val READ_BUFFER_BYTES = 8 * 1024
    private const val DEFAULT_CAPACITY_BYTES = 64 * 1024
    private const val PARENT_SEGMENT = ".."
    private const val NUL_CHAR = '\u0000'
    private const val PATH_FORWARD = '/'
    private const val PATH_BACKWARD = '\\'
    private const val DRIVE_SEPARATOR = ':'

    /** `X:` 盘符冒号所在下标。 */
    private const val DRIVE_LETTER_INDEX = 1
}
