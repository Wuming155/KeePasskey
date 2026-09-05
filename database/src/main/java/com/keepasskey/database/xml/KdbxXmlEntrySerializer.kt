package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.Base64

/**
 * KDBX XML <Entry> 节点序列化写出器。
 */
object KdbxXmlEntrySerializer {

    fun serialize(
        doc: Document,
        parentElem: Element,
        entry: KdbxEntry,
        innerStreamCipher: InnerRandomStreamCipher?,
        isHistory: Boolean = false
    ) {
        val entryElem = doc.createElement(KdbxConstants.Xml.ENTRY)
        parentElem.appendChild(entryElem)

        KdbxXmlDomUtil.appendTextElement(doc, entryElem, KdbxConstants.Xml.UUID, KdbxXmlDomUtil.encodeUuid(entry.id))
        KdbxXmlDomUtil.appendTextElement(doc, entryElem, KdbxConstants.Xml.ICON_ID, entry.iconId.toString())

        entry.customIconId?.let {
            KdbxXmlDomUtil.appendTextElement(doc, entryElem, KdbxConstants.Xml.CUSTOM_ICON_UUID, KdbxXmlDomUtil.encodeUuid(it))
        }
        entry.foregroundColor?.let {
            KdbxXmlDomUtil.appendTextElement(doc, entryElem, KdbxConstants.Xml.FOREGROUND_COLOR, it)
        }
        entry.backgroundColor?.let {
            KdbxXmlDomUtil.appendTextElement(doc, entryElem, KdbxConstants.Xml.BACKGROUND_COLOR, it)
        }
        entry.overrideUrl?.let {
            KdbxXmlDomUtil.appendTextElement(doc, entryElem, KdbxConstants.Xml.OVERRIDE_URL, it)
        }
        if (!entry.qualityCheck) {
            KdbxXmlDomUtil.appendTextElement(doc, entryElem, KdbxConstants.Xml.QUALITY_CHECK, "False")
        }
        entry.previousParentGroup?.let {
            KdbxXmlDomUtil.appendTextElement(doc, entryElem, KdbxConstants.Xml.PREVIOUS_PARENT_GROUP, KdbxXmlDomUtil.encodeUuid(it))
        }
        if (entry.tags.isNotEmpty()) {
            KdbxXmlDomUtil.appendTextElement(doc, entryElem, KdbxConstants.Xml.TAGS, entry.tags.joinToString("; "))
        }

        KdbxXmlDomUtil.serializeTimes(doc, entryElem, entry.times)

        // 字段 (标准字段 + 自定义字段)
        for ((key, protectedString) in entry.fields) {
            serializeField(doc, entryElem, key, protectedString, innerStreamCipher)
        }
        for (cf in entry.customFields) {
            serializeField(doc, entryElem, cf.key, cf.value, innerStreamCipher)
        }

        // 自动输入 (AutoType)
        entry.autoType?.let {
            serializeAutoType(doc, entryElem, it)
        }

        // 附件二进制引用 (<Binary>)
        for (att in entry.attachments) {
            val binElem = doc.createElement(KdbxConstants.Xml.BINARY)
            entryElem.appendChild(binElem)
            KdbxXmlDomUtil.appendTextElement(doc, binElem, KdbxConstants.Xml.KEY, att.name)
            val valElem = doc.createElement(KdbxConstants.Xml.VALUE)
            binElem.appendChild(valElem)
            valElem.setAttribute(KdbxConstants.Xml.REF, att.refIndex.toString())
            if (att.isProtected) {
                valElem.setAttribute(KdbxConstants.Xml.PROTECTED, "True")
            }
        }

        // CustomData
        if (entry.customData.isNotEmpty()) {
            val cdElem = doc.createElement(KdbxConstants.Xml.CUSTOM_DATA)
            entryElem.appendChild(cdElem)
            for ((k, v) in entry.customData) {
                val itemElem = doc.createElement(KdbxConstants.Xml.ITEM)
                cdElem.appendChild(itemElem)
                KdbxXmlDomUtil.appendTextElement(doc, itemElem, KdbxConstants.Xml.KEY, k)
                KdbxXmlDomUtil.appendTextElement(doc, itemElem, KdbxConstants.Xml.VALUE, v)
            }
        }

        // 历史版本 (历史条目内不再递归嵌套历史)
        if (!isHistory && entry.history.isNotEmpty()) {
            val histElem = doc.createElement(KdbxConstants.Xml.HISTORY)
            entryElem.appendChild(histElem)
            for (histEntry in entry.history) {
                serialize(doc, histElem, histEntry, innerStreamCipher, isHistory = true)
            }
        }
    }

    private fun serializeField(
        doc: Document,
        parentElem: Element,
        key: String,
        value: ProtectedString,
        innerStreamCipher: InnerRandomStreamCipher?
    ) {
        val stringElem = doc.createElement(KdbxConstants.Xml.STRING)
        parentElem.appendChild(stringElem)

        KdbxXmlDomUtil.appendTextElement(doc, stringElem, KdbxConstants.Xml.KEY, key)

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

    private fun serializeAutoType(doc: Document, parentElem: Element, autoType: KdbxAutoType) {
        val atElem = doc.createElement(KdbxConstants.Xml.AUTO_TYPE)
        parentElem.appendChild(atElem)

        KdbxXmlDomUtil.appendTextElement(doc, atElem, KdbxConstants.Xml.ENABLED, if (autoType.enabled) "True" else "False")
        KdbxXmlDomUtil.appendTextElement(doc, atElem, KdbxConstants.Xml.DATA_TRANSFER_OBFUSCATION, autoType.dataTransferObfuscation.toString())
        if (autoType.defaultSequence.isNotEmpty()) {
            KdbxXmlDomUtil.appendTextElement(doc, atElem, KdbxConstants.Xml.DEFAULT_SEQUENCE, autoType.defaultSequence)
        }
        for (assoc in autoType.associations) {
            val assocElem = doc.createElement(KdbxConstants.Xml.ASSOCIATION)
            atElem.appendChild(assocElem)
            KdbxXmlDomUtil.appendTextElement(doc, assocElem, KdbxConstants.Xml.WINDOW, assoc.window)
            KdbxXmlDomUtil.appendTextElement(doc, assocElem, KdbxConstants.Xml.KEYSTROKE_SEQUENCE, assoc.keystrokeSequence)
        }
    }
}
