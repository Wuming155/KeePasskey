package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.MemoryProtectionConfig
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.w3c.dom.Element
import java.time.Instant
import java.util.Base64

/**
 * KDBX XML <Meta> 节点解析器。
 */
object KdbxXmlMetaParser {

    data class MetaData(
        val generator: String = "KeePasskey",
        val databaseName: String = "KeePass",
        val databaseNameChanged: Instant? = null,
        val databaseDescription: String = "",
        val databaseDescriptionChanged: Instant? = null,
        val recycleBinEnabled: Boolean = true,
        val recycleBinUuid: KdbxUuid? = null,
        val recycleBinChanged: Instant? = null,
        val entryTemplatesGroup: KdbxUuid? = null,
        val entryTemplatesGroupChanged: Instant? = null,
        val historyMaxItems: Int = 10,
        val historyMaxSize: Long = 6 * 1024 * 1024L,
        val lastSelectedGroup: KdbxUuid? = null,
        val lastTopVisibleGroup: KdbxUuid? = null,
        val memoryProtection: MemoryProtectionConfig = MemoryProtectionConfig(),
        val customIcons: List<CustomIcon> = emptyList(),
        val deletedObjects: List<DeletedObject> = emptyList(),
        val customData: Map<String, String> = emptyMap()
    )

    fun parse(metaElem: Element): MetaData {
        val generator = KdbxXmlDomUtil.getChildTextOrNull(metaElem, KdbxConstants.Xml.GENERATOR) ?: "KeePasskey"
        val dbName = KdbxXmlDomUtil.getChildText(metaElem, KdbxConstants.Xml.DATABASE_NAME)
        val dbNameChanged = KdbxXmlDomUtil.getChildTextOrNull(metaElem, KdbxConstants.Xml.DATABASE_NAME_CHANGED)?.let {
            KdbxXmlTimeHelper.parseDate(it)
        }
        val dbDesc = KdbxXmlDomUtil.getChildText(metaElem, KdbxConstants.Xml.DATABASE_DESCRIPTION)
        val dbDescChanged = KdbxXmlDomUtil.getChildTextOrNull(metaElem, KdbxConstants.Xml.DATABASE_DESCRIPTION_CHANGED)?.let {
            KdbxXmlTimeHelper.parseDate(it)
        }

        val recycleBinEnabled = KdbxXmlDomUtil.getChildTextOrNull(metaElem, KdbxConstants.Xml.RECYCLE_BIN_ENABLED)?.lowercase() != "false"
        val recycleBinUuid = KdbxXmlDomUtil.parseOptionalUuid(KdbxXmlDomUtil.getChildTextOrNull(metaElem, KdbxConstants.Xml.RECYCLE_BIN_UUID))
        val recycleBinChanged = KdbxXmlDomUtil.getChildTextOrNull(metaElem, KdbxConstants.Xml.RECYCLE_BIN_CHANGED)?.let {
            KdbxXmlTimeHelper.parseDate(it)
        }

        val entryTemplatesGroup = KdbxXmlDomUtil.parseOptionalUuid(KdbxXmlDomUtil.getChildTextOrNull(metaElem, KdbxConstants.Xml.ENTRY_TEMPLATES_GROUP))
        val entryTemplatesGroupChanged = KdbxXmlDomUtil.getChildTextOrNull(metaElem, KdbxConstants.Xml.ENTRY_TEMPLATES_GROUP_CHANGED)?.let {
            KdbxXmlTimeHelper.parseDate(it)
        }

        val historyMaxItems = KdbxXmlDomUtil.getChildTextOrNull(metaElem, KdbxConstants.Xml.HISTORY_MAX_ITEMS)?.toIntOrNull() ?: 10
        val historyMaxSize = KdbxXmlDomUtil.getChildTextOrNull(metaElem, KdbxConstants.Xml.HISTORY_MAX_SIZE)?.toLongOrNull() ?: (6 * 1024 * 1024L)

        val lastSelectedGroup = KdbxXmlDomUtil.parseOptionalUuid(KdbxXmlDomUtil.getChildTextOrNull(metaElem, KdbxConstants.Xml.LAST_SELECTED_GROUP))
        val lastTopVisibleGroup = KdbxXmlDomUtil.parseOptionalUuid(KdbxXmlDomUtil.getChildTextOrNull(metaElem, KdbxConstants.Xml.LAST_TOP_VISIBLE_GROUP))

        val memProtElem = KdbxXmlDomUtil.findFirstChildElement(metaElem, KdbxConstants.Xml.MEMORY_PROTECTION)
        val memoryProtection = if (memProtElem != null) {
            MemoryProtectionConfig(
                protectTitle = KdbxXmlDomUtil.getChildTextOrNull(memProtElem, KdbxConstants.Xml.PROTECT_TITLE)?.lowercase() == "true",
                protectUserName = KdbxXmlDomUtil.getChildTextOrNull(memProtElem, KdbxConstants.Xml.PROTECT_USER_NAME)?.lowercase() == "true",
                protectPassword = KdbxXmlDomUtil.getChildTextOrNull(memProtElem, KdbxConstants.Xml.PROTECT_PASSWORD)?.lowercase() != "false",
                protectUrl = KdbxXmlDomUtil.getChildTextOrNull(memProtElem, KdbxConstants.Xml.PROTECT_URL)?.lowercase() == "true",
                protectNotes = KdbxXmlDomUtil.getChildTextOrNull(memProtElem, KdbxConstants.Xml.PROTECT_NOTES)?.lowercase() == "true"
            )
        } else {
            MemoryProtectionConfig()
        }

        // CustomIcons
        val customIcons = mutableListOf<CustomIcon>()
        val customIconsElem = KdbxXmlDomUtil.findFirstChildElement(metaElem, KdbxConstants.Xml.CUSTOM_ICONS)
        if (customIconsElem != null) {
            val iconElems = KdbxXmlDomUtil.findChildElements(customIconsElem, KdbxConstants.Xml.ICON)
            for (iconElem in iconElems) {
                val uuidText = KdbxXmlDomUtil.getChildTextOrNull(iconElem, KdbxConstants.Xml.UUID)
                val iconUuid = KdbxXmlDomUtil.parseRequiredUuid(uuidText, "CustomIcon")
                val dataText = KdbxXmlDomUtil.getChildTextOrNull(iconElem, KdbxConstants.Xml.DATA)
                val iconData = if (!dataText.isNullOrBlank()) {
                    try {
                        Base64.getDecoder().decode(dataText.trim())
                    } catch (e: Exception) {
                        throw KdbxCorruptFileException("CustomIcon Data Base64 损坏", e)
                    }
                } else {
                    ByteArray(0)
                }
                customIcons.add(CustomIcon(iconUuid, iconData))
            }
        }

        // DeletedObjects
        val deletedObjects = mutableListOf<DeletedObject>()
        val deletedObjectsElem = KdbxXmlDomUtil.findFirstChildElement(metaElem, KdbxConstants.Xml.DELETED_OBJECTS)
        if (deletedObjectsElem != null) {
            val delElems = KdbxXmlDomUtil.findChildElements(deletedObjectsElem, KdbxConstants.Xml.DELETED_OBJECT)
            for (delElem in delElems) {
                val uuidText = KdbxXmlDomUtil.getChildTextOrNull(delElem, KdbxConstants.Xml.UUID)
                val delUuid = KdbxXmlDomUtil.parseRequiredUuid(uuidText, "DeletedObject")
                val timeText = KdbxXmlDomUtil.getChildTextOrNull(delElem, KdbxConstants.Xml.DELETION_TIME)
                val delTime = KdbxXmlTimeHelper.parseDate(timeText)
                deletedObjects.add(DeletedObject(delUuid, delTime))
            }
        }

        // CustomData
        val customData = mutableMapOf<String, String>()
        val customDataElem = KdbxXmlDomUtil.findFirstChildElement(metaElem, KdbxConstants.Xml.CUSTOM_DATA)
        if (customDataElem != null) {
            val itemElems = KdbxXmlDomUtil.findChildElements(customDataElem, KdbxConstants.Xml.ITEM)
            for (itemElem in itemElems) {
                val key = KdbxXmlDomUtil.getChildText(itemElem, KdbxConstants.Xml.KEY)
                val value = KdbxXmlDomUtil.getChildText(itemElem, KdbxConstants.Xml.VALUE)
                if (key.isNotEmpty()) {
                    customData[key] = value
                }
            }
        }

        return MetaData(
            generator = generator,
            databaseName = dbName,
            databaseNameChanged = dbNameChanged,
            databaseDescription = dbDesc,
            databaseDescriptionChanged = dbDescChanged,
            recycleBinEnabled = recycleBinEnabled,
            recycleBinUuid = recycleBinUuid,
            recycleBinChanged = recycleBinChanged,
            entryTemplatesGroup = entryTemplatesGroup,
            entryTemplatesGroupChanged = entryTemplatesGroupChanged,
            historyMaxItems = historyMaxItems,
            historyMaxSize = historyMaxSize,
            lastSelectedGroup = lastSelectedGroup,
            lastTopVisibleGroup = lastTopVisibleGroup,
            memoryProtection = memoryProtection,
            customIcons = customIcons,
            deletedObjects = deletedObjects,
            customData = customData
        )
    }
}
