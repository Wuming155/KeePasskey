package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.file.InnerHeader
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.Base64

/**
 * KDBX XML <Entry> 节点解析器。
 */
object KdbxXmlEntryParser {

    fun parse(
        entryElem: Element,
        parentGroupId: KdbxUuid?,
        innerStreamCipher: InnerRandomStreamCipher?,
        binariesPool: List<InnerHeader.BinaryItem>
    ): KdbxEntry {
        val uuid = KdbxXmlDomUtil.parseRequiredUuid(
            KdbxXmlDomUtil.getChildTextOrNull(entryElem, KdbxConstants.Xml.UUID),
            "Entry"
        )
        val iconId = KdbxXmlDomUtil.getChildText(entryElem, KdbxConstants.Xml.ICON_ID).toIntOrNull() ?: 0
        val customIconId = KdbxXmlDomUtil.parseOptionalUuid(
            KdbxXmlDomUtil.getChildTextOrNull(entryElem, KdbxConstants.Xml.CUSTOM_ICON_UUID)
        )
        val fgColor = KdbxXmlDomUtil.getChildTextOrNull(entryElem, KdbxConstants.Xml.FOREGROUND_COLOR)
        val bgColor = KdbxXmlDomUtil.getChildTextOrNull(entryElem, KdbxConstants.Xml.BACKGROUND_COLOR)
        val overrideUrl = KdbxXmlDomUtil.getChildTextOrNull(entryElem, KdbxConstants.Xml.OVERRIDE_URL)
        val qualityCheck = KdbxXmlDomUtil.getChildTextOrNull(entryElem, KdbxConstants.Xml.QUALITY_CHECK)?.lowercase() != "false"
        val previousParentGroup = KdbxXmlDomUtil.parseOptionalUuid(
            KdbxXmlDomUtil.getChildTextOrNull(entryElem, KdbxConstants.Xml.PREVIOUS_PARENT_GROUP)
        )

        val timesElem = KdbxXmlDomUtil.findFirstChildElement(entryElem, KdbxConstants.Xml.TIMES)
        val times = if (timesElem != null) KdbxXmlDomUtil.parseTimes(timesElem) else KdbxTimes()

        val fields = mutableMapOf<String, ProtectedString>()
        val customFields = mutableListOf<KdbxCustomField>()
        val tags = mutableListOf<String>()
        val attachments = mutableListOf<KdbxAttachment>()
        var autoType: KdbxAutoType? = null
        val customData = mutableMapOf<String, String>()
        val history = mutableListOf<KdbxEntry>()

        val tagsStr = KdbxXmlDomUtil.getChildTextOrNull(entryElem, KdbxConstants.Xml.TAGS)
        if (!tagsStr.isNullOrBlank()) {
            tags.addAll(tagsStr.split(";").map { it.trim() }.filter { it.isNotEmpty() })
        }

        val children = entryElem.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val child = node as Element

            when (child.tagName) {
                KdbxConstants.Xml.STRING -> {
                    val key = KdbxXmlDomUtil.getChildText(child, KdbxConstants.Xml.KEY)
                    val valueElem = KdbxXmlDomUtil.findFirstChildElement(child, KdbxConstants.Xml.VALUE)
                    if (valueElem != null) {
                        val isProtected = valueElem.getAttribute(KdbxConstants.Xml.PROTECTED).lowercase() == "true"
                        val rawValue = valueElem.textContent.orEmpty()
                        val protectedString = if (isProtected && innerStreamCipher != null) {
                            val decoded = try {
                                Base64.getDecoder().decode(rawValue)
                            } catch (_: Exception) {
                                rawValue.toByteArray()
                            }
                            val plainBytes = innerStreamCipher.processBytes(decoded)
                            ProtectedString(isProtected = true, bytes = plainBytes)
                        } else {
                            ProtectedString(rawValue, isProtected = isProtected)
                        }

                        if (isStandardField(key)) {
                            fields[key] = protectedString
                        } else {
                            customFields.add(KdbxCustomField(key, protectedString, isProtected))
                        }
                    }
                }
                KdbxConstants.Xml.BINARY -> {
                    val key = KdbxXmlDomUtil.getChildText(child, KdbxConstants.Xml.KEY)
                    val valueElem = KdbxXmlDomUtil.findFirstChildElement(child, KdbxConstants.Xml.VALUE)
                    if (valueElem != null) {
                        val refStr = valueElem.getAttribute(KdbxConstants.Xml.REF).ifEmpty { valueElem.textContent }
                        val refIndex = refStr.trim().toIntOrNull() ?: 0
                        val isProtected = valueElem.getAttribute(KdbxConstants.Xml.PROTECTED).lowercase() == "true"
                        val data = if (refIndex in binariesPool.indices) {
                            binariesPool[refIndex].data
                        } else {
                            ByteArray(0)
                        }
                        attachments.add(
                            KdbxAttachment(
                                name = key,
                                refIndex = refIndex,
                                isProtected = isProtected,
                                data = data
                            )
                        )
                    }
                }
                KdbxConstants.Xml.AUTO_TYPE -> {
                    autoType = parseAutoType(child)
                }
                KdbxConstants.Xml.CUSTOM_DATA -> {
                    val itemElems = KdbxXmlDomUtil.findChildElements(child, KdbxConstants.Xml.ITEM)
                    for (item in itemElems) {
                        val k = KdbxXmlDomUtil.getChildText(item, KdbxConstants.Xml.KEY)
                        val v = KdbxXmlDomUtil.getChildText(item, KdbxConstants.Xml.VALUE)
                        if (k.isNotEmpty()) {
                            customData[k] = v
                        }
                    }
                }
                KdbxConstants.Xml.HISTORY -> {
                    val histEntries = child.childNodes
                    for (h in 0 until histEntries.length) {
                        val hNode = histEntries.item(h)
                        if (hNode.nodeType == Node.ELEMENT_NODE && (hNode as Element).tagName == KdbxConstants.Xml.ENTRY) {
                            history.add(parse(hNode, parentGroupId, innerStreamCipher, binariesPool))
                        }
                    }
                }
            }
        }

        return KdbxEntry(
            id = uuid,
            parentGroupId = parentGroupId,
            iconId = iconId,
            customIconId = customIconId,
            foregroundColor = fgColor,
            backgroundColor = bgColor,
            overrideUrl = overrideUrl,
            qualityCheck = qualityCheck,
            previousParentGroup = previousParentGroup,
            fields = fields,
            customFields = customFields,
            times = times,
            history = history,
            tags = tags,
            attachments = attachments,
            autoType = autoType,
            customData = customData
        )
    }

    private fun parseAutoType(elem: Element): KdbxAutoType {
        val enabled = KdbxXmlDomUtil.getChildTextOrNull(elem, KdbxConstants.Xml.ENABLED)?.lowercase() != "false"
        val dataTransferObfuscation = KdbxXmlDomUtil.getChildTextOrNull(elem, KdbxConstants.Xml.DATA_TRANSFER_OBFUSCATION)?.toIntOrNull() ?: 0
        val defaultSequence = KdbxXmlDomUtil.getChildText(elem, KdbxConstants.Xml.DEFAULT_SEQUENCE)

        val associations = mutableListOf<KdbxAutoType.AutoTypeAssociation>()
        val assocElems = KdbxXmlDomUtil.findChildElements(elem, KdbxConstants.Xml.ASSOCIATION)
        for (assoc in assocElems) {
            val window = KdbxXmlDomUtil.getChildText(assoc, KdbxConstants.Xml.WINDOW)
            val seq = KdbxXmlDomUtil.getChildText(assoc, KdbxConstants.Xml.KEYSTROKE_SEQUENCE)
            associations.add(KdbxAutoType.AutoTypeAssociation(window, seq))
        }

        return KdbxAutoType(
            enabled = enabled,
            dataTransferObfuscation = dataTransferObfuscation,
            defaultSequence = defaultSequence,
            associations = associations
        )
    }

    private fun isStandardField(key: String): Boolean {
        return key == KdbxConstants.Fields.TITLE ||
                key == KdbxConstants.Fields.USER_NAME ||
                key == KdbxConstants.Fields.PASSWORD ||
                key == KdbxConstants.Fields.URL ||
                key == KdbxConstants.Fields.NOTES
    }
}
