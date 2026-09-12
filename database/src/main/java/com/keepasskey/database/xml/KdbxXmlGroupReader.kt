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

/** 官方 `PwIcon.Folder`（= 48）：Group 的 IconID 缺省值（`ReadIconId(xr, PwIcon.Folder)`，KdbxFile.Read.Streamed.cs:382）。 */
private const val DEFAULT_GROUP_ICON_ID = 48

/** 官方 `PwIcon.Key`（= 0）：Entry 的 IconID 缺省值（`ReadIconId(xr, PwIcon.Key)`，KdbxFile.Read.Streamed.cs:441）。 */
private const val DEFAULT_ENTRY_ICON_ID = 0

/** 官方 `IsExpanded = ReadBool(xr, true)`（KdbxFile.Read.Streamed.cs:388）。 */
private const val DEFAULT_IS_EXPANDED = true

/** 官方 `QualityCheck = ReadBool(xr, true)`（KdbxFile.Read.Streamed.cs:459）。 */
private const val DEFAULT_QUALITY_CHECK = true

/**
 * KDBX XML <Group> 节点流式解析节点（递归下降，含 <Entry> / <History> / <Times> / <AutoType> 子树）。
 * 字段缺省语义与原 DOM 解析逐一对齐；官方与主流实现均保证 UUID 先于子节点出现，
 * 因此子元素通过 [LateRef] 在闭合时取父 UUID。
 *
 * 布尔与数值字段的官方语义（KDBX 4 XSD 的 TBool / TNonNegativeInt / TUInt64）由
 * [KdbxXmlScalarParsers] 统一承接：布尔只认精确 `"True"` / `"False"`（非法值回落**各自字段**的
 * 官方默认值，而非统一的 false），可空布尔（EnableAutoType / EnableSearching）大小写不敏感且
 * 非法值回落 null（继承），IconID 越界钳到默认图标（组 48 / 条目 0），UsageCount 按无符号 64 位解析。
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
            KdbxConstants.Xml.ICON_ID -> TextNode { iconId = KdbxXmlScalarParsers.parseIconIdOrNull(it) }
            KdbxConstants.Xml.CUSTOM_ICON_UUID -> TextNode { customIconId = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.TIMES -> TimesNode { times = it }
            KdbxConstants.Xml.IS_EXPANDED -> TextNode {
                isExpanded = KdbxXmlScalarParsers.parseBool(it, DEFAULT_IS_EXPANDED)
            }
            KdbxConstants.Xml.DEFAULT_AUTO_TYPE_SEQUENCE -> TextNode { defaultAutoTypeSequence = it }
            KdbxConstants.Xml.ENABLE_AUTO_TYPE -> TextNode { enableAutoType = KdbxXmlScalarParsers.parseNullableBool(it) }
            KdbxConstants.Xml.ENABLE_SEARCHING -> TextNode { enableSearching = KdbxXmlScalarParsers.parseNullableBool(it) }
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
                iconId = iconId ?: DEFAULT_GROUP_ICON_ID,
                customIconId = customIconId,
                times = times ?: KdbxXmlTimeHelper.ancientTimes(),
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
            KdbxConstants.Xml.ICON_ID -> TextNode { iconId = KdbxXmlScalarParsers.parseIconIdOrNull(it) }
            KdbxConstants.Xml.CUSTOM_ICON_UUID -> TextNode { customIconId = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.FOREGROUND_COLOR -> TextNode { foregroundColor = it }
            KdbxConstants.Xml.BACKGROUND_COLOR -> TextNode { backgroundColor = it }
            KdbxConstants.Xml.OVERRIDE_URL -> TextNode { overrideUrl = it }
            KdbxConstants.Xml.QUALITY_CHECK -> TextNode {
                qualityCheck = KdbxXmlScalarParsers.parseBool(it, DEFAULT_QUALITY_CHECK)
            }
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
            KdbxConstants.Xml.BINARY -> BinaryNode(binariesPool, innerStreamCipher) { attachments.add(it) }
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
                iconId = iconId ?: DEFAULT_ENTRY_ICON_ID,
                customIconId = customIconId,
                foregroundColor = foregroundColor,
                backgroundColor = backgroundColor,
                overrideUrl = overrideUrl,
                qualityCheck = qualityCheck,
                previousParentGroup = previousParentGroup,
                fields = fields.toMap(),
                customFields = customFields.toList(),
                times = times ?: KdbxXmlTimeHelper.ancientTimes(),
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

/**
 * KDBX 4 XML 字段级标量语义解析（布尔 / 图标 ID / 无符号计数），供 [GroupNode] / [EntryNode] / [TimesNode] 共用。
 *
 * 官方裁决依据 `KeePassLib/Serialization/KdbxFile.Read.Streamed.cs`（逐行核对）：
 * - `ReadBool(xr, bDefault)`（:834-841）：**精确**比较 `"True"` / `"False"`；非法值（`1`、大小写变体、
 *   首尾空白、空串）回落**字段各自的默认值**——不是统一的 false，故调用方必须显式传入默认值；
 * - `ReadNullableBool(xr, obDefault)`（:844-853）：**大小写不敏感**，仅用于 `EnableAutoType`（:392）与
 *   `EnableSearching`（:394），默认 null（继承），`"Null"`/`"null"` 与一切非法值均回落 null；
 * - `ReadIconId(xr, icDefault)`（:949-956）：按 int 解析后要求落在 `[0, PwIcon.Count)`（即 0..68），
 *   越界或非法回落字段默认图标（组 `PwIcon.Folder` = 48，条目 `PwIcon.Key` = 0）；
 * - `ReadULong(xr, 0)`（:904-916）：`UsageCount` 为 TUInt64，非法值回落 0。
 *
 * 宽松度对齐 .NET：数值解析允许首尾空白与正号（`int.TryParse` / `ulong.TryParse` 的 NumberStyles.Integer）；
 * 布尔解析**不做 trim**（官方是裸字符串精确比较，故 `" True "` 属非法值）。
 *
 * 落位说明：本对象随 [GroupNode] 所在文件落位；后续若需 Meta / AutoType 读取器共用，
 * 建议迁入 `KdbxXmlValueUtil.kt`（那两处仍是旧语义，属跨文件整改项，不在本批次范围内）。
 */
internal object KdbxXmlScalarParsers {

    /** XSD TBool 的合法字面量（官方 `KdbxFile.ValTrue` / `ValFalse`）。 */
    private const val BOOL_TRUE = "True"
    private const val BOOL_FALSE = "False"

    /**
     * 官方 `PwIcon.Count`（KeePass 2.61 `PwEnums.cs`，:156）：合法图标 ID 为 `0..68`；
     * 69 是「图标总数」虚拟值，本身不是合法图标。
     */
    private const val ICON_COUNT = 69

    /** TUInt64 十进制上界字面量（等长数字串按字典序比较即数值比较）。 */
    private const val ULONG_MAX_DECIMAL = "18446744073709551615"

    /**
     * 官方 `ReadBool(xr, default)`：仅精确 `"True"` / `"False"` 合法，其余一律回落 [default]。
     * 不做 trim、不做大小写折叠（与官方裸字符串比较一致）。
     *
     * 于是 `<Expires>1</Expires>` → [default] = false（官方语义）；`<IsExpanded>1</IsExpanded>` →
     * [default] = true。
     */
    fun parseBool(text: String?, default: Boolean): Boolean = when (text) {
        BOOL_TRUE -> true
        BOOL_FALSE -> false
        else -> default
    }

    /**
     * 官方 `ReadNullableBool(xr, null)`（仅 EnableAutoType / EnableSearching）：
     * 大小写不敏感地识别 `"True"` / `"False"`；`"Null"` 与一切非法值（含空串、`1`）→ null（继承）。
     *
     * 于是 `<EnableAutoType>1</EnableAutoType>` → null（继承），而非 false。
     */
    fun parseNullableBool(text: String?): Boolean? = when {
        text == null -> null
        text.equals(BOOL_TRUE, ignoreCase = true) -> true
        text.equals(BOOL_FALSE, ignoreCase = true) -> false
        else -> null
    }

    /**
     * 官方 `ReadIconId(xr, icDefault)`：非法（非数字 / 溢出 / 负数）或越界（≥ 69）→ null，
     * 由调用方回落各自默认图标（组 48 / 条目 0）；合法值原样返回。
     */
    fun parseIconIdOrNull(text: String?): Int? {
        val value = text?.trim()?.toIntOrNull() ?: return null
        return value.takeIf { it in 0 until ICON_COUNT }
    }

    /**
     * 官方 `ReadULong(xr, 0)`（`UsageCount`，TUInt64）：非法（负号 / 非数字 / 超出 ulong 上界）→ 0。
     *
     * 模型侧以 [Long] 承载：落在 `(Long.MAX_VALUE, ULong.MAX_VALUE]` 的合法 ulong **饱和**为
     * [Long.MAX_VALUE]（保持非递减排序语义，绝不回绕成负数）；该区间在使用计数中不可能出现，
     * 饱和仅用于防止位模式被误读。
     */
    fun parseUsageCountOrZero(text: String?): Long {
        // NumberStyles.Integer 允许首尾空白与前导 '+'；无符号类型不接受 '-'（故负号天然非法）
        val unsigned = text?.trim().orEmpty().removePrefix("+")
        // 去掉前导零：避免「全零」与「大量前导零 + 小数值」被后续位数判断误杀
        val digits = unsigned.trimStart('0')
        if (digits.isEmpty()) return 0L
        if (digits.any { it < '0' || it > '9' }) return 0L
        if (digits.length > ULONG_MAX_DECIMAL.length) return 0L
        if (digits.length == ULONG_MAX_DECIMAL.length && digits > ULONG_MAX_DECIMAL) return 0L
        // 此处必然是可放入 ulong 的纯数字串；超出 Long 表示范围时饱和
        return digits.toLongOrNull() ?: Long.MAX_VALUE
    }
}
