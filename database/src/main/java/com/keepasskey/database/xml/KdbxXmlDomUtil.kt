package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.Base64

/**
 * KDBX XML DOM 节点操作通用助手。
 */
object KdbxXmlDomUtil {

    fun findFirstChildElement(parent: Element, tagName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tagName) {
                return node
            }
        }
        return null
    }

    fun findChildElements(parent: Element, tagName: String): List<Element> {
        val list = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tagName) {
                list.add(node)
            }
        }
        return list
    }

    fun getChildText(parent: Element, tagName: String): String {
        return getChildTextOrNull(parent, tagName).orEmpty()
    }

    fun getChildTextOrNull(parent: Element, tagName: String): String? {
        val elem = findFirstChildElement(parent, tagName) ?: return null
        return elem.textContent
    }

    fun appendTextElement(doc: Document, parent: Element, tagName: String, text: String): Element {
        val elem = doc.createElement(tagName)
        elem.textContent = text
        parent.appendChild(elem)
        return elem
    }

    fun parseRequiredUuid(text: String?, context: String): KdbxUuid {
        if (text.isNullOrBlank()) {
            throw KdbxCorruptFileException("缺少必需的 UUID 节点 ($context)")
        }
        val clean = text.trim()
        return try {
            val bytes = Base64.getDecoder().decode(clean)
            if (bytes.size != 16) {
                throw KdbxCorruptFileException("UUID 字节长度非法: ${bytes.size}，期望 16 字节 ($context)")
            }
            KdbxUuid(bytes)
        } catch (e: IllegalArgumentException) {
            throw KdbxCorruptFileException("UUID Base64 编码损坏: $clean ($context)", e)
        }
    }

    fun parseOptionalUuid(text: String?): KdbxUuid? {
        if (text.isNullOrBlank()) return null
        val clean = text.trim()
        return try {
            val bytes = Base64.getDecoder().decode(clean)
            if (bytes.size != 16) {
                throw KdbxCorruptFileException("可选 UUID 字节长度非法: ${bytes.size}，期望 16 字节")
            }
            KdbxUuid(bytes)
        } catch (e: IllegalArgumentException) {
            throw KdbxCorruptFileException("可选 UUID Base64 编码损坏: $clean", e)
        }
    }

    fun encodeUuid(uuid: KdbxUuid): String {
        return Base64.getEncoder().encodeToString(uuid.toByteArray())
    }

    fun parseTimes(timesElem: Element): KdbxTimes {
        return KdbxTimes(
            creationTime = KdbxXmlTimeHelper.parseDate(getChildTextOrNull(timesElem, KdbxConstants.Xml.CREATION_TIME)),
            lastModificationTime = KdbxXmlTimeHelper.parseDate(getChildTextOrNull(timesElem, KdbxConstants.Xml.LAST_MODIFICATION_TIME)),
            lastAccessTime = KdbxXmlTimeHelper.parseDate(getChildTextOrNull(timesElem, KdbxConstants.Xml.LAST_ACCESS_TIME)),
            expiryTime = KdbxXmlTimeHelper.parseDate(getChildTextOrNull(timesElem, KdbxConstants.Xml.EXPIRY_TIME)),
            expires = getChildTextOrNull(timesElem, KdbxConstants.Xml.EXPIRES)?.lowercase() == "true",
            usageCount = getChildTextOrNull(timesElem, KdbxConstants.Xml.USAGE_COUNT)?.toLongOrNull() ?: 0L,
            locationChanged = KdbxXmlTimeHelper.parseDate(getChildTextOrNull(timesElem, KdbxConstants.Xml.LOCATION_CHANGED))
        )
    }

    fun serializeTimes(doc: Document, parentElem: Element, times: KdbxTimes) {
        val timesElem = doc.createElement(KdbxConstants.Xml.TIMES)
        parentElem.appendChild(timesElem)

        appendTextElement(doc, timesElem, KdbxConstants.Xml.CREATION_TIME, KdbxXmlTimeHelper.formatDate(times.creationTime))
        appendTextElement(doc, timesElem, KdbxConstants.Xml.LAST_MODIFICATION_TIME, KdbxXmlTimeHelper.formatDate(times.lastModificationTime))
        appendTextElement(doc, timesElem, KdbxConstants.Xml.LAST_ACCESS_TIME, KdbxXmlTimeHelper.formatDate(times.lastAccessTime))
        appendTextElement(doc, timesElem, KdbxConstants.Xml.EXPIRY_TIME, KdbxXmlTimeHelper.formatDate(times.expiryTime))
        appendTextElement(doc, timesElem, KdbxConstants.Xml.EXPIRES, if (times.expires) "True" else "False")
        appendTextElement(doc, timesElem, KdbxConstants.Xml.USAGE_COUNT, times.usageCount.toString())
        appendTextElement(doc, timesElem, KdbxConstants.Xml.LOCATION_CHANGED, KdbxXmlTimeHelper.formatDate(times.locationChanged))
    }
}
