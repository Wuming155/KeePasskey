package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.MemoryProtectionConfig
import java.util.Base64

/**
 * KDBX XML <Meta> 节点序列化写出器（流式）。
 * 元素顺序对齐官方 KeePass 2.61.1 `KdbxFile.Write.cs` 的 WriteMeta：
 * SettingsChanged 为 KDBX 4.1 追加字段，官方固定写在 CustomData 之后（文档末位）。
 */
object KdbxXmlMetaSerializer {

    fun serialize(
        writer: KdbxXmlStreamWriter,
        meta: KdbxMetaData
    ) {
        writer.startElement(KdbxConstants.Xml.META)

        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.GENERATOR, meta.generator)
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATABASE_NAME, meta.databaseName)
        if (meta.databaseNameChanged != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATABASE_NAME_CHANGED, KdbxXmlTimeHelper.formatDate(meta.databaseNameChanged))
        }
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATABASE_DESCRIPTION, meta.databaseDescription)
        if (meta.databaseDescriptionChanged != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATABASE_DESCRIPTION_CHANGED, KdbxXmlTimeHelper.formatDate(meta.databaseDescriptionChanged))
        }

        // 官方 Meta 字段（P1-8 补齐：写出侧全量保留，读写往返零丢失）
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DEFAULT_USER_NAME, meta.defaultUserName)
        if (meta.defaultUserNameChanged != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DEFAULT_USER_NAME_CHANGED, KdbxXmlTimeHelper.formatDate(meta.defaultUserNameChanged))
        }
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.MAINTENANCE_HISTORY_DAYS, meta.maintenanceHistoryDays.toString())
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.COLOR, meta.color)
        if (meta.masterKeyChanged != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.MASTER_KEY_CHANGED, KdbxXmlTimeHelper.formatDate(meta.masterKeyChanged))
        }
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.MASTER_KEY_CHANGE_REC, meta.masterKeyChangeRec.toString())
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.MASTER_KEY_CHANGE_FORCE, meta.masterKeyChangeForce.toString())

        serializeMemoryProtection(writer, meta.memoryProtection)

        if (meta.customIcons.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.CUSTOM_ICONS)
            for (icon in meta.customIcons) {
                writer.startElement(KdbxConstants.Xml.ICON)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.UUID, KdbxXmlValueUtil.encodeUuid(icon.uuid))
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATA, Base64.getEncoder().encodeToString(icon.data))
                writer.endElement()
            }
            writer.endElement()
        }

        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.RECYCLE_BIN_ENABLED, if (meta.recycleBinEnabled) "True" else "False")
        if (meta.recycleBinUuid != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.RECYCLE_BIN_UUID, KdbxXmlValueUtil.encodeUuid(meta.recycleBinUuid))
        }
        if (meta.recycleBinChanged != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.RECYCLE_BIN_CHANGED, KdbxXmlTimeHelper.formatDate(meta.recycleBinChanged))
        }

        if (meta.entryTemplatesGroup != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.ENTRY_TEMPLATES_GROUP, KdbxXmlValueUtil.encodeUuid(meta.entryTemplatesGroup))
        }
        if (meta.entryTemplatesGroupChanged != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.ENTRY_TEMPLATES_GROUP_CHANGED, KdbxXmlTimeHelper.formatDate(meta.entryTemplatesGroupChanged))
        }

        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.HISTORY_MAX_ITEMS, meta.historyMaxItems.toString())
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.HISTORY_MAX_SIZE, meta.historyMaxSize.toString())

        if (meta.lastSelectedGroup != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.LAST_SELECTED_GROUP, KdbxXmlValueUtil.encodeUuid(meta.lastSelectedGroup))
        }
        if (meta.lastTopVisibleGroup != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.LAST_TOP_VISIBLE_GROUP, KdbxXmlValueUtil.encodeUuid(meta.lastTopVisibleGroup))
        }

        if (meta.deletedObjects.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.DELETED_OBJECTS)
            for (del in meta.deletedObjects) {
                writer.startElement(KdbxConstants.Xml.DELETED_OBJECT)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.UUID, KdbxXmlValueUtil.encodeUuid(del.id))
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DELETION_TIME, KdbxXmlTimeHelper.formatDate(del.deletionTime))
                writer.endElement()
            }
            writer.endElement()
        }

        if (meta.customData.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.CUSTOM_DATA)
            for ((key, value) in meta.customData) {
                writer.startElement(KdbxConstants.Xml.ITEM)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.KEY, key)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.VALUE, value)
                writer.endElement()
            }
            writer.endElement()
        }

        // KDBX 4.1 官方追加字段：位于 Meta 文档末位
        if (meta.settingsChanged != null) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.SETTINGS_CHANGED, KdbxXmlTimeHelper.formatDate(meta.settingsChanged))
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
