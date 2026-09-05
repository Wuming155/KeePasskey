package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.file.InnerHeader
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * KDBX XML <Group> 节点解析器。
 */
object KdbxXmlGroupParser {

    fun parse(
        groupElem: Element,
        parentId: KdbxUuid?,
        innerStreamCipher: InnerRandomStreamCipher?,
        binariesPool: List<InnerHeader.BinaryItem>
    ): KdbxGroup {
        val uuid = KdbxXmlDomUtil.parseRequiredUuid(
            KdbxXmlDomUtil.getChildTextOrNull(groupElem, KdbxConstants.Xml.UUID),
            "Group"
        )
        val name = KdbxXmlDomUtil.getChildText(groupElem, KdbxConstants.Xml.NAME)
        val notes = KdbxXmlDomUtil.getChildText(groupElem, KdbxConstants.Xml.NOTES)
        val iconId = KdbxXmlDomUtil.getChildText(groupElem, KdbxConstants.Xml.ICON_ID).toIntOrNull() ?: 48
        val customIconId = KdbxXmlDomUtil.parseOptionalUuid(
            KdbxXmlDomUtil.getChildTextOrNull(groupElem, KdbxConstants.Xml.CUSTOM_ICON_UUID)
        )
        val isExpanded = KdbxXmlDomUtil.getChildTextOrNull(groupElem, KdbxConstants.Xml.IS_EXPANDED)?.lowercase() != "false"
        val defaultAutoTypeSequence = KdbxXmlDomUtil.getChildText(groupElem, KdbxConstants.Xml.DEFAULT_AUTO_TYPE_SEQUENCE)

        val enableAutoType = parseNullableBoolean(KdbxXmlDomUtil.getChildTextOrNull(groupElem, KdbxConstants.Xml.ENABLE_AUTO_TYPE))
        val enableSearching = parseNullableBoolean(KdbxXmlDomUtil.getChildTextOrNull(groupElem, KdbxConstants.Xml.ENABLE_SEARCHING))
        val lastTopVisibleEntry = KdbxXmlDomUtil.parseOptionalUuid(
            KdbxXmlDomUtil.getChildTextOrNull(groupElem, KdbxConstants.Xml.LAST_TOP_VISIBLE_ENTRY)
        )
        val previousParentGroup = KdbxXmlDomUtil.parseOptionalUuid(
            KdbxXmlDomUtil.getChildTextOrNull(groupElem, KdbxConstants.Xml.PREVIOUS_PARENT_GROUP)
        )

        val timesElem = KdbxXmlDomUtil.findFirstChildElement(groupElem, KdbxConstants.Xml.TIMES)
        val times = if (timesElem != null) KdbxXmlDomUtil.parseTimes(timesElem) else KdbxTimes()

        val entries = mutableListOf<KdbxEntry>()
        val subgroups = mutableListOf<KdbxGroup>()

        val children = groupElem.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val child = node as Element
            when (child.tagName) {
                KdbxConstants.Xml.ENTRY -> entries.add(KdbxXmlEntryParser.parse(child, uuid, innerStreamCipher, binariesPool))
                KdbxConstants.Xml.GROUP -> subgroups.add(parse(child, uuid, innerStreamCipher, binariesPool))
            }
        }

        return KdbxGroup(
            id = uuid,
            parentGroupId = parentId,
            name = name,
            notes = notes,
            iconId = iconId,
            customIconId = customIconId,
            times = times,
            isExpanded = isExpanded,
            defaultAutoTypeSequence = defaultAutoTypeSequence,
            enableAutoType = enableAutoType,
            enableSearching = enableSearching,
            lastTopVisibleEntry = lastTopVisibleEntry,
            previousParentGroup = previousParentGroup,
            entries = entries,
            subgroups = subgroups
        )
    }

    private fun parseNullableBoolean(str: String?): Boolean? {
        if (str.isNullOrBlank() || str.equals("null", ignoreCase = true)) return null
        return str.equals("true", ignoreCase = true)
    }
}
