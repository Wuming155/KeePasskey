package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import java.util.Base64

/**
 * KDBX XML <Entry> 节点序列化写出器（流式）。
 */
object KdbxXmlEntrySerializer {

    fun serialize(
        writer: KdbxXmlStreamWriter,
        entry: KdbxEntry,
        innerStreamCipher: InnerRandomStreamCipher?,
        isHistory: Boolean = false
    ) {
        writer.startElement(KdbxConstants.Xml.ENTRY)

        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.UUID, KdbxXmlValueUtil.encodeUuid(entry.id))
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.ICON_ID, entry.iconId.toString())

        entry.customIconId?.let {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.CUSTOM_ICON_UUID, KdbxXmlValueUtil.encodeUuid(it))
        }
        KdbxXmlWriteUtil.optionalTextElement(writer, KdbxConstants.Xml.FOREGROUND_COLOR, entry.foregroundColor)
        KdbxXmlWriteUtil.optionalTextElement(writer, KdbxConstants.Xml.BACKGROUND_COLOR, entry.backgroundColor)
        KdbxXmlWriteUtil.optionalTextElement(writer, KdbxConstants.Xml.OVERRIDE_URL, entry.overrideUrl)
        if (!entry.qualityCheck) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.QUALITY_CHECK, "False")
        }
        entry.previousParentGroup?.let {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PREVIOUS_PARENT_GROUP, KdbxXmlValueUtil.encodeUuid(it))
        }
        if (entry.tags.isNotEmpty()) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.TAGS, entry.tags.joinToString("; "))
        }

        KdbxXmlWriteUtil.serializeTimes(writer, entry.times)

        for ((key, protectedString) in entry.fields) {
            serializeField(writer, key, protectedString, innerStreamCipher)
        }
        for (cf in entry.customFields) {
            serializeField(writer, cf.key, cf.value, innerStreamCipher)
        }

        entry.autoType?.let {
            serializeAutoType(writer, it)
        }

        for (att in entry.attachments) {
            writer.startElement(KdbxConstants.Xml.BINARY)
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.KEY, att.name)
            writer.startElement(KdbxConstants.Xml.VALUE)
            writer.attribute(KdbxConstants.Xml.REF, att.refIndex.toString())
            if (att.isProtected) {
                writer.attribute(KdbxConstants.Xml.PROTECTED, "True")
            }
            writer.endElement()
            writer.endElement()
        }

        if (entry.customData.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.CUSTOM_DATA)
            for ((k, v) in entry.customData) {
                writer.startElement(KdbxConstants.Xml.ITEM)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.KEY, k)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.VALUE, v)
                writer.endElement()
            }
            writer.endElement()
        }

        if (!isHistory && entry.history.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.HISTORY)
            for (histEntry in entry.history) {
                serialize(writer, histEntry, innerStreamCipher, isHistory = true)
            }
            writer.endElement()
        }

        writer.endElement()
    }

    private fun serializeField(
        writer: KdbxXmlStreamWriter,
        key: String,
        value: ProtectedString,
        innerStreamCipher: InnerRandomStreamCipher?
    ) {
        writer.startElement(KdbxConstants.Xml.STRING)
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.KEY, key)

        writer.startElement(KdbxConstants.Xml.VALUE)
        if (value.isProtected) {
            writer.attribute(KdbxConstants.Xml.PROTECTED, "True")
            val rawBytes = value.readUtf8()
            try {
                val encodedBytes = if (innerStreamCipher != null) {
                    innerStreamCipher.processBytes(rawBytes)
                } else {
                    rawBytes
                }
                writer.text(Base64.getEncoder().encodeToString(encodedBytes))
            } finally {
                rawBytes.fill(0)
            }
        } else {
            writer.text(value.readString())
        }
        writer.endElement()

        writer.endElement()
    }

    private fun serializeAutoType(writer: KdbxXmlStreamWriter, autoType: KdbxAutoType) {
        writer.startElement(KdbxConstants.Xml.AUTO_TYPE)
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.ENABLED, if (autoType.enabled) "True" else "False")
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATA_TRANSFER_OBFUSCATION, autoType.dataTransferObfuscation.toString())
        if (autoType.defaultSequence.isNotEmpty()) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DEFAULT_SEQUENCE, autoType.defaultSequence)
        }
        for (assoc in autoType.associations) {
            writer.startElement(KdbxConstants.Xml.ASSOCIATION)
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.WINDOW, assoc.window)
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.KEYSTROKE_SEQUENCE, assoc.keystrokeSequence)
            writer.endElement()
        }
        writer.endElement()
    }
}
