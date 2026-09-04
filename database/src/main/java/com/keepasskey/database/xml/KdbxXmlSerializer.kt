package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.io.LittleEndianUtil
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.OutputStream
import java.time.Instant
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * KDBX XML 序列化写回器
 */
class KdbxXmlSerializer(
    private val innerStreamCipher: InnerRandomStreamCipher?
) {

    fun serialize(
        outputStream: OutputStream,
        databaseName: String,
        databaseDescription: String,
        rootGroup: KdbxGroup
    ) {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.newDocument()

        val rootElem = doc.createElement(KdbxConstants.Xml.ROOT)
        doc.appendChild(rootElem)

        // <Meta>
        val metaElem = doc.createElement(KdbxConstants.Xml.META)
        rootElem.appendChild(metaElem)

        appendTextElement(doc, metaElem, "Generator", "KeePasskey")
        appendTextElement(doc, metaElem, "DatabaseName", databaseName)
        appendTextElement(doc, metaElem, "DatabaseDescription", databaseDescription)

        // <Root>
        val rootNodeElem = doc.createElement(KdbxConstants.Xml.ROOT_GROUP)
        rootElem.appendChild(rootNodeElem)

        // Root Group
        serializeGroup(doc, rootNodeElem, rootGroup)

        // Transformer 输出至流
        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")

        val source = DOMSource(doc)
        val result = StreamResult(outputStream)
        transformer.transform(source, result)
    }

    private fun serializeGroup(doc: Document, parentElem: Element, group: KdbxGroup) {
        val groupElem = doc.createElement(KdbxConstants.Xml.GROUP)
        parentElem.appendChild(groupElem)

        appendTextElement(doc, groupElem, KdbxConstants.Xml.UUID, encodeUuid(group.id))
        appendTextElement(doc, groupElem, KdbxConstants.Xml.NAME, group.name)
        appendTextElement(doc, groupElem, KdbxConstants.Xml.NOTES, group.notes)
        appendTextElement(doc, groupElem, KdbxConstants.Xml.ICON_ID, group.iconId.toString())
        appendTextElement(doc, groupElem, KdbxConstants.Xml.IS_EXPANDED, if (group.isExpanded) "True" else "False")

        serializeTimes(doc, groupElem, group.times)

        // 条目
        for (entry in group.entries) {
            serializeEntry(doc, groupElem, entry)
        }

        // 子群组
        for (subgroup in group.subgroups) {
            serializeGroup(doc, groupElem, subgroup)
        }
    }

    private fun serializeEntry(doc: Document, parentElem: Element, entry: KdbxEntry) {
        val entryElem = doc.createElement(KdbxConstants.Xml.ENTRY)
        parentElem.appendChild(entryElem)

        appendTextElement(doc, entryElem, KdbxConstants.Xml.UUID, encodeUuid(entry.id))
        appendTextElement(doc, entryElem, KdbxConstants.Xml.ICON_ID, entry.iconId.toString())

        val fgColor = entry.foregroundColor
        if (fgColor != null) {
            appendTextElement(doc, entryElem, "ForegroundColor", fgColor)
        }
        val bgColor = entry.backgroundColor
        if (bgColor != null) {
            appendTextElement(doc, entryElem, "BackgroundColor", bgColor)
        }

        // 字段
        for ((key, protectedString) in entry.fields) {
            serializeField(doc, entryElem, key, protectedString)
        }

        // 自定义字段
        for (cf in entry.customFields) {
            serializeField(doc, entryElem, cf.key, cf.value)
        }

        if (entry.tags.isNotEmpty()) {
            appendTextElement(doc, entryElem, "Tags", entry.tags.joinToString("; "))
        }

        serializeTimes(doc, entryElem, entry.times)

        // 历史版本
        if (entry.history.isNotEmpty()) {
            val histElem = doc.createElement(KdbxConstants.Xml.HISTORY)
            entryElem.appendChild(histElem)
            for (histEntry in entry.history) {
                serializeEntry(doc, histElem, histEntry)
            }
        }
    }

    private fun serializeField(doc: Document, parentElem: Element, key: String, value: ProtectedString) {
        val stringElem = doc.createElement(KdbxConstants.Xml.STRING)
        parentElem.appendChild(stringElem)

        appendTextElement(doc, stringElem, KdbxConstants.Xml.KEY, key)

        val valueElem = doc.createElement(KdbxConstants.Xml.VALUE)
        stringElem.appendChild(valueElem)

        if (value.isProtected) {
            valueElem.setAttribute(KdbxConstants.Xml.PROTECTED, "True")
            val rawBytes = value.readUtf8()
            try {
                val encodedBytes = if (innerStreamCipher != null) {
                    innerStreamCipher.processBytes(rawBytes)
                } else {
                    rawBytes
                }
                valueElem.textContent = Base64.getEncoder().encodeToString(encodedBytes)
            } finally {
                rawBytes.fill(0)
            }
        } else {
            valueElem.textContent = value.readString()
        }
    }

    private fun serializeTimes(doc: Document, parentElem: Element, times: KdbxTimes) {
        val timesElem = doc.createElement(KdbxConstants.Xml.TIMES)
        parentElem.appendChild(timesElem)

        appendTextElement(doc, timesElem, KdbxConstants.Xml.CREATION_TIME, formatDate(times.creationTime))
        appendTextElement(doc, timesElem, KdbxConstants.Xml.LAST_MODIFICATION_TIME, formatDate(times.lastModificationTime))
        appendTextElement(doc, timesElem, KdbxConstants.Xml.LAST_ACCESS_TIME, formatDate(times.lastAccessTime))
        appendTextElement(doc, timesElem, KdbxConstants.Xml.EXPIRY_TIME, formatDate(times.expiryTime))
        appendTextElement(doc, timesElem, KdbxConstants.Xml.EXPIRES, if (times.expires) "True" else "False")
        appendTextElement(doc, timesElem, KdbxConstants.Xml.USAGE_COUNT, times.usageCount.toString())
        appendTextElement(doc, timesElem, KdbxConstants.Xml.LOCATION_CHANGED, formatDate(times.locationChanged))
    }

    private fun formatDate(instant: Instant): String {
        val seconds = instant.epochSecond + EPOCH_OFFSET_SECONDS
        val bytes = LittleEndianUtil.longTo8Bytes(seconds)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun encodeUuid(uuid: KdbxUuid): String {
        return Base64.getEncoder().encodeToString(uuid.toByteArray())
    }

    private fun appendTextElement(doc: Document, parent: Element, tagName: String, text: String) {
        val elem = doc.createElement(tagName)
        elem.textContent = text
        parent.appendChild(elem)
    }

    companion object {
        private const val EPOCH_OFFSET_SECONDS = 62135596800L
    }
}
