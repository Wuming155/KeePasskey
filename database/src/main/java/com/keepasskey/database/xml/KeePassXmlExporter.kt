package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.database.file.KdbxDatabase
import java.io.ByteArrayOutputStream

/**
 * KeePass 2.x 兼容的明文 XML 导出器（TASK-13 整改：设置页「导出 XML」此前仅弹假提示）。
 *
 * 输出结构对齐官方 KeePass 2.x XML 导出格式（`KeePassFile > Meta + Root > Group/Entry`），
 * 可被 KeePass / KeePassXC 直接导入；仅写出最小必需集合（Generator / 分组树 / 条目字段），
 * 未实现的容器（回收站映射、CustomIcons、Attachments）按官方解析器的可缺省语义省略。
 *
 * 安全声明：导出为**明文**格式——受保护字段的明文按用户导出请求如实写出（不携带
 * `Protected="True"` 属性），导出文件不再受 KDBX 主密码保护；明文风险由导出确认
 * 对话框（`dbset_export_dialog_warning`）显式告知，调用方必须经 SAF 写到用户选定位置。
 */
object KeePassXmlExporter {

    fun export(database: KdbxDatabase): ByteArray {
        val buffer = ByteArrayOutputStream()
        val writer = KdbxXmlStreamWriter(buffer)
        writer.startDocument()
        writer.startElement("KeePassFile")
        writer.startElement("Meta")
        KdbxXmlWriteUtil.textElement(writer, "Generator", "KeePasskey")
        writer.endElement() // Meta
        writer.startElement("Root")
        serializeGroup(writer, database.rootGroup)
        writer.endElement() // Root
        writer.endElement() // KeePassFile
        writer.flush()
        return buffer.toByteArray()
    }

    private fun serializeGroup(writer: KdbxXmlStreamWriter, group: KdbxGroup) {
        writer.startElement("Group")
        KdbxXmlWriteUtil.textElement(writer, "UUID", KdbxXmlValueUtil.encodeUuid(group.id))
        KdbxXmlWriteUtil.textElement(writer, "Name", group.name)
        KdbxXmlWriteUtil.textElement(writer, "IconID", group.iconId.toString())
        if (group.notes.isNotBlank()) {
            KdbxXmlWriteUtil.textElement(writer, "Notes", group.notes)
        }
        if (group.tags.isNotEmpty()) {
            KdbxXmlWriteUtil.textElement(writer, "Tags", group.tags.joinToString(";"))
        }
        group.entries.forEach { serializeEntry(writer, it) }
        group.subgroups.forEach { serializeGroup(writer, it) }
        writer.endElement() // Group
    }

    private fun serializeEntry(writer: KdbxXmlStreamWriter, entry: KdbxEntry) {
        writer.startElement("Entry")
        KdbxXmlWriteUtil.textElement(writer, "UUID", KdbxXmlValueUtil.encodeUuid(entry.id))
        KdbxXmlWriteUtil.textElement(writer, "IconID", entry.iconId.toString())
        if (entry.tags.isNotEmpty()) {
            KdbxXmlWriteUtil.textElement(writer, "Tags", entry.tags.joinToString(";"))
        }

        writer.startElement("String")
        KdbxXmlWriteUtil.textElement(writer, "Key", KdbxConstants.Fields.TITLE)
        KdbxXmlWriteUtil.textElement(writer, "Value", entry.title)
        writer.endElement()
        writer.startElement("String")
        KdbxXmlWriteUtil.textElement(writer, "Key", KdbxConstants.Fields.USER_NAME)
        KdbxXmlWriteUtil.textElement(writer, "Value", entry.userName)
        writer.endElement()

        entry.password?.let { password ->
            writer.startElement("String")
            KdbxXmlWriteUtil.textElement(writer, "Key", KdbxConstants.Fields.PASSWORD)
            // 明文导出语义：不携带 Protected 属性，明文如实写出（见类 KDoc 安全声明）
            KdbxXmlWriteUtil.textElement(writer, "Value", password.readString())
            writer.endElement()
        }

        writer.startElement("String")
        KdbxXmlWriteUtil.textElement(writer, "Key", KdbxConstants.Fields.URL)
        KdbxXmlWriteUtil.textElement(writer, "Value", entry.url)
        writer.endElement()
        writer.startElement("String")
        KdbxXmlWriteUtil.textElement(writer, "Key", KdbxConstants.Fields.NOTES)
        KdbxXmlWriteUtil.textElement(writer, "Value", entry.notes)
        writer.endElement()

        entry.customFields.forEach { field ->
            writer.startElement("String")
            KdbxXmlWriteUtil.textElement(writer, "Key", field.key)
            KdbxXmlWriteUtil.textElement(writer, "Value", field.value.readString())
            writer.endElement()
        }

        writer.endElement() // Entry
    }
}
