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
import javax.xml.parsers.SAXParser
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
        // 强化 XML 解析防御：禁用 DTD 与外部实体（XXE 防御）。
        // ISSUE-P3-10 子项 3 的「逐项设置 + 单项失败落告警」语义由 [buildHardenedParser] 承接；
        // ISSUE-P1-12 起改为**探测式**应用——Android 的 `setFeature` 不校验、延迟到
        // `newSAXParser()` 才抛，仅在 `setFeature` 外套 try/catch 无法阻止设备端解析全量失败。
        val parser = buildHardenedParser()

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
     * 构造启用 XXE 加固的解析器：**逐项探测**平台是否真的接受该特性，不支持的项跳过并留痕告警。
     *
     * 为什么必须探测（ISSUE-P1-12，设备侧实测缺陷，2026-09-11）：
     * Android（Harmony）的 `SAXParserFactoryImpl.setFeature` **不校验**特性名，只做记录；
     * 真正应用发生在 `newSAXParser()`，此时才抛 `SAXNotRecognizedException`。因此仅在
     * `setFeature` 外面套 try/catch **完全无效**——异常会从 `newSAXParser()` 抛出、被上层包装为
     * `KdbxCorruptFileException`，导致**每一次** KDBX 解析失败（emulator-5554 / API 36 实测：
     * `http://xml.org/sax/features/resolve-dtd-uris` 命中该路径，设备端任何库都打不开）。
     * 本方法对每一项都在「已接受集合 + 该项」上实例化一次解析器作探针，成功才纳入。
     *
     * 判定为「不 fail-fast」的理由（沿用 ISSUE-P3-10 子项 3 的既有取舍）：平台对未识别特性抛
     * `SAXNotRecognizedException`，若因此拒绝打开密码库，则一个实现差异就会让用户完全无法读取
     * 自己的合法库（可用性代价远高于收益）。
     *
     * **安全语义不削弱**：DTD 与外部实体的实际拦截由 handler 侧两道与特性支持无关的
     * fail-closed 兜底完成（[DefaultHandler2.startDTD] 直接拒绝 DTD 声明、`resolveEntity`
     * 直接拒绝外部实体，见 [parse] 内 handler 定义）；工厂特性始终只是「尽力加固」层。
     */
    private fun buildHardenedParser(): SAXParser {
        val accepted = mutableListOf<XxeGuardFeature>()
        for (feature in XXE_GUARD_FEATURES) {
            val probe = runCatching { newFactory(accepted + feature).newSAXParser() }
            if (probe.isSuccess) {
                accepted += feature
            } else {
                logger.log(
                    Level.WARNING,
                    "SAX 解析器 XXE 加固特性不受支持，已跳过该项" +
                            "（DTD/外部实体由 handler 兜底拒绝）: $feature",
                    probe.exceptionOrNull()
                )
            }
        }
        return newFactory(accepted).newSAXParser()
    }

    /**
     * 新建 SAX 工厂并尽力应用给定加固特性。
     *
     * 单项 `setFeature` 失败仅告警：部分实现的失败会在 `newSAXParser()` 才显现，
     * 该情形由 [buildHardenedParser] 的探针负责剔除。
     */
    private fun newFactory(features: List<XxeGuardFeature>): SAXParserFactory {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        for (feature in features) {
            runCatching { factory.setFeature(feature.name, feature.enabled) }
                .onFailure {
                    logger.log(
                        Level.WARNING,
                        "SAX 解析器 XXE 加固特性设置失败，已跳过该项: $feature",
                        it
                    )
                }
        }
        return factory
    }

    /** 一项 XXE 加固特性：`name` 为 SAX/Apache 特性名，`enabled` 为期望开关。 */
    private data class XxeGuardFeature(val name: String, val enabled: Boolean) {
        override fun toString(): String = "$name=$enabled"
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

        /**
         * XXE 加固特性清单（顺序即探测顺序）。
         *
         * 平台（尤其 Android/Harmony）不支持的项会在 [buildHardenedParser] 的探针中被自动剔除并告警，
         * 绝不会因实现差异导致合法库无法解析（ISSUE-P1-12）。
         */
        private val XXE_GUARD_FEATURES = listOf(
            XxeGuardFeature(FEATURE_DISALLOW_DOCTYPE_DECL, true),
            XxeGuardFeature(FEATURE_EXTERNAL_GENERAL_ENTITIES, false),
            XxeGuardFeature(FEATURE_EXTERNAL_PARAMETER_ENTITIES, false),
            XxeGuardFeature(FEATURE_RESOLVE_DTD_URIS, false)
        )

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
