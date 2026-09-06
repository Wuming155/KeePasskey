package com.keepasskey.database.xml

import com.keepasskey.database.exception.KdbxCorruptFileException
import org.xml.sax.Attributes

/**
 * KDBX XML 流式（SAX）解析节点基类。
 *
 * 官方标准参考（KeePass 2.61.1 `KdbxFile.Read.Streamed.cs` 流式状态机 / KeePassDX `readDocumentStreamed`）：
 * 解析器边读边构建对象树，不在内存中物化整棵 DOM。每个遇到的结构化元素对应一个节点实例入栈；
 * 简单文本子元素由 [TextNode] 收集并在闭合时回调父节点的赋值逻辑。
 */
internal abstract class SaxNode {

    /**
     * 子元素开始时调用。返回新入栈的子节点；未覆写时返回 [IgnoredNode] 忽略未知子树（与原 DOM 解析语义一致）。
     */
    open fun startChild(name: String, attrs: Attributes): SaxNode = IgnoredNode()

    /**
     * 收到字符数据时调用（SAX 允许将一段文本拆成多次事件）。
     */
    open fun text(ch: CharArray, start: Int, length: Int) {}

    /**
     * 本元素闭合时调用（此时全部子节点已闭合出栈）。
     */
    open fun end() {}
}

/**
 * 纯文本叶节点：收集自身全部字符数据并在闭合时回调。
 *
 * Wave 12 解析炸弹防线：单节点字符数封顶（[MAX_TEXT_CHARS]）——
 * XML 文本层是此前唯一无长度限制的解析层，恶意文件可借单一字段
 * （如超大 Base64 受保护值/图标数据）耗尽内存。
 */
internal class TextNode(
    // maxChars 置于首位：使既有「尾随 lambda」调用点（TextNode { ... }）继续绑定 onText
    private val maxChars: Int = MAX_TEXT_CHARS,
    private val onText: (String) -> Unit
) : SaxNode() {

    private val buffer = StringBuilder()

    override fun text(ch: CharArray, start: Int, length: Int) {
        if (buffer.length + length > maxChars) {
            throw KdbxCorruptFileException("XML 文本节点超出长度上限（$maxChars 字符），疑似解析炸弹")
        }
        buffer.append(ch, start, length)
    }

    override fun end() {
        onText(buffer.toString())
    }

    companion object {
        /**
         * 单文本节点字符上限：8 Mi 字符（约 16 MiB JVM 内存）。
         * 覆盖 Base64 受保护值、自定义图标数据等最大合法负载，正常库远低于该界。
         */
        const val MAX_TEXT_CHARS = 8 shl 20
    }
}

/**
 * 未知/忽略元素占位节点：吞掉整个子树（含嵌套结构）。
 */
internal class IgnoredNode : SaxNode() {

    override fun startChild(name: String, attrs: Attributes): SaxNode = IgnoredNode()
}

/**
 * 延迟解析引用：KDBX XML 中子元素的父 UUID 依赖文档顺序（UUID 先于子节点出现），
 * 结构化节点在闭合时才从持有器取值，与原 DOM 解析的取值时机等价。
 */
internal class LateRef<T> {
    var value: T? = null
}
