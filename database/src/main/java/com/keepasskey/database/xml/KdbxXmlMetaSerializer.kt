package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.MemoryProtectionConfig
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.time.Instant
import java.util.Base64

/**
 * KDBX XML <Meta> 节点序列化写出器。
 */
object KdbxXmlMetaSerializer {

    fun serialize(
        doc: Document,
        rootElem: Element,
        generator: String,
        databaseName: String,
        databaseNameChanged: Instant?,
        databaseDescription: String,
        databaseDescriptionChanged: Instant?,
        recycleBinEnabled: Boolean,
        recycleBinUuid: KdbxUuid?,
        recycleBinChanged: Instant?,
        entryTemplatesGroup: KdbxUuid?,
        entryTemplatesGroupChanged: Instant?,
        historyMaxItems: Int,
        historyMaxSize: Long,
        lastSelectedGroup: KdbxUuid?,
        lastTopVisibleGroup: KdbxUuid?,
        memoryProtection: MemoryProtectionConfig,
        customIcons: List<CustomIcon>,
        deletedObjects: List<DeletedObject>,
        customData: Map<String, String>
    ) {
        val metaElem = doc.createElement(KdbxConstants.Xml.META)
        rootElem.appendChild(metaElem)

        KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.GENERATOR, generator)
        KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.DATABASE_NAME, databaseName)
        if (databaseNameChanged != null) {
            KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.DATABASE_NAME_CHANGED, KdbxXmlTimeHelper.formatDate(databaseNameChanged))
        }
        KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.DATABASE_DESCRIPTION, databaseDescription)
        if (databaseDescriptionChanged != null) {
            KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.DATABASE_DESCRIPTION_CHANGED, KdbxXmlTimeHelper.formatDate(databaseDescriptionChanged))
        }

        KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.RECYCLE_BIN_ENABLED, if (recycleBinEnabled) "True" else "False")
        if (recycleBinUuid != null) {
            KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.RECYCLE_BIN_UUID, KdbxXmlDomUtil.encodeUuid(recycleBinUuid))
        }
        if (recycleBinChanged != null) {
            KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.RECYCLE_BIN_CHANGED, KdbxXmlTimeHelper.formatDate(recycleBinChanged))
        }

        if (entryTemplatesGroup != null) {
            KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.ENTRY_TEMPLATES_GROUP, KdbxXmlDomUtil.encodeUuid(entryTemplatesGroup))
        }
        if (entryTemplatesGroupChanged != null) {
            KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.ENTRY_TEMPLATES_GROUP_CHANGED, KdbxXmlTimeHelper.formatDate(entryTemplatesGroupChanged))
        }

        KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.HISTORY_MAX_ITEMS, historyMaxItems.toString())
        KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.HISTORY_MAX_SIZE, historyMaxSize.toString())

        if (lastSelectedGroup != null) {
            KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.LAST_SELECTED_GROUP, KdbxXmlDomUtil.encodeUuid(lastSelectedGroup))
        }
        if (lastTopVisibleGroup != null) {
            KdbxXmlDomUtil.appendTextElement(doc, metaElem, KdbxConstants.Xml.LAST_TOP_VISIBLE_GROUP, KdbxXmlDomUtil.encodeUuid(lastTopVisibleGroup))
        }

        // MemoryProtection
        val memProtElem = doc.createElement(KdbxConstants.Xml.MEMORY_PROTECTION)
        metaElem.appendChild(memProtElem)
        KdbxXmlDomUtil.appendTextElement(doc, memProtElem, KdbxConstants.Xml.PROTECT_TITLE, if (memoryProtection.protectTitle) "True" else "False")
        KdbxXmlDomUtil.appendTextElement(doc, memProtElem, KdbxConstants.Xml.PROTECT_USER_NAME, if (memoryProtection.protectUserName) "True" else "False")
        KdbxXmlDomUtil.appendTextElement(doc, memProtElem, KdbxConstants.Xml.PROTECT_PASSWORD, if (memoryProtection.protectPassword) "True" else "False")
        KdbxXmlDomUtil.appendTextElement(doc, memProtElem, KdbxConstants.Xml.PROTECT_URL, if (memoryProtection.protectUrl) "True" else "False")
        KdbxXmlDomUtil.appendTextElement(doc, memProtElem, KdbxConstants.Xml.PROTECT_NOTES, if (memoryProtection.protectNotes) "True" else "False")

        // CustomIcons
        if (customIcons.isNotEmpty()) {
            val customIconsElem = doc.createElement(KdbxConstants.Xml.CUSTOM_ICONS)
            metaElem.appendChild(customIconsElem)
            for (icon in customIcons) {
                val iconElem = doc.createElement(KdbxConstants.Xml.ICON)
                customIconsElem.appendChild(iconElem)
                KdbxXmlDomUtil.appendTextElement(doc, iconElem, KdbxConstants.Xml.UUID, KdbxXmlDomUtil.encodeUuid(icon.uuid))
                KdbxXmlDomUtil.appendTextElement(doc, iconElem, KdbxConstants.Xml.DATA, Base64.getEncoder().encodeToString(icon.data))
            }
        }

        // DeletedObjects
        if (deletedObjects.isNotEmpty()) {
            val delObjsElem = doc.createElement(KdbxConstants.Xml.DELETED_OBJECTS)
            metaElem.appendChild(delObjsElem)
            for (del in deletedObjects) {
                val delElem = doc.createElement(KdbxConstants.Xml.DELETED_OBJECT)
                delObjsElem.appendChild(delElem)
                KdbxXmlDomUtil.appendTextElement(doc, delElem, KdbxConstants.Xml.UUID, KdbxXmlDomUtil.encodeUuid(del.id))
                KdbxXmlDomUtil.appendTextElement(doc, delElem, KdbxConstants.Xml.DELETION_TIME, KdbxXmlTimeHelper.formatDate(del.deletionTime))
            }
        }

        // CustomData
        if (customData.isNotEmpty()) {
            val cdElem = doc.createElement(KdbxConstants.Xml.CUSTOM_DATA)
            metaElem.appendChild(cdElem)
            for ((key, value) in customData) {
                val itemElem = doc.createElement(KdbxConstants.Xml.ITEM)
                cdElem.appendChild(itemElem)
                KdbxXmlDomUtil.appendTextElement(doc, itemElem, KdbxConstants.Xml.KEY, key)
                KdbxXmlDomUtil.appendTextElement(doc, itemElem, KdbxConstants.Xml.VALUE, value)
            }
        }
    }
}
