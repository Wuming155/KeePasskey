package com.keepasskey.app.data.importer

import org.xml.sax.InputSource
import org.xml.sax.ext.DefaultHandler2
import java.io.StringReader
import java.util.logging.Level
import java.util.logging.Logger
import javax.xml.parsers.SAXParserFactory

/**
 * 加固的 SAX 解析入口（明文导入 XML 专用）。
 *
 * 安全要求（ISSUE-P3-19 硬要求：防 XXE）：本对象**必须**为每次解析显式关闭外部实体能力。
 * 采用与库内 `KdbxXmlParser`（ISSUE-P3-10 子项 3 的既定实现）**同一套双保险**：
 * 1. 逐项设置 XXE 加固特性（`disallow-doctype-decl` / 外部通用实体 / 外部参数实体 / DTD URI），
 *    单项不受支持仅落告警——平台 SAX 实现（Android Expat 后端）对未识别特性会抛异常，
 *    若因此让用户无法导入合法文件，可用性代价高于收益；
 * 2. 与特性支持**无关**的 handler 侧 fail-closed 兜底（见 [KeePassXmlImportHandler]）：
 *    `startDTD` 直接拒绝 DOCTYPE（同时封死内部实体展开炸弹），`resolveEntity` 拒绝一切外部实体。
 *
 * 刻意不使用 `DocumentBuilderFactory`/DOM：SAX 的 `characters(char[], start, len)` 让密码可在
 * 第一现场写入 [SensitiveTextBuffer]，全程不产生 `String`（DOM 的 `getTextContent()` 做不到）。
 */
internal object HardenedXmlReader {

    /**
     * 解析 [text]（已由 [ImportTextDecoder] 严格 UTF-8 解码，BOM 已剥离）。
     *
     * 采用 `InputSource(StringReader)`：编码已由上游确定，解析器不再做二次编码猜测，
     * 同时避免 XML 声明中的 `encoding` 与实际字节不符带来的歧义。
     */
    fun parse(text: String, handler: DefaultHandler2) {
        val parser = newLenientParser(handler)
        parser.parse(InputSource(StringReader(text)), handler)
    }

    /**
     * 构建带 XXE 加固特性的 SAX 解析器；任一特性被平台拒绝都**不得阻断导入**。
     *
     * ISSUE-P1-15（设备实测 2026-09-11）：Android Harmony SAX 对部分特性（如
     * `resolve-dtd-uris`）**延迟到 `newSAXParser()` 才抛** `ParserConfigurationException`
     * （宿主 JVM 的 Xerces 无此行为，宿主单测因此无法拦截）。处置：逐轮剔除最后一个
     * 待用特性并重建解析器重试；全部剔除仍失败才放行异常——handler 侧
     * `startDTD` / `resolveEntity` 兜底在任意轮次都保持 fail-closed。
     */
    private fun newLenientParser(handler: DefaultHandler2): javax.xml.parsers.SAXParser {
        var features = GUARD_FEATURES
        while (true) {
            val factory = factoryProvider()
            factory.isNamespaceAware = false
            disableXInclude(factory)
            features.forEach { (feature, enabled) -> applyGuardFeature(factory, feature, enabled) }
            try {
                return factory.newSAXParser().also { registerLexicalHandler(it.xmlReader, handler) }
            } catch (e: javax.xml.parsers.ParserConfigurationException) {
                val dropped = features.lastOrNull()?.first
                logger.log(
                    Level.WARNING,
                    "SAX 解析器在构建阶段拒绝加固特性，剔除后重试: dropped=$dropped",
                    e
                )
                if (dropped == null) throw e
                features = features.dropLast(1)
            }
        }
    }

    /** 逐项加固：单项失败仅告警，不阻断（handler 侧兜底保证 fail-closed 语义不变）。 */
    private fun applyGuardFeature(factory: SAXParserFactory, feature: String, enabled: Boolean) {
        try {
            factory.setFeature(feature, enabled)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "SAX 解析器不支持该 XXE 加固特性，已跳过: $feature=$enabled", e)
        }
    }

    private fun disableXInclude(factory: SAXParserFactory) {
        try {
            factory.isXIncludeAware = false
        } catch (e: UnsupportedOperationException) {
            logger.log(Level.WARNING, "SAX 解析器不支持关闭 XInclude，已跳过", e)
        }
    }

    /**
     * 注册 LexicalHandler 以接收 `startDTD` 事件（DTD 声明处 fail-closed 拦截）。
     * 属性不受支持时仅告警——此时 DTD 仍由 `disallow-doctype-decl` 特性拦截。
     */
    private fun registerLexicalHandler(reader: org.xml.sax.XMLReader, handler: DefaultHandler2) {
        try {
            reader.setProperty(LEXICAL_HANDLER_PROPERTY, handler)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "SAX 解析器不支持 lexical-handler，DTD 仅由特性开关拦截", e)
        }
    }

    private val logger = Logger.getLogger(HardenedXmlReader::class.java.name)

    /**
     * 平台工厂提供者（测试接缝）：默认走 SPI 发现；单测注入假工厂驱动
     * 「newSAXParser 延迟拒绝加固特性」的设备端分支。
     */
    internal var factoryProvider: () -> SAXParserFactory = { SAXParserFactory.newInstance() }

    /** 加固特性清单（按序逐项设置；顺序即降级剔除顺序的倒数）。 */
    private val GUARD_FEATURES: List<Pair<String, Boolean>> = listOf(
        FEATURE_DISALLOW_DOCTYPE_DECL to true,
        FEATURE_EXTERNAL_GENERAL_ENTITIES to false,
        FEATURE_EXTERNAL_PARAMETER_ENTITIES to false,
        FEATURE_RESOLVE_DTD_URIS to false
    )

    /** 完全禁止 DOCTYPE 声明（XXE 与内部实体炸弹的总闸）。 */
    private const val FEATURE_DISALLOW_DOCTYPE_DECL =
        "http://apache.org/xml/features/disallow-doctype-decl"

    /** 禁止解析外部通用实体。 */
    private const val FEATURE_EXTERNAL_GENERAL_ENTITIES =
        "http://xml.org/sax/features/external-general-entities"

    /** 禁止解析外部参数实体。 */
    private const val FEATURE_EXTERNAL_PARAMETER_ENTITIES =
        "http://xml.org/sax/features/external-parameter-entities"

    /** 禁止解析 DTD 的 URI（同包单测可见；P1-15 延迟拒绝分支的回归锚点）。 */
    internal const val FEATURE_RESOLVE_DTD_URIS =
        "http://xml.org/sax/features/resolve-dtd-uris"

    /** LexicalHandler 注册属性名（用于接收 DTD 声明事件）。 */
    private const val LEXICAL_HANDLER_PROPERTY =
        "http://xml.org/sax/properties/lexical-handler"
}
