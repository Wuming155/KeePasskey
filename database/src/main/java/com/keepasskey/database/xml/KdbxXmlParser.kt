package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.file.InnerHeader
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * KDBX XML 反序列化总解析器（流式 SAX，非 DOM）。
 *
 * 官方标准参考（KeePass 2.61.1 `ReadXmlStreamed` 流式状态机 / KeePassDX `readDocumentStreamed`）：
 * 边读边构建对象树，解密后的 XML 不再整体物化为 DOM，大库加载峰值内存显著降低。
 * 委派 [MetaNode]、[GroupNode] 等流式节点处理具体子树。
 */
class KdbxXmlParser(
    private val innerStreamCipher: InnerRandomStreamCipher?
) {

    data class ParseResult(
        val meta: KdbxMetaData,
        val rootGroup: KdbxGroup
    ) {
        val databaseName: String get() = meta.databaseName
        val databaseDescription: String get() = meta.databaseDescription
    }

    fun parse(
        inputStream: InputStream,
        binaries: List<InnerHeader.BinaryItem> = emptyList()
    ): ParseResult {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        // 强化 XML 解析防御：禁用 DTD 与外部实体（XXE 防御）
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://xml.org/sax/features/resolve-dtd-uris", false)
        } catch (_: Exception) {
            // 环境不支持部分特性时宽容继续（resolveEntity 兜底拦截外部实体）
        }

        var metaData = KdbxMetaData()
        var rootGroup: KdbxGroup? = null
        val nodeStack = ArrayDeque<SaxNode>()

        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
                // 拒绝一切外部实体解析（防御纵深）
                throw KdbxCorruptFileException("KDBX XML 不允许包含外部实体引用: $systemId")
            }

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                // Wave 12 解析炸弹防线：XML 嵌套深度封顶（合法库远低于该界；深度受限同时
                // 约束分组树嵌套与 IgnoredNode 未知子树的栈消耗）
                if (nodeStack.size >= MAX_XML_DEPTH) {
                    throw KdbxCorruptFileException("KDBX XML 嵌套深度超出上限（$MAX_XML_DEPTH），疑似解析炸弹")
                }
                if (nodeStack.isEmpty()) {
                    if (qName != KdbxConstants.Xml.ROOT) {
                        throw KdbxCorruptFileException("KDBX XML 根节点必须是 <${KdbxConstants.Xml.ROOT}>，实际为 <$qName>")
                    }
                    nodeStack.addLast(FileNode(innerStreamCipher, binaries) {
                        metaData = it.first
                        rootGroup = it.second
                    })
                } else {
                    nodeStack.addLast(nodeStack.last().startChild(qName, attributes))
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                nodeStack.lastOrNull()?.text(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                nodeStack.removeLast().end()
            }
        }

        try {
            factory.newSAXParser().parse(inputStream, handler)
        } catch (e: KdbxCorruptFileException) {
            throw e
        } catch (e: KdbxInvalidCredentialsException) {
            // 上游（HMAC 块流等）的凭据语义化异常原样放行，不得包装为文件损坏
            throw e
        } catch (e: Exception) {
            throw KdbxCorruptFileException("KDBX XML 解析失败：文档结构非法或已损坏", e)
        }

        return ParseResult(
            meta = metaData,
            rootGroup = rootGroup ?: KdbxGroup(name = "Root")
        )
    }

    fun parse(inputStream: InputStream): ParseResult {
        return parse(inputStream, emptyList())
    }

    companion object {
        /**
         * XML 嵌套深度安全上限（Wave 12 解析炸弹防线）。
         * 合法 KDBX 文档最深路径（KeePassFile>Root>Group*…>Entry>History>Entry>String>Value）
         * 约在 40 层以内，64 为宽松上限；恶意深嵌套在内存耗尽前即被拒绝。
         */
        const val MAX_XML_DEPTH = 64
    }
}

/**
 * <KeePassFile> 根节点：分发 <Meta> 与 <Root>。
 */
private class FileNode(
    private val innerStreamCipher: InnerRandomStreamCipher?,
    private val binaries: List<InnerHeader.BinaryItem>,
    private val onDone: (Pair<KdbxMetaData, KdbxGroup?>) -> Unit
) : SaxNode() {

    private var meta: KdbxMetaData = KdbxMetaData()
    private var rootGroup: KdbxGroup? = null

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.META -> MetaNode { meta = it }
            KdbxConstants.Xml.ROOT_GROUP -> RootNode(innerStreamCipher, binaries) { rootGroup = it }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        onDone(Pair(meta, rootGroup))
    }
}

/**
 * <Root> 包裹节点：包含唯一的根 <Group>。
 */
private class RootNode(
    private val innerStreamCipher: InnerRandomStreamCipher?,
    private val binaries: List<InnerHeader.BinaryItem>,
    private val onDone: (KdbxGroup?) -> Unit
) : SaxNode() {

    private var rootGroup: KdbxGroup? = null

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return if (name == KdbxConstants.Xml.GROUP) {
            GroupNode(null, innerStreamCipher, binaries) { rootGroup = it }
        } else {
            IgnoredNode()
        }
    }

    override fun end() {
        onDone(rootGroup)
    }
}
