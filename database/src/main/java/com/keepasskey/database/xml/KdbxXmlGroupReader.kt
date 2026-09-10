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
import com.keepasskey.database.file.InnerHeader
import org.xml.sax.Attributes

/**
 * KDBX XML <Group> 节点流式解析节点（递归下降，含 <Entry> / <History> / <Times> / <AutoType> 子树）。
 * 字段缺省语义与原 DOM 解析逐一对齐；官方与主流实现均保证 UUID 先于子节点出现，
 * 因此子元素通过 [LateRef] 在闭合时取父 UUID。
 *
 * ISSUE-P3-25 拆分：本文件保留 <Group> / <Entry> / <History> 三条「条目-分组树」主干职责；
 * 叶级子树按职责拆出——[TimesNode]（KdbxXmlTimesNode.kt）、[StringNode]（KdbxXmlStringNode.kt）、
 * [BinaryNode]（KdbxXmlBinaryNode.kt）、[AutoTypeNode] / AssociationNode（KdbxXmlAutoTypeNode.kt）。
 * 全部为原样搬运，节点类名、可见性与 `package` 均不变（同包同模块）。
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
