package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.MemoryProtectionConfig
import java.time.Instant
import java.util.Base64

/**
 * KDBX XML <Meta> 节点序列化写出器（流式）。
 */
object KdbxXmlMetaSerializer {

    fun serialize(
        writer: KdbxXmlStreamWriter,
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
        writer.startElement(KdbxConstants.Xml.META)

        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.GENERATOR, generator)
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATABASE_NAME, databaseName)
        if (databaseNameChanged != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATABASE_NAME_CHANGED, KdbxXmlTimeHelper.formatDate(databaseNameChanged))
        }
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATABASE_DESCRIPTION, databaseDescription)
        if (databaseDescriptionChanged != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATABASE_DESCRIPTION_CHANGED, KdbxXmlTimeHelper.formatDate(databaseDescriptionChanged))
        }

        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.RECYCLE_BIN_ENABLED, if (recycleBinEnabled) "True" else "False")
        if (recycleBinUuid != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.RECYCLE_BIN_UUID, KdbxXmlValueUtil.encodeUuid(recycleBinUuid))
        }
        if (recycleBinChanged != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.RECYCLE_BIN_CHANGED, KdbxXmlTimeHelper.formatDate(recycleBinChanged))
        }

        if (entryTemplatesGroup != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.ENTRY_TEMPLATES_GROUP, KdbxXmlValueUtil.encodeUuid(entryTemplatesGroup))
        }
        if (entryTemplatesGroupChanged != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.ENTRY_TEMPLATES_GROUP_CHANGED, KdbxXmlTimeHelper.formatDate(entryTemplatesGroupChanged))
        }

        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.HISTORY_MAX_ITEMS, historyMaxItems.toString())
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.HISTORY_MAX_SIZE, historyMaxSize.toString())

        if (lastSelectedGroup != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.LAST_SELECTED_GROUP, KdbxXmlValueUtil.encodeUuid(lastSelectedGroup))
        }
        if (lastTopVisibleGroup != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.LAST_TOP_VISIBLE_GROUP, KdbxXmlValueUtil.encodeUuid(lastTopVisibleGroup))
        }

        serializeMemoryProtection(writer, memoryProtection)

        if (customIcons.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.CUSTOM_ICONS)
            for (icon in customIcons) {
                writer.startElement(KdbxConstants.Xml.ICON)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.UUID, KdbxXmlValueUtil.encodeUuid(icon.uuid))
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATA, Base64.getEncoder().encodeToString(icon.data))
                writer.endElement()
            }
            writer.endElement()
        }

        if (deletedObjects.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.DELETED_OBJECTS)
            for (del in deletedObjects) {
                writer.startElement(KdbxConstants.Xml.DELETED_OBJECT)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.UUID, KdbxXmlValueUtil.encodeUuid(del.id))
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DELETION_TIME, KdbxXmlTimeHelper.formatDate(del.deletionTime))
                writer.endElement()
            }
            writer.endElement()
        }

        if (customData.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.CUSTOM_DATA)
            for ((key, value) in customData) {
                writer.startElement(KdbxConstants.Xml.ITEM)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.KEY, key)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.VALUE, value)
                writer.endElement()
            }
            writer.endElement()
        }

        writer.endElement()
    }

    private fun serializeMemoryProtection(writer: KdbxXmlStreamWriter, memoryProtection: MemoryProtectionConfig) {
        writer.startElement(KdbxConstants.Xml.MEMORY_PROTECTION)
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PROTECT_TITLE, if (memoryProtection.protectTitle) "True" else "False")
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PROTECT_USER_NAME, if (memoryProtection.protectUserName) "True" else "False")
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PROTECT_PASSWORD, if (memoryProtection.protectPassword) "True" else "False")
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PROTECT_URL, if (memoryProtection.protectUrl) "True" else "False")
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PROTECT_NOTES, if (memoryProtection.protectNotes) "True" else "False")
        writer.endElement()
    }
}
