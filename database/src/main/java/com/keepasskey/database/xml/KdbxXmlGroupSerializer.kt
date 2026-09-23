package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.MemoryProtectionConfig
import com.keepasskey.crypto.stream.InnerRandomStreamCipher

/**
 * KDBX XML <Group> 节点序列化写出器（流式，对应官方 `Write.cs` 的 groupStack 流式遍历）。
 */
object KdbxXmlGroupSerializer {

    fun serialize(
        writer: KdbxXmlStreamWriter,
        group: KdbxGroup,
        innerStreamCipher: InnerRandomStreamCipher?,
        /** 数据库级内存保护配置（缺陷 D7），透传至条目写出器。 */
        memoryProtection: MemoryProtectionConfig = MemoryProtectionConfig(),
        /** 二进制池条目数（缺陷 D17 内联回退判定），透传至条目写出器。 */
        binaryPoolSize: Int = 0,
        /** ISSUE-P2-280 AC②：图标池成员集合（非 null 时校验 `CustomIconRef` 命中），透传至条目写出器。 */
        customIconPool: Set<KdbxUuid>? = null
    ) {
        writer.startElement(KdbxConstants.Xml.GROUP)

        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.UUID, KdbxXmlValueUtil.encodeUuid(group.id))
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.NAME, group.name)
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.NOTES, group.notes)
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.ICON_ID, group.iconId.toString())

        group.customIconId?.let {
            KdbxXmlEntrySerializer.requireIconRefResolvable(customIconPool, it, ownerKind = "分组", ownerId = group.id)
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.CUSTOM_ICON_UUID, KdbxXmlValueUtil.encodeUuid(it))
        }

        KdbxXmlWriteUtil.serializeTimes(writer, group.times)
        KdbxXmlWriteUtil.boolElement(writer, KdbxConstants.Xml.IS_EXPANDED, group.isExpanded)

        if (group.defaultAutoTypeSequence.isNotEmpty()) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DEFAULT_AUTO_TYPE_SEQUENCE, group.defaultAutoTypeSequence)
        }
        group.enableAutoType?.let {
            KdbxXmlWriteUtil.boolElement(writer, KdbxConstants.Xml.ENABLE_AUTO_TYPE, it)
        }
        group.enableSearching?.let {
            KdbxXmlWriteUtil.boolElement(writer, KdbxConstants.Xml.ENABLE_SEARCHING, it)
        }
        group.lastTopVisibleEntry?.let {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.LAST_TOP_VISIBLE_ENTRY, KdbxXmlValueUtil.encodeUuid(it))
        }
        group.previousParentGroup?.let {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PREVIOUS_PARENT_GROUP, KdbxXmlValueUtil.encodeUuid(it))
        }

        // 官方 Group 级 <Tags>（KeePass 2.51+，分号分隔，语义与条目 Tags 一致）
        if (group.tags.isNotEmpty()) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.TAGS, group.tags.joinToString("; "))
        }

        // 官方 Group 级 <CustomData>
        if (group.customData.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.CUSTOM_DATA)
            for ((key, value) in group.customData) {
                writer.startElement(KdbxConstants.Xml.ITEM)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.KEY, key)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.VALUE, value)
                writer.endElement()
            }
            writer.endElement()
        }

        for (entry in group.entries) {
            KdbxXmlEntrySerializer.serialize(
                writer,
                entry,
                innerStreamCipher,
                memoryProtection = memoryProtection,
                binaryPoolSize = binaryPoolSize,
                customIconPool = customIconPool
            )
        }

        for (subgroup in group.subgroups) {
            serialize(writer, subgroup, innerStreamCipher, memoryProtection, binaryPoolSize, customIconPool)
        }

        writer.endElement()
    }
}
