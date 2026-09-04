package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.io.LittleEndianUtil
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/**
 * KDBX XML 反序列化解析器
 */
class KdbxXmlParser(
    private val innerStreamCipher: InnerRandomStreamCipher?
) {

    data class ParseResult(
        val databaseName: String,
        val databaseDescription: String,
        val rootGroup: KdbxGroup
    )

    fun parse(inputStream: InputStream): ParseResult {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(inputStream)
        val rootElement = doc.documentElement // <KeePassFile>

        var dbName = ""
        var dbDesc = ""
        var rootGroup: KdbxGroup? = null

        val children = rootElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val elem = node as Element

            when (elem.tagName) {
                KdbxConstants.Xml.META -> {
                    dbName = getChildText(elem, "DatabaseName")
                    dbDesc = getChildText(elem, "DatabaseDescription")
                }
                KdbxConstants.Xml.ROOT_GROUP -> {
                    val groupElem = findFirstChildElement(elem, KdbxConstants.Xml.GROUP)
                    if (groupElem != null) {
                        rootGroup = parseGroup(groupElem, null)
                    }
                }
            }
        }

        return ParseResult(
            databaseName = dbName,
            databaseDescription = dbDesc,
            rootGroup = rootGroup ?: KdbxGroup(name = "Root")
        )
    }

    private fun parseGroup(groupElem: Element, parentId: KdbxUuid?): KdbxGroup {
        val uuid = parseUuid(getChildText(groupElem, KdbxConstants.Xml.UUID))
        val name = getChildText(groupElem, KdbxConstants.Xml.NAME)
        val notes = getChildText(groupElem, KdbxConstants.Xml.NOTES)
        val iconId = getChildText(groupElem, KdbxConstants.Xml.ICON_ID).toIntOrNull() ?: 48
        val isExpanded = getChildText(groupElem, KdbxConstants.Xml.IS_EXPANDED).lowercase() != "false"

        val timesElem = findFirstChildElement(groupElem, KdbxConstants.Xml.TIMES)
        val times = if (timesElem != null) parseTimes(timesElem) else KdbxTimes()

        val entries = mutableListOf<KdbxEntry>()
        val subgroups = mutableListOf<KdbxGroup>()

        val children = groupElem.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val child = node as Element
            when (child.tagName) {
                KdbxConstants.Xml.ENTRY -> entries.add(parseEntry(child, uuid))
                KdbxConstants.Xml.GROUP -> subgroups.add(parseGroup(child, uuid))
            }
        }

        return KdbxGroup(
            id = uuid,
            parentGroupId = parentId,
            name = name,
            notes = notes,
            iconId = iconId,
            times = times,
            isExpanded = isExpanded,
            entries = entries,
            subgroups = subgroups
        )
    }

    private fun parseEntry(entryElem: Element, parentGroupId: KdbxUuid?): KdbxEntry {
        val uuid = parseUuid(getChildText(entryElem, KdbxConstants.Xml.UUID))
        val iconId = getChildText(entryElem, KdbxConstants.Xml.ICON_ID).toIntOrNull() ?: 0
        val fgColor = getChildTextOrNull(entryElem, "ForegroundColor")
        val bgColor = getChildTextOrNull(entryElem, "BackgroundColor")

        val timesElem = findFirstChildElement(entryElem, KdbxConstants.Xml.TIMES)
        val times = if (timesElem != null) parseTimes(timesElem) else KdbxTimes()

        val fields = mutableMapOf<String, ProtectedString>()
        val customFields = mutableListOf<KdbxCustomField>()
        val tags = mutableListOf<String>()
        val history = mutableListOf<KdbxEntry>()

        val tagsStr = getChildTextOrNull(entryElem, "Tags")
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
                    val key = getChildText(child, KdbxConstants.Xml.KEY)
                    val valueElem = findFirstChildElement(child, KdbxConstants.Xml.VALUE)
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
                KdbxConstants.Xml.HISTORY -> {
                    val histEntries = child.childNodes
                    for (h in 0 until histEntries.length) {
                        val hNode = histEntries.item(h)
                        if (hNode.nodeType == Node.ELEMENT_NODE && (hNode as Element).tagName == KdbxConstants.Xml.ENTRY) {
                            history.add(parseEntry(hNode, parentGroupId))
                        }
                    }
                }
            }
        }

        return KdbxEntry(
            id = uuid,
            parentGroupId = parentGroupId,
            iconId = iconId,
            foregroundColor = fgColor,
            backgroundColor = bgColor,
            fields = fields,
            customFields = customFields,
            times = times,
            history = history,
            tags = tags
        )
    }

    private fun parseTimes(timesElem: Element): KdbxTimes {
        return KdbxTimes(
            creationTime = parseDate(getChildTextOrNull(timesElem, KdbxConstants.Xml.CREATION_TIME)),
            lastModificationTime = parseDate(getChildTextOrNull(timesElem, KdbxConstants.Xml.LAST_MODIFICATION_TIME)),
            lastAccessTime = parseDate(getChildTextOrNull(timesElem, KdbxConstants.Xml.LAST_ACCESS_TIME)),
            expiryTime = parseDate(getChildTextOrNull(timesElem, KdbxConstants.Xml.EXPIRY_TIME)),
            expires = getChildTextOrNull(timesElem, KdbxConstants.Xml.EXPIRES)?.lowercase() == "true",
            usageCount = getChildTextOrNull(timesElem, KdbxConstants.Xml.USAGE_COUNT)?.toLongOrNull() ?: 0L,
            locationChanged = parseDate(getChildTextOrNull(timesElem, KdbxConstants.Xml.LOCATION_CHANGED))
        )
    }

    private fun parseDate(dateStr: String?): Instant {
        if (dateStr.isNullOrBlank()) return Instant.now()
        val clean = dateStr.trim()
        if (clean.contains("-") || clean.contains("T")) {
            return try {
                Instant.parse(clean)
            } catch (_: Exception) {
                Instant.now()
            }
        }
        return try {
            val bytes = Base64.getDecoder().decode(clean)
            val sec = LittleEndianUtil.bytesToLong(bytes)
            val javaSec = sec - EPOCH_OFFSET_SECONDS
            Instant.ofEpochSecond(javaSec)
        } catch (_: Exception) {
            Instant.now()
        }
    }

    private fun parseUuid(text: String): KdbxUuid {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return KdbxUuid.random()
        return try {
            val bytes = Base64.getDecoder().decode(trimmed)
            if (bytes.size == 16) KdbxUuid(bytes) else KdbxUuid.random()
        } catch (_: Exception) {
            KdbxUuid.random()
        }
    }

    private fun isStandardField(key: String): Boolean {
        return key == KdbxConstants.Fields.TITLE ||
                key == KdbxConstants.Fields.USER_NAME ||
                key == KdbxConstants.Fields.PASSWORD ||
                key == KdbxConstants.Fields.URL ||
                key == KdbxConstants.Fields.NOTES
    }

    private fun findFirstChildElement(parent: Element, tagName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tagName) {
                return node
            }
        }
        return null
    }

    private fun getChildText(parent: Element, tagName: String): String {
        return getChildTextOrNull(parent, tagName).orEmpty()
    }

    private fun getChildTextOrNull(parent: Element, tagName: String): String? {
        val elem = findFirstChildElement(parent, tagName) ?: return null
        return elem.textContent
    }

    companion object {
        private const val EPOCH_OFFSET_SECONDS = 62135596800L
    }
}
