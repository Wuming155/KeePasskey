package com.keepasskey.database.xml

import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.MemoryProtectionConfig
import java.util.Base64

/**
 * KDBX XML <Meta> 节点序列化写出器（流式）。
 * 元素顺序对齐官方 KeePass 2.61.1 `KdbxFile.Write.cs` 的 WriteMeta：
 * SettingsChanged 为 KDBX 4.1 追加字段，官方固定写在 CustomData 之后（文档末位）。
 *
 * 注意：`<DeletedObjects>` **不属于** Meta——官方把它写在 `<Root>` 作用域
 * （`KdbxFile.Write.cs:430`），由 [serializeDeletedObjects] 在根分组之后写出。
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
        // 官方仅在为 true 时写出（Write.cs:461-462）
        if (meta.masterKeyChangeForceOnce) {
            KdbxXmlWriteUtil.textElement(writer, XML_MASTER_KEY_CHANGE_FORCE_ONCE, "True")
        }

        serializeMemoryProtection(writer)

        if (meta.customIcons.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.CUSTOM_ICONS)
            for (icon in meta.customIcons) {
                writer.startElement(KdbxConstants.Xml.ICON)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.UUID, KdbxXmlValueUtil.encodeUuid(icon.uuid))
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATA, Base64.getEncoder().encodeToString(icon.data))
                // KDBX 4.1 追加字段（官方 WriteCustomIconList，Write.cs:697-703）：空名/无时间不写出
                if (icon.name.isNotEmpty()) {
                    KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.NAME, icon.name)
                }
                // 跨模块 public 属性不可 smart cast（Kotlin 契约：其它模块的属性可能被覆写），
                // 故先取局部不可变副本再判空
                val iconLastModificationTime = icon.lastModificationTime
                if (iconLastModificationTime != null) {
                    KdbxXmlWriteUtil.textElement(
                        writer,
                        KdbxConstants.Xml.LAST_MODIFICATION_TIME,
                        KdbxXmlTimeHelper.formatDate(iconLastModificationTime)
                    )
                }
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

        if (meta.customData.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.CUSTOM_DATA)
            for ((key, value) in meta.customData) {
                writer.startElement(KdbxConstants.Xml.ITEM)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.KEY, key)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.VALUE, value)
                // 官方元素顺序：Key → Value → LastModificationTime（Write.cs:808-825）
                val lastModificationTime = meta.customDataTimes[key]
                if (lastModificationTime != null) {
                    KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.LAST_MODIFICATION_TIME, KdbxXmlTimeHelper.formatDate(lastModificationTime))
                }
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

    /**
     * 写出根作用域的 `<DeletedObjects>` 墓碑列表。
     *
     * 官方位置（KeePass 2.61.1 `KdbxFile.Write.cs:430`）：位于 `<Root>` 之内、根 `<Group>` 之后，
     * 由 [KdbxXmlSerializer] 在根分组写出完毕后调用。墓碑写在 Meta 内属本仓历史缺陷（缺陷 1/D2）：
     * 它与 `<Meta>` 的其他字段一起被第三方客户端忽略或错误写入，导致删除条目跨客户端复活。
     *
     * 列表为空时**不写出**空元素（与官方 `WriteList` 一致：官方恒定写出，本仓保持既有「空则不写」口径，
     * 该口径对读取方等价，因为缺失与空列表同义）。
     */
    internal fun serializeDeletedObjects(
        writer: KdbxXmlStreamWriter,
        deletedObjects: List<DeletedObject>
    ) {
        if (deletedObjects.isEmpty()) return
        writer.startElement(KdbxConstants.Xml.DELETED_OBJECTS)
        for (del in deletedObjects) {
            writer.startElement(KdbxConstants.Xml.DELETED_OBJECT)
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.UUID, KdbxXmlValueUtil.encodeUuid(del.id))
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DELETION_TIME, KdbxXmlTimeHelper.formatDate(del.deletionTime))
            writer.endElement()
        }
        writer.endElement()
    }

    /**
     * 写出 `<MemoryProtection>`：**恒定写出官方默认组合**。
     *
     * 与读侧「装载收尾整对象重置为默认值」对称（官方 `KdbxFile.Read.cs:246-248`，
     * 注释 "to always use reasonable defaults"）：文件里的组合永远不会被采用，
     * 因此写侧也只写该默认值，避免把（可能被篡改的）保护策略回写传播给其他客户端。
     * 语义说明见 [MemoryProtectionConfig] 的 KDoc。
     */
    private fun serializeMemoryProtection(writer: KdbxXmlStreamWriter) {
        val memoryProtection = MemoryProtectionConfig()
        writer.startElement(KdbxConstants.Xml.MEMORY_PROTECTION)
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PROTECT_TITLE, if (memoryProtection.protectTitle) "True" else "False")
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PROTECT_USER_NAME, if (memoryProtection.protectUserName) "True" else "False")
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PROTECT_PASSWORD, if (memoryProtection.protectPassword) "True" else "False")
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PROTECT_URL, if (memoryProtection.protectUrl) "True" else "False")
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PROTECT_NOTES, if (memoryProtection.protectNotes) "True" else "False")
        writer.endElement()
    }
}
