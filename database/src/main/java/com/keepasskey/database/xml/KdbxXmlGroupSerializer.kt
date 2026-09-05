package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * KDBX XML <Group> 节点序列化写出器。
 */
object KdbxXmlGroupSerializer {

    fun serialize(
        doc: Document,
        parentElem: Element,
        group: KdbxGroup,
        innerStreamCipher: InnerRandomStreamCipher?
    ) {
        val groupElem = doc.createElement(KdbxConstants.Xml.GROUP)
        parentElem.appendChild(groupElem)

        KdbxXmlDomUtil.appendTextElement(doc, groupElem, KdbxConstants.Xml.UUID, KdbxXmlDomUtil.encodeUuid(group.id))
        KdbxXmlDomUtil.appendTextElement(doc, groupElem, KdbxConstants.Xml.NAME, group.name)
        KdbxXmlDomUtil.appendTextElement(doc, groupElem, KdbxConstants.Xml.NOTES, group.notes)
        KdbxXmlDomUtil.appendTextElement(doc, groupElem, KdbxConstants.Xml.ICON_ID, group.iconId.toString())

        group.customIconId?.let {
            KdbxXmlDomUtil.appendTextElement(doc, groupElem, KdbxConstants.Xml.CUSTOM_ICON_UUID, KdbxXmlDomUtil.encodeUuid(it))
        }

        KdbxXmlDomUtil.serializeTimes(doc, groupElem, group.times)
        KdbxXmlDomUtil.appendTextElement(doc, groupElem, KdbxConstants.Xml.IS_EXPANDED, if (group.isExpanded) "True" else "False")

        if (group.defaultAutoTypeSequence.isNotEmpty()) {
            KdbxXmlDomUtil.appendTextElement(doc, groupElem, KdbxConstants.Xml.DEFAULT_AUTO_TYPE_SEQUENCE, group.defaultAutoTypeSequence)
        }
        group.enableAutoType?.let {
            KdbxXmlDomUtil.appendTextElement(doc, groupElem, KdbxConstants.Xml.ENABLE_AUTO_TYPE, if (it) "True" else "False")
        }
        group.enableSearching?.let {
            KdbxXmlDomUtil.appendTextElement(doc, groupElem, KdbxConstants.Xml.ENABLE_SEARCHING, if (it) "True" else "False")
        }
        group.lastTopVisibleEntry?.let {
            KdbxXmlDomUtil.appendTextElement(doc, groupElem, KdbxConstants.Xml.LAST_TOP_VISIBLE_ENTRY, KdbxXmlDomUtil.encodeUuid(it))
        }
        group.previousParentGroup?.let {
            KdbxXmlDomUtil.appendTextElement(doc, groupElem, KdbxConstants.Xml.PREVIOUS_PARENT_GROUP, KdbxXmlDomUtil.encodeUuid(it))
        }

        // 条目
        for (entry in group.entries) {
            KdbxXmlEntrySerializer.serialize(doc, groupElem, entry, innerStreamCipher)
        }

        // 子分组
        for (subgroup in group.subgroups) {
            serialize(doc, groupElem, subgroup, innerStreamCipher)
        }
    }
}
