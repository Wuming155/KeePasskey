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

    /**
     * `ISSUE-P3-303`：文本写出的 **CharArray** 通道——供「归调用方所有、用毕即擦」的
     * 敏感文本（如条目口令）直接写出，**不再经 `readString()` 物化不可擦 `String`**。
     *
     * 与 [text] 逐字节等价（同一 `escape` 分支表与区间批量写出策略），仅数据载体不同。
     */
    fun text(value: CharArray) {
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

    /**
     * 按 XML 规则转义后写出（文本与属性共用）。
     *
     * ISSUE-P3-151：**按「无需转义的连续区间」批量写出**，不再逐字符调 `write(int)`。
     * 原实现每个字符一次 `BufferedWriter.write(int)`（每次都要进锁 + 逐字节入缓冲），
     * 保存大库时随 XML 字符数线性放大。
     *
     * 顺带修掉一处**真缺陷**（原实现的结构性后果）：`Writer.write(int)` 只写
     * **低 16 位**，故非 BMP 字符（如 emoji，U+1F600）会被写成其低位截断值
     * （`0xF600` 私用区字符）——写出即已损坏。批量路径按 UTF-16 区间原样搬运，
     * 代理对完整，往返无损。回归锁见 `KdbxXmlFullRoundtripTest` 的「非 BMP 字符往返」用例。
     */
    private fun escape(value: String, escapeNewLines: Boolean) {
        var index = 0
        // 当前「无需转义」区间起点；遇到需转义 / 需剔除的码点时先flush本区间
        var runStart = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            if (isLegalXmlCodePoint(codePoint) && !needsEscape(codePoint, escapeNewLines)) {
                index += charCount
                continue
            }
            if (index > runStart) {
                writer.write(value, runStart, index - runStart)
            }
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
                // 非法 XML 1.0 控制字符剔除，保证写出文档始终 well-formed
                else -> {}
            }
            index += charCount
            runStart = index
        }
        if (value.length > runStart) {
            writer.write(value, runStart, value.length - runStart)
        }
    }

    /**
     * `ISSUE-P3-303`：[escape] 的 **CharArray** 版，供敏感文本（口令等）不经 `String` 直接写出。
     *
     * **刻意逐字节复刻**（而非抽公共内核传 lambda）：本函数位于**保存热路径**上
     * （大库每个文本节点都会经过它，`ISSUE-P3-151` 正是为此把逐字符 `write(int)` 改成区间批量写出），
     * 抽公共内核需要引入 `codePointAt` / 写出区间两个 lambda ⇒ 每次调用各分配一次闭包，
     * 与 §151 的优化方向相反。两版必须**同步维护**：分支表、`needsEscape` 判据、
     * 「非法码点剔除」与 CR 转义口径的任何改动都要同时落到两处（回归锁见
     * `KdbxXmlFullRoundtripTest` 的往返用例与 `ExportWritePathHygieneTest`）。
     */
    private fun escape(value: CharArray, escapeNewLines: Boolean) {
        var index = 0
        var runStart = 0
        while (index < value.size) {
            val codePoint = Character.codePointAt(value, index)
            val charCount = Character.charCount(codePoint)
            if (isLegalXmlCodePoint(codePoint) && !needsEscape(codePoint, escapeNewLines)) {
                index += charCount
                continue
            }
            if (index > runStart) {
                writer.write(value, runStart, index - runStart)
            }
            when {
                codePoint == '&'.code -> writer.write("&amp;")
                codePoint == '<'.code -> writer.write("&lt;")
                codePoint == '>'.code -> writer.write("&gt;")
                codePoint == '"'.code && escapeNewLines -> writer.write("&quot;")
                codePoint == '\r'.code -> writer.write("&#xD;")
                codePoint == '\n'.code && escapeNewLines -> writer.write("&#10;")
                codePoint == '\t'.code && escapeNewLines -> writer.write("&#9;")
                else -> {}
            }
            index += charCount
            runStart = index
        }
        if (value.size > runStart) {
            writer.write(value, runStart, value.size - runStart)
        }
    }

    /** 该码点是否需要转义（与 [escape] 的分支一一对应；不含「非法码点剔除」）。 */
    private fun needsEscape(codePoint: Int, escapeNewLines: Boolean): Boolean = when {
        codePoint == '&'.code -> true
        codePoint == '<'.code -> true
        codePoint == '>'.code -> true
        codePoint == '"'.code -> escapeNewLines
        // CR 在两条路径上恒转义（见 escape 内的 P3-3 说明）
        codePoint == '\r'.code -> true
        codePoint == '\n'.code -> escapeNewLines
        codePoint == '\t'.code -> escapeNewLines
        else -> false
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
