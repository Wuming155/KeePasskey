package com.keepasskey.app.data.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把原值 CSV 转为最小 OOXML 工作簿。工作表只写 inline string 单元格（`t="inlineStr"`），
 * 不创建公式、超链接、外部关系、共享字符串或宏。CSV 单元格内容逐字符复制，只解开 RFC 4180
 * 的双引号转义；因此密码和其他字段不会被加前缀或做公式清洗。
 *
 * 本实现直接处理 UTF-8 字节，避免把整库明文再汇总为不可擦除的 Kotlin `String`。调用方仍负责
 * 清零传入的 CSV 字节及返回的 XLSX 字节。
 */
internal object SpreadsheetTextWorkbookExporter {

    private const val MAX_ROWS = 1_048_576
    private const val MAX_COLUMNS = 16_384
    private const val MAX_CELL_UTF16_UNITS = 32_767
    private const val MAX_CELL_LINE_FEEDS = 253

    private const val QUOTE = 0x22
    private const val COMMA = 0x2C
    private const val CR = 0x0D
    private const val LF = 0x0A

    private val contentTypes = xml(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "</Types>"
    )

    private val rootRelationships = xml(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"
    )

    private val workbook = xml(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"Entries\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
    )

    private val workbookRelationships = xml(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "</Relationships>"
    )

    private val sheetStart = xml(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
    )
    private val sheetEnd = xml("</sheetData></worksheet>")
    private val rowStart = xml("<row r=\"")
    private val cellStart = xml("<c r=\"")
    private val inlineTextStart = xml("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
    private val cellEnd = xml("</t></is></c>")
    private val rowEnd = xml("</row>")
    private val quoteByte = byteArrayOf(QUOTE.toByte())
    private val ampersand = xml("&amp;")
    private val lessThan = xml("&lt;")
    private val greaterThan = xml("&gt;")
    private val tabReference = xml("&#9;")
    private val carriageReturnReference = xml("&#13;")

    /** 输出一个 XLSX 字节副本；异常时不返回部分工作簿，并擦除内部压缩缓冲区。 */
    fun exportCsv(csvBytes: ByteArray): ByteArray {
        require(csvBytes.isNotEmpty()) { "CSV is empty" }
        validateUtf8AndXmlCharacters(csvBytes)
        val output = WipableByteArrayOutputStream()
        try {
            ZipOutputStream(output).use { zip ->
                zip.putStaticEntry("[Content_Types].xml", contentTypes)
                zip.putStaticEntry("_rels/.rels", rootRelationships)
                zip.putStaticEntry("xl/workbook.xml", workbook)
                zip.putStaticEntry("xl/_rels/workbook.xml.rels", workbookRelationships)
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                writeSheet(zip, csvBytes)
                zip.closeEntry()
            }
            return output.toByteArray()
        } finally {
            output.wipe()
        }
    }

    private fun writeSheet(output: OutputStream, csv: ByteArray) {
        output.write(sheetStart)
        var index = 0
        var row = 1
        while (index < csv.size) {
            require(row <= MAX_ROWS) { "CSV row count exceeds the spreadsheet limit" }
            output.write(rowStart)
            output.write(row.toString().toByteArray(Charsets.US_ASCII))
            output.write(xml("\">"))

            var column = 1
            while (true) {
                require(column <= MAX_COLUMNS) { "CSV column count exceeds the spreadsheet limit" }
                output.write(cellStart)
                output.write(columnName(column).toByteArray(Charsets.US_ASCII))
                output.write(row.toString().toByteArray(Charsets.US_ASCII))
                output.write(inlineTextStart)

                val cell = writeCellValue(output, csv, index)
                output.write(cellEnd)
                index = cell.nextIndex
                when (cell.delimiter) {
                    CellDelimiter.NEXT_CELL -> column++
                    CellDelimiter.NEXT_ROW -> break
                    CellDelimiter.END -> {
                        output.write(rowEnd)
                        output.write(sheetEnd)
                        return
                    }
                }
            }

            output.write(rowEnd)
            row++
        }
        output.write(sheetEnd)
    }

    /** 解析一个 RFC 4180 单元格并直接写出其文本内容，不建立单元格字符串副本。 */
    private fun writeCellValue(output: OutputStream, csv: ByteArray, startIndex: Int): ParsedCell {
        val metrics = CellMetrics()
        var index = startIndex

        if (index < csv.size && unsigned(csv[index]) == QUOTE) {
            index++
            var segmentStart = index
            while (index < csv.size) {
                if (unsigned(csv[index]) != QUOTE) {
                    index++
                    continue
                }

                if (index + 1 < csv.size && unsigned(csv[index + 1]) == QUOTE) {
                    writeEscapedText(output, csv, segmentStart, index, metrics)
                    output.write(quoteByte)
                    metrics.addCodePoint(QUOTE)
                    index += 2
                    segmentStart = index
                    continue
                }

                writeEscapedText(output, csv, segmentStart, index, metrics)
                index++
                val delimiter = delimiterAfterCell(csv, index)
                return ParsedCell(delimiter.nextIndex, delimiter.kind)
            }
            throw IllegalArgumentException("CSV contains an unterminated quoted field")
        }

        val segmentStart = index
        while (index < csv.size && !isDelimiter(unsigned(csv[index]))) {
            require(unsigned(csv[index]) != QUOTE) { "CSV contains a quote in an unquoted field" }
            index++
        }
        writeEscapedText(output, csv, segmentStart, index, metrics)
        val delimiter = delimiterAfterCell(csv, index)
        return ParsedCell(delimiter.nextIndex, delimiter.kind)
    }

    private fun writeEscapedText(
        output: OutputStream,
        csv: ByteArray,
        start: Int,
        end: Int,
        metrics: CellMetrics
    ) {
        var index = start
        var segmentStart = start
        while (index < end) {
            val byte = unsigned(csv[index])
            val replacement = when (byte) {
                '&'.code -> ampersand
                '<'.code -> lessThan
                '>'.code -> greaterThan
                '\t'.code -> tabReference
                CR -> carriageReturnReference
                else -> null
            }
            if (replacement != null) {
                if (segmentStart < index) output.write(csv, segmentStart, index - segmentStart)
                output.write(replacement)
                if (byte <= 0x7F) metrics.addCodePoint(byte)
                index++
                segmentStart = index
                continue
            }

            if (byte <= 0x7F) {
                metrics.addCodePoint(byte)
                if (byte == LF) metrics.addLineFeed()
                index++
                continue
            }

            val width = when {
                (byte and 0xE0) == 0xC0 -> 2
                (byte and 0xF0) == 0xE0 -> 3
                else -> 4
            }
            metrics.addCodePoint(if (width == 4) 0x10000 else 0x0800)
            index += width
        }
        if (segmentStart < end) output.write(csv, segmentStart, end - segmentStart)
        metrics.checkLimits()
    }

    private fun delimiterAfterCell(csv: ByteArray, index: Int): DelimiterResult {
        if (index == csv.size) return DelimiterResult(index, CellDelimiter.END)
        return when (unsigned(csv[index])) {
            COMMA -> DelimiterResult(index + 1, CellDelimiter.NEXT_CELL)
            LF -> DelimiterResult(index + 1, CellDelimiter.NEXT_ROW)
            CR -> DelimiterResult(
                if (index + 1 < csv.size && unsigned(csv[index + 1]) == LF) index + 2 else index + 1,
                CellDelimiter.NEXT_ROW
            )
            else -> throw IllegalArgumentException("CSV contains data after a closing quote")
        }
    }

    private fun validateUtf8AndXmlCharacters(bytes: ByteArray) {
        var index = 0
        while (index < bytes.size) {
            val first = unsigned(bytes[index])
            val codePoint: Int
            val width: Int
            when {
                first <= 0x7F -> {
                    codePoint = first
                    width = 1
                }
                first in 0xC2..0xDF -> {
                    width = 2
                    codePoint = ((first and 0x1F) shl 6) or continuation(bytes, index + 1)
                }
                first in 0xE0..0xEF -> {
                    width = 3
                    val second = unsignedAt(bytes, index + 1)
                    require(second in 0x80..0xBF) { "CSV is not valid UTF-8" }
                    require(first != 0xE0 || second >= 0xA0) { "CSV is not valid UTF-8" }
                    require(first != 0xED || second <= 0x9F) { "CSV is not valid UTF-8" }
                    codePoint = ((first and 0x0F) shl 12) or
                        ((second and 0x3F) shl 6) or continuation(bytes, index + 2)
                }
                first in 0xF0..0xF4 -> {
                    width = 4
                    val second = unsignedAt(bytes, index + 1)
                    require(second in 0x80..0xBF) { "CSV is not valid UTF-8" }
                    require(first != 0xF0 || second >= 0x90) { "CSV is not valid UTF-8" }
                    require(first != 0xF4 || second <= 0x8F) { "CSV is not valid UTF-8" }
                    codePoint = ((first and 0x07) shl 18) or ((second and 0x3F) shl 12) or
                        ((continuation(bytes, index + 2)) shl 6) or continuation(bytes, index + 3)
                }
                else -> throw IllegalArgumentException("CSV is not valid UTF-8")
            }
            require(isXml10Character(codePoint)) { "CSV contains a character unsupported by XML" }
            index += width
        }
    }

    private fun continuation(bytes: ByteArray, index: Int): Int {
        val value = unsignedAt(bytes, index)
        require(value in 0x80..0xBF) { "CSV is not valid UTF-8" }
        return value and 0x3F
    }

    private fun unsignedAt(bytes: ByteArray, index: Int): Int {
        require(index < bytes.size) { "CSV ends inside a UTF-8 sequence" }
        return unsigned(bytes[index])
    }

    private fun isXml10Character(codePoint: Int): Boolean =
        codePoint == 0x09 || codePoint == 0x0A || codePoint == 0x0D ||
            codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint in 0x10000..0x10FFFF

    private fun columnName(column: Int): String {
        var remaining = column
        val result = StringBuilder()
        while (remaining > 0) {
            val digit = (remaining - 1) % 26
            result.append(('A'.code + digit).toChar())
            remaining = (remaining - 1) / 26
        }
        return result.reverse().toString()
    }

    private fun isDelimiter(byte: Int): Boolean = byte == COMMA || byte == CR || byte == LF

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

    private fun xml(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    private fun ZipOutputStream.putStaticEntry(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    private data class ParsedCell(val nextIndex: Int, val delimiter: CellDelimiter)

    private data class DelimiterResult(val nextIndex: Int, val kind: CellDelimiter)

    private enum class CellDelimiter {
        NEXT_CELL,
        NEXT_ROW,
        END
    }

    private class CellMetrics {
        private var utf16Units = 0
        private var lineFeeds = 0

        fun addCodePoint(codePoint: Int) {
            utf16Units += if (codePoint > 0xFFFF) 2 else 1
            checkLimits()
        }

        fun addLineFeed() {
            lineFeeds++
            checkLimits()
        }

        fun checkLimits() {
            require(utf16Units <= MAX_CELL_UTF16_UNITS) { "CSV cell exceeds the spreadsheet text limit" }
            require(lineFeeds <= MAX_CELL_LINE_FEEDS) { "CSV cell exceeds the spreadsheet line-feed limit" }
        }
    }

    private class WipableByteArrayOutputStream : ByteArrayOutputStream() {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }
}
