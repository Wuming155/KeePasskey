package com.keepasskey.app.data.importer

/**
 * RFC 4180 兼容的 CSV 记录读取器（浏览器密码导出专用）。
 *
 * 如实处理的边界（`ISSUE-P3-19` 交付物 3 要求）：
 * - 双引号包裹字段（含字段内逗号、字段内换行、`""` 转义引号）；
 * - `CRLF` / `LF` / 裸 `CR` 混用（引号内的换行原样保留，不做归一，保持值忠实）；
 * - 引号出现在非引号字段中时按字面量处理（宽容，不崩溃）；
 * - UTF-8 BOM 由 [ImportTextDecoder] 在解码阶段剥离；
 * - 单字段长度封顶 [ImportLimits.MAX_FIELD_CHARS]（畸形未闭合引号即 fail-closed）。
 *
 * **所有权与敏感数据**：调用方经 [forEachRow] 收到的每个字段都是**独占的 `CharArray`**，
 * 由调用方负责清零（密码字段可径直转交 [ImportedEntry]，全程不产生 `String`）；
 * 读取器自身的工作缓冲在交出副本后立即清零。
 */
internal class CsvRecordReader(
    private val text: String,
    private val maxFieldChars: Int = ImportLimits.MAX_FIELD_CHARS
) {

    private var state = CsvState.FIELD_START
    private var fieldBuffer = CharArray(INITIAL_CAPACITY)
    private var fieldLength = 0
    private val currentRecord = mutableListOf<CharArray>()
    private var rowPending = false

    /**
     * 顺序回调每条记录（含表头）。[consumer] 收到的字段数组归其所有，
     * 必须在返回前清零或转交（转交即由 [ImportedEntry] 的擦除契约接管）。
     */
    fun forEachRow(consumer: (recordIndex: Int, fields: List<CharArray>) -> Unit) {
        var recordIndex = 0
        var index = 0
        while (index < text.length) {
            index += handleChar(text[index], index)
            if (rowPending) {
                rowPending = false
                state = CsvState.FIELD_START
                consumer(recordIndex, takeRecord())
                recordIndex++
            }
        }
        if (fieldLength > 0 || currentRecord.isNotEmpty()) {
            endField()
            consumer(recordIndex, takeRecord())
        }
    }

    private fun handleChar(c: Char, index: Int): Int = when (state) {
        CsvState.FIELD_START -> handleFieldStart(c, index)
        CsvState.FIELD -> handleField(c, index)
        CsvState.QUOTED -> handleQuoted(c)
        CsvState.QUOTE_TAIL -> handleQuoteTail(c, index)
    }

    private fun handleFieldStart(c: Char, index: Int): Int = when (c) {
        QUOTE -> { state = CsvState.QUOTED; 1 }
        DELIMITER -> { endField(); 1 }
        CARRIAGE_RETURN, LINE_FEED -> { endRow(); lineBreakLength(c, index) }
        else -> { append(c); state = CsvState.FIELD; 1 }
    }

    private fun handleField(c: Char, index: Int): Int = when (c) {
        DELIMITER -> { endField(); state = CsvState.FIELD_START; 1 }
        CARRIAGE_RETURN, LINE_FEED -> { endRow(); lineBreakLength(c, index) }
        else -> { append(c); 1 }
    }

    private fun handleQuoted(c: Char): Int {
        return if (c == QUOTE) {
            state = CsvState.QUOTE_TAIL
            1
        } else {
            // 引号内的逗号 / CR / LF 全部如实保留
            append(c)
            1
        }
    }

    private fun handleQuoteTail(c: Char, index: Int): Int = when (c) {
        QUOTE -> { append(QUOTE); state = CsvState.QUOTED; 1 }
        DELIMITER -> { endField(); state = CsvState.FIELD_START; 1 }
        CARRIAGE_RETURN, LINE_FEED -> { endRow(); lineBreakLength(c, index) }
        else -> { append(c); state = CsvState.FIELD; 1 }
    }

    /** `\r\n` 计为一次换行（返回 2），裸 `\r` 或 `\n` 返回 1。 */
    private fun lineBreakLength(c: Char, index: Int): Int =
        if (c == CARRIAGE_RETURN && index + 1 < text.length && text[index + 1] == LINE_FEED) {
            PAIR_LENGTH
        } else {
            1
        }

    private fun endRow() {
        endField()
        rowPending = true
    }

    private fun endField() {
        val field = fieldBuffer.copyOf(fieldLength)
        if (fieldLength > 0) fieldBuffer.fill(NUL_CHAR, 0, fieldLength)
        fieldLength = 0
        currentRecord += field
    }

    private fun takeRecord(): List<CharArray> {
        val record = currentRecord.toList()
        currentRecord.clear()
        return record
    }

    private fun append(c: Char) {
        if (fieldLength >= maxFieldChars) throw ImportLimitExceededException(OVER_FIELD_LIMIT)
        ensureCapacity(fieldLength + 1)
        fieldBuffer[fieldLength] = c
        fieldLength++
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= fieldBuffer.size) return
        var newSize = fieldBuffer.size
        while (newSize < needed) newSize = newSize shl 1
        val grown = CharArray(newSize)
        fieldBuffer.copyInto(grown, destinationOffset = 0, startIndex = 0, endIndex = fieldLength)
        fieldBuffer.fill(NUL_CHAR)
        fieldBuffer = grown
    }

    private enum class CsvState { FIELD_START, FIELD, QUOTED, QUOTE_TAIL }

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val PAIR_LENGTH = 2
        const val QUOTE = '"'
        const val DELIMITER = ','
        const val CARRIAGE_RETURN = '\r'
        const val LINE_FEED = '\n'
        const val NUL_CHAR = '\u0000'
        const val OVER_FIELD_LIMIT = "CSV 单个字段超出导入长度上限"
    }
}
