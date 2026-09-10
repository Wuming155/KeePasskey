package com.keepasskey.app.data.importer

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 1PUX 子批的**原始输入读取层**（ISSUE-P3-19 交付物 3）：
 * [PuxArchiveReader]（ZIP 容器）+ [ImportJson]（容器内 `export.data` 的 JSON 文本）。
 * 二者同为「把磁盘字节变成解析器可消费的内存结构」的设施，故合并于本文件，
 * 文件面保持最小；两个 JSON 系解析器（Bitwarden / 1PUX）共享 [ImportJson]。
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
        ZipInputStream(archive.inputStream()).use { zip ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
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

    /** 读取目标条目，累计超过 [maxBytes] 立即中止（不把超限字节继续留在堆上）。 */
    private fun readBounded(
        zip: ZipInputStream,
        entry: ZipEntry,
        maxBytes: Long,
        buffer: ByteArray
    ): ByteArray {
        val sink = ByteArrayOutputStream(initialCapacity(entry.size, maxBytes))
        while (true) {
            val read = zip.read(buffer, 0, buffer.size)
            if (read < 0) return sink.toByteArray()
            if (sink.size().toLong() + read > maxBytes) {
                throw ImportLimitExceededException("1PUX 条目体积超出上限（$maxBytes 字节）")
            }
            sink.write(buffer, 0, read)
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

/**
 * 极简只读 JSON 解析器（Bitwarden 与 1PUX 两个 JSON 系解析器共享）。
 *
 * ### 为何手写而不引依赖
 * `gradle/libs.versions.toml` 与 `app/build.gradle.kts` 中**均无** kotlinx-serialization / gson /
 * org.json 依赖声明；Android 自带 `org.json` 在 JVM 单测中是未实现 stub。为满足「不新增依赖 +
 * JVM 可测」，此处手写只读解析器；同时刻意不复用 passkey 包的 `MinimalJson`（跨包引用 internal
 * 辅助对象会破坏「包即边界」纪律），改以同包 internal 形式落地。
 *
 * ### 输入与职责边界
 * 本类只做**文本 → JSON 结构**的语法解析（空白含 CRLF、`\uXXXX` 与代理对转义）。
 * 字节 → 文本的解码（严格 UTF-8、BOM 剥离、拒绝 UTF-16/32 与 NUL 伪文本）由同批的
 * `ImportUtf8Decoder` 统一负责，两个解析器均先行解码再调用 [parse]。
 *
 * ### fail-closed
 * 任何语法非法一律抛 [ImportFormatException]，消息仅含字符偏移、绝不含输入内容；
 * 递归深度上限 64 防恶意深嵌套导致栈溢出（超限同样归一为上述异常）。
 */
internal object ImportJson {

    /** 解析整份文本；要求整体是一个合法 JSON 值（允许首尾空白）。 */
    fun parse(text: String): Any? = Parser(text).parseDocument()

    fun asObject(value: Any?): Map<*, *>? = value as? Map<*, *>

    fun asArray(value: Any?): List<*>? = value as? List<*>

    fun asString(value: Any?): String? = value as? String

    /** JSON 数值 → Long（`1` 与 `1.0` 均归一为 1；非数值返回 null）。 */
    fun asLong(value: Any?): Long? = (value as? Long) ?: (value as? Double)?.toLong()

    fun asBoolean(value: Any?): Boolean? = value as? Boolean

    /** 递归下降解析器；[pos] 为当前字符偏移。语法常量就近收敛于本类，不跨作用域取私有声明。 */
    private class Parser(private val text: String) {
        private var pos = 0

        fun parseDocument(): Any? {
            skipWhitespace()
            val value = readValue(0)
            skipWhitespace()
            if (pos < text.length) fail()
            return value
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isJsonWhitespace()) pos++
        }

        private fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH || pos >= text.length) fail()
            return when (val current = text[pos]) {
                OBJECT_OPEN -> readObject(depth)
                ARRAY_OPEN -> readArray(depth)
                STRING_QUOTE -> readString()
                't' -> readLiteral(LITERAL_TRUE, true)
                'f' -> readLiteral(LITERAL_FALSE, false)
                'n' -> readLiteral(LITERAL_NULL, null)
                else -> if (current == SIGN_MINUS || current.isDigit()) readNumber() else fail()
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++
            val members = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (consume(OBJECT_CLOSE)) return members
            while (true) {
                skipWhitespace()
                if (pos >= text.length || text[pos] != STRING_QUOTE) fail()
                val key = readString()
                skipWhitespace()
                if (!consume(NAME_SEPARATOR)) fail()
                skipWhitespace()
                members[key] = readValue(depth + 1)
                skipWhitespace()
                if (consume(OBJECT_CLOSE)) return members
                if (!consume(VALUE_SEPARATOR)) fail()
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++
            val elements = ArrayList<Any?>()
            skipWhitespace()
            if (consume(ARRAY_CLOSE)) return elements
            while (true) {
                skipWhitespace()
                elements += readValue(depth + 1)
                skipWhitespace()
                if (consume(ARRAY_CLOSE)) return elements
                if (!consume(VALUE_SEPARATOR)) fail()
            }
        }

        private fun readString(): String {
            pos++
            val builder = StringBuilder()
            while (true) {
                if (pos >= text.length) fail()
                when (val current = text[pos]) {
                    STRING_QUOTE -> {
                        pos++
                        return builder.toString()
                    }
                    ESCAPE -> {
                        pos++
                        builder.append(readEscape())
                    }
                    else -> {
                        // 规范禁止字符串内出现裸控制字符：严格拒绝，避免后续解析歧义
                        if (current < MIN_PRINTABLE_CHAR) fail()
                        builder.append(current)
                        pos++
                    }
                }
            }
        }

        /** 消费一个转义序列（调用时 `pos` 指向反斜杠之后的字符）。 */
        private fun readEscape(): Char {
            if (pos >= text.length) fail()
            val escaped = text[pos]
            pos++
            return when (escaped) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                ESCAPE_UNICODE -> readHexChar()
                else -> fail()
            }
        }

        /** `\uXXXX`：代理对由两次转义各产出一个 Char，拼接后即为完整码点。 */
        private fun readHexChar(): Char {
            if (pos + HEX_DIGITS > text.length) fail()
            var code = 0
            repeat(HEX_DIGITS) {
                val digit = Character.digit(text[pos], 16)
                if (digit < 0) fail()
                code = code * 16 + digit
                pos++
            }
            return code.toChar()
        }

        private fun readNumber(): Any {
            val start = pos
            if (pos < text.length && text[pos] == SIGN_MINUS) pos++
            readDigits()
            var fractional = false
            if (consume(FRACTION_SEPARATOR)) {
                fractional = true
                readDigits()
            }
            if (consume(EXPONENT_LOWER) || consume(EXPONENT_UPPER)) {
                fractional = true
                if (!consume(SIGN_PLUS)) consume(SIGN_MINUS)
                readDigits()
            }
            val token = text.substring(start, pos)
            return if (fractional) token.toDoubleOrNull() ?: fail() else token.toLongOrNull() ?: fail()
        }

        /** 至少消费一位数字（整数 / 小数 / 指数三处共用）。 */
        private fun readDigits() {
            val start = pos
            while (pos < text.length && text[pos].isDigit()) pos++
            if (pos == start) fail()
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail()
            pos += word.length
            return value
        }

        private fun consume(expected: Char): Boolean {
            if (pos < text.length && text[pos] == expected) {
                pos++
                return true
            }
            return false
        }

        /** 解析失败：消息只带偏移，绝不携带输入片段（防明文经异常消息外泄）。 */
        private fun fail(): Nothing = throw ImportFormatException("JSON 语法非法（偏移 $pos）")

        private fun Char.isJsonWhitespace(): Boolean =
            this == ' ' || this == '\t' || this == '\n' || this == '\r'

        private companion object {
            const val OBJECT_OPEN = '{'
            const val OBJECT_CLOSE = '}'
            const val ARRAY_OPEN = '['
            const val ARRAY_CLOSE = ']'
            const val STRING_QUOTE = '"'
            const val ESCAPE = '\\'
            const val NAME_SEPARATOR = ':'
            const val VALUE_SEPARATOR = ','
            const val FRACTION_SEPARATOR = '.'
            const val EXPONENT_LOWER = 'e'
            const val EXPONENT_UPPER = 'E'
            const val SIGN_PLUS = '+'
            const val SIGN_MINUS = '-'
            const val ESCAPE_UNICODE = 'u'
            const val MIN_PRINTABLE_CHAR = ' '

            const val LITERAL_TRUE = "true"
            const val LITERAL_FALSE = "false"
            const val LITERAL_NULL = "null"

            /** 单个 `\uXXXX` 转义的十六进制位数。 */
            const val HEX_DIGITS = 4

            /** 嵌套深度上限：远超此深度必为异常输入，直接拒绝而非冒险递归。 */
            const val MAX_DEPTH = 64
        }
    }
}
