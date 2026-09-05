package com.keepasskey.database.xml

import java.io.BufferedWriter
import java.io.Closeable
import java.io.Flushable
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * KDBX XML 流式写出器（对应官方 `WriteDocument` 的 XmlWriter 流式写出）。
 *
 * - 紧凑输出（无缩进空白）：文本节点的字符内容与模型值严格一致，压缩前体积也更小；
 * - 文本与属性分别按 XML 规则转义，非法 XML 1.0 控制字符被剔除；
 * - 回车（\r）一律转义为字符引用 &#xD;：XML 1.0 §2.11 要求解析器将字面 CR / CRLF
 *   规范化为 LF（字符引用不受影响），按字面写出会使 CRLF 多行文本往返后丢失 CR
 *   （P3-3 整改，保证 Windows 风格换行往返一致）；
 * - [close] 仅冲刷自身缓冲，不关闭底层流——外层管线（GZip/加密/HMAC 块流）的级联关闭由 [com.keepasskey.database.file.KdbxFile] 统一控制。
 */
class KdbxXmlStreamWriter(
    output: OutputStream
) : Closeable, Flushable {

    private val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), BUFFER_SIZE)
    private val elementStack = ArrayDeque<String>()
    private var insideStartTag = false

    fun startDocument() {
        writer.write(XML_DECLARATION)
    }

    fun startElement(name: String) {
        finishStartTag()
        writer.write('<'.code)
        writer.write(name)
        insideStartTag = true
        elementStack.addLast(name)
    }

    fun attribute(name: String, value: String) {
        check(insideStartTag) { "attribute() 必须紧跟 startElement() 调用" }
        writer.write(' '.code)
        writer.write(name)
        writer.write("=\"")
        escape(value, escapeNewLines = true)
        writer.write('"'.code)
    }

    fun text(value: String) {
        finishStartTag()
        escape(value, escapeNewLines = false)
    }

    fun endElement() {
        val name = elementStack.removeLast()
        if (insideStartTag) {
            writer.write("/>")
            insideStartTag = false
        } else {
            writer.write("</")
            writer.write(name)
            writer.write('>'.code)
        }
    }

    override fun flush() {
        finishStartTag()
        writer.flush()
    }

    override fun close() {
        finishStartTag()
        writer.flush()
    }

    private fun finishStartTag() {
        if (insideStartTag) {
            writer.write('>'.code)
            insideStartTag = false
        }
    }

    private fun escape(value: String, escapeNewLines: Boolean) {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            when {
                codePoint == '&'.code -> writer.write("&amp;")
                codePoint == '<'.code -> writer.write("&lt;")
                codePoint == '>'.code -> writer.write("&gt;")
                codePoint == '"'.code && escapeNewLines -> writer.write("&quot;")
                // P3-3：CR 一律转义为字符引用（文本与属性两路径一致）。
                // 字面 CR / CRLF 会被任何符合 XML 1.0 的解析器规范化为 LF，而
                // 字符引用 &#xD; 不参与行尾规范化，可保证 CRLF 精确往返。
                codePoint == '\r'.code -> writer.write("&#xD;")
                codePoint == '\n'.code && escapeNewLines -> writer.write("&#10;")
                codePoint == '\t'.code && escapeNewLines -> writer.write("&#9;")
                isLegalXmlCodePoint(codePoint) -> writer.write(codePoint)
                // 非法 XML 1.0 控制字符剔除，保证写出文档始终 well-formed
                else -> {}
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun isLegalXmlCodePoint(codePoint: Int): Boolean {
        return codePoint == '\t'.code || codePoint == '\n'.code || codePoint == '\r'.code ||
                (codePoint >= 0x20 && codePoint <= 0xD7FF) ||
                (codePoint >= 0xE000 && codePoint <= 0xFFFD) ||
                (codePoint >= 0x10000 && codePoint <= 0x10FFFF)
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    }
}
