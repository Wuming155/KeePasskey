package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.MemoryProtectionConfig
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import java.util.Base64

/**
 * KDBX XML <Entry> 节点序列化写出器（流式）。
 */
object KdbxXmlEntrySerializer {

    fun serialize(
        writer: KdbxXmlStreamWriter,
        entry: KdbxEntry,
        innerStreamCipher: InnerRandomStreamCipher?,
        isHistory: Boolean = false,
        /**
         * 数据库级内存保护配置（缺陷 D7）：官方写出侧对**标准五字段**以该配置为准
         * （`KdbxFile.Write.cs:838-854`），per-value 的 `IsProtected` 仅对非标准字段生效。
         * 默认空配置＝保持既有 per-value 行为，源码兼容既有调用点。
         */
        memoryProtection: MemoryProtectionConfig = MemoryProtectionConfig(),
        /**
         * 写出时二进制池的条目数：附件 `refIndex` 落在此范围外则改以内联 Base64 写出
         * （官方 `KdbxFile.Write.cs:930-939` 只有 Find 命中才写 `Ref`）。
         * 默认 0＝任何索引都视为池外，源码兼容既有调用点。
         */
        binaryPoolSize: Int = 0
    ) {
        writer.startElement(KdbxConstants.Xml.ENTRY)

        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.UUID, KdbxXmlValueUtil.encodeUuid(entry.id))
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.ICON_ID, entry.iconId.toString())

        entry.customIconId?.let {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.CUSTOM_ICON_UUID, KdbxXmlValueUtil.encodeUuid(it))
        }
        KdbxXmlWriteUtil.optionalTextElement(writer, KdbxConstants.Xml.FOREGROUND_COLOR, entry.foregroundColor)
        KdbxXmlWriteUtil.optionalTextElement(writer, KdbxConstants.Xml.BACKGROUND_COLOR, entry.backgroundColor)
        KdbxXmlWriteUtil.optionalTextElement(writer, KdbxConstants.Xml.OVERRIDE_URL, entry.overrideUrl)
        if (!entry.qualityCheck) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.QUALITY_CHECK, "False")
        }
        entry.previousParentGroup?.let {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.PREVIOUS_PARENT_GROUP, KdbxXmlValueUtil.encodeUuid(it))
        }
        if (entry.tags.isNotEmpty()) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.TAGS, entry.tags.joinToString("; "))
        }

        KdbxXmlWriteUtil.serializeTimes(writer, entry.times)

        for ((key, protectedString) in entry.fields) {
            serializeField(writer, key, protectedString, innerStreamCipher, memoryProtection)
        }
        for (cf in entry.customFields) {
            // 自定义字段无数据库级配置项：仅按 per-value 标志决定（MemoryProtectionConfig 查询恒 false）
            serializeField(writer, cf.key, cf.value, innerStreamCipher, memoryProtection)
        }

        entry.autoType?.let {
            serializeAutoType(writer, it)
        }

        for (att in entry.attachments) {
            serializeAttachment(writer, att, innerStreamCipher, binaryPoolSize)
        }

        if (entry.customData.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.CUSTOM_DATA)
            for ((k, v) in entry.customData) {
                writer.startElement(KdbxConstants.Xml.ITEM)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.KEY, k)
                KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.VALUE, v)
                writer.endElement()
            }
            writer.endElement()
        }

        if (!isHistory && entry.history.isNotEmpty()) {
            writer.startElement(KdbxConstants.Xml.HISTORY)
            for (histEntry in entry.history) {
                serialize(
                    writer,
                    histEntry,
                    innerStreamCipher,
                    isHistory = true,
                    memoryProtection = memoryProtection,
                    binaryPoolSize = binaryPoolSize
                )
            }
            writer.endElement()
        }

        writer.endElement()
    }

    /**
     * 写出 `<Binary>` 附件（缺陷 D17）。
     *
     * 官方写出侧 `KdbxFile.Write.cs:930-939`：能入池的附件**只写 `Ref`**，`Protected` 属性
     * 仅由 `SubWriteValue` 用于**内联值**分支（`:947-963`）。Ref 路径的池条目自带 flags
     * 保护标志，条目级 `Protected` 属性是非法组合（官方读取侧 `ReadProtectedBinary` 池命中
     * 分支根本不看该属性）。
     *
     * 只有当 `refIndex` **落在池外**（含 [BinaryNode.INLINE_REF_INDEX] 哨兵）时才走内联写出：
     * 正常保存路径由 [com.keepasskey.database.file.KdbxBinaryDeduplicator] 先把附件收编入池并
     * 回填合法索引，故内联分支只服务于「未经去重器直接调本序列化器」的场景；
     * 此时按官方 `SubWriteValue` 语义：受保护者 XOR + Base64，否则明文体 Base64。
     */
    private fun serializeAttachment(
        writer: KdbxXmlStreamWriter,
        att: KdbxAttachment,
        innerStreamCipher: InnerRandomStreamCipher?,
        binaryPoolSize: Int
    ) {
        writer.startElement(KdbxConstants.Xml.BINARY)
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.KEY, att.name)

        writer.startElement(KdbxConstants.Xml.VALUE)
        if (att.refIndex in 0 until binaryPoolSize) {
            writer.attribute(KdbxConstants.Xml.REF, att.refIndex.toString())
            // 缺陷 D17：Ref 路径不得写 Protected 属性（仅内联值路径可写）
        } else {
            writeInlineAttachmentValue(writer, att, innerStreamCipher)
        }
        writer.endElement()

        writer.endElement()
    }

    /** 内联附件值写出（官方 `SubWriteValue`，`KdbxFile.Write.cs:945-978`）。 */
    private fun writeInlineAttachmentValue(
        writer: KdbxXmlStreamWriter,
        att: KdbxAttachment,
        innerStreamCipher: InnerRandomStreamCipher?
    ) {
        // 所有权契约（务必先读 KdbxAttachment KDoc）：`att.data` 对**内存附件**返回的是附件自身
        // 持有的数组（非副本）——本方法只借用，**绝不**清零。原实现在 finally 里 `raw.fill(0)`，
        // 结果写出即把调用方的附件字节销毁（同一实例再次保存 / UI 读取全为 0 字节），
        // 并与 KdbxAttachment KDoc 声称的"独立副本"矛盾。
        // 受保护分支产生的密文由 processBytes **新建**且非明文，亦无需清零。
        val raw = att.data
        val payload = if (att.isProtected && innerStreamCipher != null) {
            // 写侧恒写规范字面量 "True"（官方 KdbxFile.Write.cs:949）
            writer.attribute(KdbxConstants.Xml.PROTECTED, PROTECTED_ATTR_TRUE)
            innerStreamCipher.processBytes(raw)
        } else {
            raw
        }
        if (payload.isNotEmpty()) {
            writer.text(Base64.getEncoder().encodeToString(payload))
        }
    }

    private fun serializeField(
        writer: KdbxXmlStreamWriter,
        key: String,
        value: ProtectedString,
        innerStreamCipher: InnerRandomStreamCipher?,
        memoryProtection: MemoryProtectionConfig
    ) {
        writer.startElement(KdbxConstants.Xml.STRING)
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.KEY, key)

        writer.startElement(KdbxConstants.Xml.VALUE)
        // 缺陷 D7：受保护标志的判定口径按官方 `KdbxFile.Write.cs:838-854` **分两类**，
        // 详见 [resolveProtectedFlag]。
        if (resolveProtectedFlag(key, value, memoryProtection)) {
            // 写侧恒写规范字面量 "True"（官方 KdbxFile.Write.cs:858 只写该形式）；
            // 读侧为大小写敏感精确比较，任何其它拼写都会使受保护值被当明文（缺陷 D4）。
            writer.attribute(KdbxConstants.Xml.PROTECTED, PROTECTED_ATTR_TRUE)
            val rawBytes = value.readUtf8()
            try {
                val encodedBytes = if (innerStreamCipher != null) {
                    innerStreamCipher.processBytes(rawBytes)
                } else {
                    rawBytes
                }
                writer.text(Base64.getEncoder().encodeToString(encodedBytes))
            } finally {
                rawBytes.fill(0)
            }
        } else {
            writer.text(value.readString())
        }
        writer.endElement()

        writer.endElement()
    }

    /**
     * 解析某字段是否应写出 `Protected="True"`——逐字对齐官方
     * `KdbxFile.Write.cs:838-854` 的**两类判定**，**不是**「per-value 或库级配置」的并集：
     *
     * ```csharp
     * bool bProtected = value.IsProtected;
     * if(bIsEntryString) {
     *     if(name == PwDefs.TitleField)         bProtected = m_pwDatabase.MemoryProtection.ProtectTitle;
     *     else if(name == PwDefs.UserNameField) bProtected = m_pwDatabase.MemoryProtection.ProtectUserName;
     *     else if(name == PwDefs.PasswordField) bProtected = m_pwDatabase.MemoryProtection.ProtectPassword;
     *     else if(name == PwDefs.UrlField)      bProtected = m_pwDatabase.MemoryProtection.ProtectUrl;
     *     else if(name == PwDefs.NotesField)    bProtected = m_pwDatabase.MemoryProtection.ProtectNotes;
     * }
     * ```
     *
     * 1. **标准五字段**（Title / UserName / Password / URL / Notes）→ 以**库级配置**为准，
     *    官方是**无条件覆盖** per-value 的 `IsProtected`（注意 `=` 而非 `|=`）。官方源码注释
     *    明说是为**归一化**「与数据库默认不一致的设置」（典型来源：导入时未给正确设置），
     *    因此「库级关闭 + per-value 开启」时官方**不写** `Protected`——这是有意行为，
     *    **请勿按直觉改回 `value.isProtected || config.isProtectEnabledFor(key)`**。
     * 2. **非标准字段 / 自定义字段** → `bIsEntryString == false`，官方保留 `value.IsProtected`；
     *    本仓用 [MemoryProtectionConfig.isProtectEnabledFor] 对非标准字段恒返回 false 作为
     *    「是否标准字段」的判据（两者取值同源，无需另建字段名集合）。
     */
    private fun resolveProtectedFlag(
        key: String,
        value: ProtectedString,
        memoryProtection: MemoryProtectionConfig
    ): Boolean {
        val configEnabled = memoryProtection.isProtectEnabledFor(key)
        return if (configEnabled) {
            // 标准字段且库级开启（无论 per-value 如何）
            true
        } else {
            // 库级未开启：可能是「标准字段但库级关闭」（官方覆盖为 false），
            // 也可能是「非标准字段」（官方保留 per-value）——按后者取值区分
            !isStandardField(key) && value.isProtected
        }
    }

    /** 官方标准五字段判定（`PwDefs.TitleField` / `UserNameField` / `PasswordField` / `UrlField` / `NotesField`）。 */
    private fun isStandardField(key: String): Boolean =
        key == KdbxConstants.Fields.TITLE ||
                key == KdbxConstants.Fields.USER_NAME ||
                key == KdbxConstants.Fields.PASSWORD ||
                key == KdbxConstants.Fields.URL ||
                key == KdbxConstants.Fields.NOTES

    private fun serializeAutoType(writer: KdbxXmlStreamWriter, autoType: KdbxAutoType) {
        writer.startElement(KdbxConstants.Xml.AUTO_TYPE)
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.ENABLED, if (autoType.enabled) "True" else "False")
        KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DATA_TRANSFER_OBFUSCATION, autoType.dataTransferObfuscation.toString())
        if (autoType.defaultSequence.isNotEmpty()) {
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.DEFAULT_SEQUENCE, autoType.defaultSequence)
        }
        for (assoc in autoType.associations) {
            writer.startElement(KdbxConstants.Xml.ASSOCIATION)
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.WINDOW, assoc.window)
            KdbxXmlWriteUtil.textElement(writer, KdbxConstants.Xml.KEYSTROKE_SEQUENCE, assoc.keystrokeSequence)
            writer.endElement()
        }
        writer.endElement()
    }
}
