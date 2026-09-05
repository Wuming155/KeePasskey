package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.file.InnerHeader
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * KDBX XML 反序列化总解析器。
 * 遵循单一职责与高内聚设计：委派 [KdbxXmlMetaParser]、[KdbxXmlGroupParser]、[KdbxXmlEntryParser] 处理具体子树。
 */
class KdbxXmlParser(
    private val innerStreamCipher: InnerRandomStreamCipher?
) {

    data class ParseResult(
        val meta: KdbxXmlMetaParser.MetaData,
        val rootGroup: KdbxGroup
    ) {
        val databaseName: String get() = meta.databaseName
        val databaseDescription: String get() = meta.databaseDescription
    }

    fun parse(
        inputStream: InputStream,
        binaries: List<InnerHeader.BinaryItem> = emptyList()
    ): ParseResult {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        // 强化 XML 解析防御：禁用外部实体与 DTD
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (_: Exception) {
            // 环境不支持部分特性时宽容继续
        }

        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(inputStream)
        val rootElement = doc.documentElement // <KeePassFile>

        var metaData = KdbxXmlMetaParser.MetaData()
        var rootGroup: KdbxGroup? = null

        val children = rootElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val elem = node as Element

            when (elem.tagName) {
                KdbxConstants.Xml.META -> {
                    metaData = KdbxXmlMetaParser.parse(elem)
                }
                KdbxConstants.Xml.ROOT_GROUP -> {
                    val groupElem = KdbxXmlDomUtil.findFirstChildElement(elem, KdbxConstants.Xml.GROUP)
                    if (groupElem != null) {
                        rootGroup = KdbxXmlGroupParser.parse(groupElem, null, innerStreamCipher, binaries)
                    }
                }
            }
        }

        return ParseResult(
            meta = metaData,
            rootGroup = rootGroup ?: KdbxGroup(name = "Root")
        )
    }

    fun parse(inputStream: InputStream): ParseResult {
        return parse(inputStream, emptyList())
    }
}
