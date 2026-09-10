package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.file.InnerHeader
import org.xml.sax.Attributes
import java.util.Base64

/**
 * KDBX XML <Group> 节点流式解析节点（递归下降，含 <Entry> / <History> / <Times> / <AutoType> 子树）。
 * 字段缺省语义与原 DOM 解析逐一对齐；官方与主流实现均保证 UUID 先于子节点出现，
 * 因此子元素通过 [LateRef] 在闭合时取父 UUID。
 */
internal class GroupNode(
    private val parentGroupIdRef: LateRef<KdbxUuid>?,
    private val innerStreamCipher: InnerRandomStreamCipher?,
    private val binariesPool: List<InnerHeader.BinaryItem>,
    private val onDone: (KdbxGroup) -> Unit
) : SaxNode() {

    private val selfUuid = LateRef<KdbxUuid>()
    private var name = ""
    private var notes = ""
    private var iconId: Int? = null
    private var customIconId: KdbxUuid? = null
    private var times: KdbxTimes? = null
    private var isExpanded = true
    private var defaultAutoTypeSequence = ""
    private var enableAutoType: Boolean? = null
    private var enableSearching: Boolean? = null
    private var lastTopVisibleEntry: KdbxUuid? = null
    private var previousParentGroup: KdbxUuid? = null
    private var tagsStr: String? = null
    private val customData = mutableMapOf<String, String>()
    private val entries = mutableListOf<KdbxEntry>()
    private val subgroups = mutableListOf<KdbxGroup>()

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.UUID -> TextNode { selfUuid.value = KdbxXmlValueUtil.parseRequiredUuid(it, "Group") }
            KdbxConstants.Xml.NAME -> TextNode { this.name = it }
            KdbxConstants.Xml.NOTES -> TextNode { this.notes = it }
            KdbxConstants.Xml.ICON_ID -> TextNode { iconId = it.trim().toIntOrNull() ?: 48 }
            KdbxConstants.Xml.CUSTOM_ICON_UUID -> TextNode { customIconId = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.TIMES -> TimesNode { times = it }
            KdbxConstants.Xml.IS_EXPANDED -> TextNode { isExpanded = it.lowercase() != "false" }
            KdbxConstants.Xml.DEFAULT_AUTO_TYPE_SEQUENCE -> TextNode { defaultAutoTypeSequence = it }
            KdbxConstants.Xml.ENABLE_AUTO_TYPE -> TextNode { enableAutoType = parseNullableBoolean(it) }
            KdbxConstants.Xml.ENABLE_SEARCHING -> TextNode { enableSearching = parseNullableBoolean(it) }
            KdbxConstants.Xml.LAST_TOP_VISIBLE_ENTRY -> TextNode { lastTopVisibleEntry = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.PREVIOUS_PARENT_GROUP -> TextNode { previousParentGroup = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.TAGS -> TextNode { tagsStr = it }
            KdbxConstants.Xml.CUSTOM_DATA -> CustomDataItemsNode { customData.putAll(it) }
            KdbxConstants.Xml.ENTRY -> EntryNode(selfUuid, innerStreamCipher, binariesPool) { entries.add(it) }
            KdbxConstants.Xml.GROUP -> GroupNode(selfUuid, innerStreamCipher, binariesPool) { subgroups.add(it) }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        // 标签解析语义与条目 <Tags> 一致（分号分隔 + trim + 去空）
        val tags = tagsStr?.split(";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        onDone(
            KdbxGroup(
                id = requireUuid(selfUuid.value, "Group"),
                parentGroupId = parentGroupIdRef?.value,
                name = name,
                notes = notes,
                iconId = iconId ?: 48,
                customIconId = customIconId,
                times = times ?: KdbxTimes(),
                isExpanded = isExpanded,
                defaultAutoTypeSequence = defaultAutoTypeSequence,
                enableAutoType = enableAutoType,
                enableSearching = enableSearching,
                lastTopVisibleEntry = lastTopVisibleEntry,
                previousParentGroup = previousParentGroup,
                tags = tags,
                customData = customData.toMap(),
                entries = entries.toList(),
                subgroups = subgroups.toList()
            )
        )
    }

    private fun parseNullableBoolean(str: String): Boolean? {
        if (str.isBlank() || str.equals("null", ignoreCase = true)) return null
        return str.equals("true", ignoreCase = true)
    }
}

/**
 * <Times> 子树（Group / Entry 共用）。
 */
internal class TimesNode(
    private val onDone: (KdbxTimes) -> Unit
) : SaxNode() {

    private var creationTime: String? = null
    private var lastModificationTime: String? = null
    private var lastAccessTime: String? = null
    private var expiryTime: String? = null
    private var expires: Boolean = false
    private var usageCount: Long = 0L
    private var locationChanged: String? = null

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.CREATION_TIME -> TextNode { creationTime = it }
            KdbxConstants.Xml.LAST_MODIFICATION_TIME -> TextNode { lastModificationTime = it }
            KdbxConstants.Xml.LAST_ACCESS_TIME -> TextNode { lastAccessTime = it }
            KdbxConstants.Xml.EXPIRY_TIME -> TextNode { expiryTime = it }
            KdbxConstants.Xml.EXPIRES -> TextNode { expires = it.lowercase() == "true" }
            KdbxConstants.Xml.USAGE_COUNT -> TextNode { usageCount = it.trim().toLongOrNull() ?: 0L }
            KdbxConstants.Xml.LOCATION_CHANGED -> TextNode { locationChanged = it }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        onDone(
            KdbxTimes(
                creationTime = KdbxXmlTimeHelper.parseDate(creationTime),
                lastModificationTime = KdbxXmlTimeHelper.parseDate(lastModificationTime),
                lastAccessTime = KdbxXmlTimeHelper.parseDate(lastAccessTime),
                expiryTime = KdbxXmlTimeHelper.parseDate(expiryTime),
                expires = expires,
                usageCount = usageCount,
                locationChanged = KdbxXmlTimeHelper.parseDate(locationChanged)
            )
        )
    }
}

/**
 * <Entry> 节点（历史条目复用同结构）。
 */
internal class EntryNode(
    private val parentGroupId: LateRef<KdbxUuid>,
    private val innerStreamCipher: InnerRandomStreamCipher?,
    private val binariesPool: List<InnerHeader.BinaryItem>,
    private val onDone: (KdbxEntry) -> Unit
) : SaxNode() {

    private var id: KdbxUuid? = null
    private var iconId: Int? = null
    private var customIconId: KdbxUuid? = null
    private var foregroundColor: String? = null
    private var backgroundColor: String? = null
    private var overrideUrl: String? = null
    private var qualityCheck = true
    private var previousParentGroup: KdbxUuid? = null
    private var tagsStr: String? = null
    private var times: KdbxTimes? = null
    private var autoType: KdbxAutoType? = null

    private val fields = mutableMapOf<String, ProtectedString>()
    private val customFields = mutableListOf<KdbxCustomField>()
    private val attachments = mutableListOf<KdbxAttachment>()
    private val customData = mutableMapOf<String, String>()
    private val history = mutableListOf<KdbxEntry>()

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.UUID -> TextNode { id = KdbxXmlValueUtil.parseRequiredUuid(it, "Entry") }
            KdbxConstants.Xml.ICON_ID -> TextNode { iconId = it.trim().toIntOrNull() ?: 0 }
            KdbxConstants.Xml.CUSTOM_ICON_UUID -> TextNode { customIconId = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.FOREGROUND_COLOR -> TextNode { foregroundColor = it }
            KdbxConstants.Xml.BACKGROUND_COLOR -> TextNode { backgroundColor = it }
            KdbxConstants.Xml.OVERRIDE_URL -> TextNode { overrideUrl = it }
            KdbxConstants.Xml.QUALITY_CHECK -> TextNode { qualityCheck = it.lowercase() != "false" }
            KdbxConstants.Xml.PREVIOUS_PARENT_GROUP -> TextNode { previousParentGroup = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.TAGS -> TextNode { tagsStr = it }
            KdbxConstants.Xml.TIMES -> TimesNode { times = it }
            KdbxConstants.Xml.STRING -> StringNode(innerStreamCipher) { key, value ->
                if (isStandardField(key)) {
                    fields[key] = value
                } else {
                    customFields.add(KdbxCustomField(key, value, value.isProtected))
                }
            }
            KdbxConstants.Xml.BINARY -> BinaryNode(binariesPool) { attachments.add(it) }
            KdbxConstants.Xml.AUTO_TYPE -> AutoTypeNode { autoType = it }
            KdbxConstants.Xml.CUSTOM_DATA -> CustomDataItemsNode { customData.putAll(it) }
            KdbxConstants.Xml.HISTORY -> HistoryNode(parentGroupId, innerStreamCipher, binariesPool) { history.add(it) }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        val tags = tagsStr?.split(";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        onDone(
            KdbxEntry(
                id = requireUuid(id, "Entry"),
                parentGroupId = parentGroupId.value,
                iconId = iconId ?: 0,
                customIconId = customIconId,
                foregroundColor = foregroundColor,
                backgroundColor = backgroundColor,
                overrideUrl = overrideUrl,
                qualityCheck = qualityCheck,
                previousParentGroup = previousParentGroup,
                fields = fields.toMap(),
                customFields = customFields.toList(),
                times = times ?: KdbxTimes(),
                history = history.toList(),
                tags = tags,
                attachments = attachments.toList(),
                autoType = autoType,
                customData = customData.toMap()
            )
        )
    }

    private fun isStandardField(key: String): Boolean {
        return key == KdbxConstants.Fields.TITLE ||
                key == KdbxConstants.Fields.USER_NAME ||
                key == KdbxConstants.Fields.PASSWORD ||
                key == KdbxConstants.Fields.URL ||
                key == KdbxConstants.Fields.NOTES
    }
}

/**
 * <String> 字段子树：<Key> 名称 + <Value Protected="..."> 内容，闭合时解密受保护值。
 */
private class StringNode(
    private val innerStreamCipher: InnerRandomStreamCipher?,
    private val onDone: (String, ProtectedString) -> Unit
) : SaxNode() {

    private var key = ""
    private var rawValue: String? = null
    private var isProtected = false

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.KEY -> TextNode { key = it }
            KdbxConstants.Xml.VALUE -> {
                isProtected = attrs.getValue(KdbxConstants.Xml.PROTECTED)?.lowercase() == "true"
                TextNode { rawValue = it }
            }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        val value = rawValue ?: return
        val protectedString = if (isProtected && innerStreamCipher != null) {
            // 受保护值在写入侧恒为合法 Base64（官方与本项目序列化器均单行编码），
            // 解码前先 trim() 去除 XML 缩进/换行空白。
            // 解码失败说明文件已损坏：严禁降级为 value.toByteArray()——其字节数与
            // Base64 解码结果不一致，会使内层流密码 keystream 错位，级联污染后续
            // 所有受保护字段的解密结果（全部变成乱码），必须立即中断解析。
            val decoded = try {
                Base64.getDecoder().decode(value.trim())
            } catch (e: IllegalArgumentException) {
                throw KdbxCorruptFileException("无法解码受保护字段 Base64 数据: key=$key", e)
            }
            val plainBytes = innerStreamCipher.processBytes(decoded)
            ProtectedString(isProtected = true, bytes = plainBytes)
        } else {
            ProtectedString(value, isProtected = isProtected)
        }
        onDone(key, protectedString)
    }
}

/**
 * <Binary> 附件子树：通过 <Value Ref="n"> 引用内层头二进制池。
 */
private class BinaryNode(
    private val binariesPool: List<InnerHeader.BinaryItem>,
    private val onDone: (KdbxAttachment) -> Unit
) : SaxNode() {

    private var key = ""
    private var refAttribute: String? = null
    private var rawValue: String? = null
    private var isProtected = false

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.KEY -> TextNode { key = it }
            KdbxConstants.Xml.VALUE -> {
                refAttribute = attrs.getValue(KdbxConstants.Xml.REF)
                isProtected = attrs.getValue(KdbxConstants.Xml.PROTECTED)?.lowercase() == "true"
                TextNode { rawValue = it }
            }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        if (refAttribute == null && rawValue == null) return
        val refStr = refAttribute?.takeIf { it.isNotEmpty() } ?: rawValue.orEmpty()
        val refIndex = refStr.trim().toIntOrNull() ?: 0
        // ISSUE-P3-07（P1-9 残余）：出边界交付给条目树的附件字节必须是独立副本。
        // 直接引用池内数组会使「同一池条目的多个引用者共享同一可变 ByteArray」，
        // 外部按 Closeable 契约 attachment.clear()/close() 即清零内层 Header 二进制池，
        // 连带损坏其他引用者与后续保存（去重指纹取自已清零数据）——副本化后池仍为唯一权威源。
        // 去重语义不受影响：保存侧按 flags + 字节内容指纹去重（KdbxBinaryDeduplicator），与实例身份无关。
        val data = if (refIndex in binariesPool.indices) {
            binariesPool[refIndex].data.copyOf()
        } else {
            ByteArray(0)
        }
        onDone(
            KdbxAttachment(
                name = key,
                refIndex = refIndex,
                isProtected = isProtected,
                data = data
            )
        )
    }
}

/**
 * <AutoType> 子树。
 */
private class AutoTypeNode(
    private val onDone: (KdbxAutoType) -> Unit
) : SaxNode() {

    private var enabled = true
    private var dataTransferObfuscation = 0
    private var defaultSequence = ""
    private val associations = mutableListOf<KdbxAutoType.AutoTypeAssociation>()

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.ENABLED -> TextNode { enabled = it.lowercase() != "false" }
            KdbxConstants.Xml.DATA_TRANSFER_OBFUSCATION -> TextNode { dataTransferObfuscation = it.trim().toIntOrNull() ?: 0 }
            KdbxConstants.Xml.DEFAULT_SEQUENCE -> TextNode { defaultSequence = it }
            KdbxConstants.Xml.ASSOCIATION -> AssociationNode { associations.add(it) }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        onDone(
            KdbxAutoType(
                enabled = enabled,
                dataTransferObfuscation = dataTransferObfuscation,
                defaultSequence = defaultSequence,
                associations = associations.toList()
            )
        )
    }
}

private class AssociationNode(
    private val onDone: (KdbxAutoType.AutoTypeAssociation) -> Unit
) : SaxNode() {

    private var window = ""
    private var keystrokeSequence = ""

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.WINDOW -> TextNode { window = it }
            KdbxConstants.Xml.KEYSTROKE_SEQUENCE -> TextNode { keystrokeSequence = it }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        onDone(KdbxAutoType.AutoTypeAssociation(window, keystrokeSequence))
    }
}

/**
 * <History> 子树：内嵌历史条目（历史条目不再递归嵌套历史，写出侧保证，读侧按原样接受）。
 */
internal class HistoryNode(
    private val parentGroupId: LateRef<KdbxUuid>,
    private val innerStreamCipher: InnerRandomStreamCipher?,
    private val binariesPool: List<InnerHeader.BinaryItem>,
    private val onDone: (KdbxEntry) -> Unit
) : SaxNode() {

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return if (name == KdbxConstants.Xml.ENTRY) {
            EntryNode(parentGroupId, innerStreamCipher, binariesPool) { onDone(it) }
        } else {
            IgnoredNode()
        }
    }
}
