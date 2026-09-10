package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.file.InnerHeader
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.ext.DefaultHandler2
import java.io.InputStream
import java.util.logging.Level
import java.util.logging.Logger
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
        // 强化 XML 解析防御：禁用 DTD 与外部实体（XXE 防御）。
        // ISSUE-P3-10 子项 3：逐项设置且单项失败即落告警——原实现四项共用一个空 catch，
        // 首项失败会让后续三项根本不被尝试，且失败原因完全无迹可查。
        applyXxeGuardFeature(factory, FEATURE_DISALLOW_DOCTYPE_DECL, true)
        applyXxeGuardFeature(factory, FEATURE_EXTERNAL_GENERAL_ENTITIES, false)
        applyXxeGuardFeature(factory, FEATURE_EXTERNAL_PARAMETER_ENTITIES, false)
        applyXxeGuardFeature(factory, FEATURE_RESOLVE_DTD_URIS, false)

        var metaData = KdbxMetaData()
        var rootGroup: KdbxGroup? = null
        val nodeStack = ArrayDeque<SaxNode>()

        val handler = object : DefaultHandler2() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
                // 拒绝一切外部实体解析（防御纵深）
                throw KdbxCorruptFileException("KDBX XML 不允许包含外部实体引用: $systemId")
            }

            /**
             * DTD 出现即拒绝（fail-closed 纵深防御）。
             *
             * KDBX XML 规范不含 DOCTYPE，官方 KeePass / KeePassXC / KeePassDX 产物亦无。
             * 本回调不依赖平台解析器是否支持 [FEATURE_DISALLOW_DOCTYPE_DECL]——在该特性
             * 不受支持的实现（如 Android Expat 后端）上，内部实体展开炸弹（billion laughs）
             * 可在字符读取上限生效前先撑爆内存，故在 DTD 声明处直接中止解析（合法库零影响）。
             */
            override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                throw KdbxCorruptFileException("KDBX XML 不允许包含 DTD 声明（DOCTYPE）")
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
            val parser = factory.newSAXParser()
            // 注册 LexicalHandler：DTD 声明（startDTD）由 handler 直接 fail-closed 拒绝。
            // 属性不受支持时仅告警——加固层级降级但绝不阻断合法库解析。
            try {
                parser.xmlReader.setProperty(LEXICAL_HANDLER_PROPERTY, handler)
            } catch (e: Exception) {
                logger.log(
                    Level.WARNING,
                    "解析器不支持 lexical-handler 属性，DTD 声明仅由特性开关拦截: " +
                            "$LEXICAL_HANDLER_PROPERTY",
                    e
                )
            }
            parser.parse(inputStream, handler)
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

    /**
     * 逐项应用 XXE 加固特性：**单项失败仅告警，不中断也不拒绝解析**。
     *
     * 判定为「不 fail-fast」的理由：平台解析器对未识别特性会抛 `SAXNotRecognizedException`，
     * 若因此拒绝打开密码库，则一个实现差异就会让用户完全无法读取自己的合法库（可用性代价
     * 远高于收益）；且解析器已由 [DefaultHandler2.startDTD] 与 `resolveEntity` 两道
     * 与特性支持无关的 fail-closed 兜底覆盖（DTD 直接拒绝、外部实体直接拒绝），
     * 故这里保留「尽力加固 + 留痕告警」语义。
     */
    private fun applyXxeGuardFeature(factory: SAXParserFactory, feature: String, enabled: Boolean) {
        try {
            factory.setFeature(feature, enabled)
        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "SAX 解析器 XXE 加固特性不受支持，已跳过该项（DTD/外部实体由 handler 兜底拒绝）: " +
                        "$feature=$enabled",
                e
            )
        }
    }

    fun parse(inputStream: InputStream): ParseResult {
        return parse(inputStream, emptyList())
    }

    companion object {
        private val logger = Logger.getLogger(KdbxXmlParser::class.java.name)

        /** 完全禁止 DOCTYPE 声明（XXE 与内部实体炸弹的总闸） */
        private const val FEATURE_DISALLOW_DOCTYPE_DECL =
            "http://apache.org/xml/features/disallow-doctype-decl"

        /** 禁止解析外部通用实体 */
        private const val FEATURE_EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities"

        /** 禁止解析外部参数实体 */
        private const val FEATURE_EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities"

        /** 禁止解析 DTD 的 URI */
        private const val FEATURE_RESOLVE_DTD_URIS =
            "http://xml.org/sax/features/resolve-dtd-uris"

        /** LexicalHandler 注册属性名（用于接收 DTD 声明事件） */
        private const val LEXICAL_HANDLER_PROPERTY =
            "http://xml.org/sax/properties/lexical-handler"

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
