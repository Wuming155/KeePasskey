package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.xml.sax.Attributes

/**
 * `<Meta><CustomIcons>` 子树解析（`ISSUE-P2-645`：自 `KdbxXmlMetaReader.kt` 拆分，纯搬运）。
 *
 * 官方语义：零 UUID 或缺少有效 `<Data>` 的图标**丢弃**（`KdbxFile.Read.Streamed.cs:613-623`），
 * 因此 `IconNode` 的回调参数为 `null` 表示该图标被跳过，而不是解析失败。
 */
/**
 * <CustomIcons> 子树。
 */
internal class CustomIconsNode(
    private val onDone: (List<CustomIcon>) -> Unit
) : SaxNode() {

    private val icons = mutableListOf<CustomIcon>()

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return if (name == KdbxConstants.Xml.ICON) {
            IconNode { icon -> if (icon != null) icons.add(icon) }
        } else {
            IgnoredNode()
        }
    }

    override fun end() {
        onDone(icons.toList())
    }
}

/**
 * `<CustomIcons><Icon>` 单图标节点。
 *
 * 按官方语义（`KdbxFile.Read.Streamed.cs:613-623`）**丢弃**零 UUID 或缺少有效 `<Data>` 的图标：
 * 官方仅在 `!uuid.IsZero && data != null` 时才把图标加入库图标池，其余情况仅断言后跳过。
 * 回调参数为 `null` 表示该图标被丢弃。
 *
 * [name] / [lastModificationTime] 为官方 KDBX 4.1 追加字段（`Read.Streamed.cs:312-315`），
 * 元素缺失时分别为空串与 `null`。
 */
private class IconNode(
    private val onDone: (CustomIcon?) -> Unit
) : SaxNode() {

    private var iconUuid: KdbxUuid? = null
    private var dataText: String? = null
    private var iconName = ""
    private var lastModificationTime: java.time.Instant? = null

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            // 官方 ReadUuid 对空内容返回 Zero（Read.Streamed.cs:855-858），故此处用可选解析：
            // 元素缺失/空文本 → null → 该图标被丢弃，而非抛错（官方同为静默跳过）。
            KdbxConstants.Xml.UUID -> TextNode { iconUuid = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.DATA -> TextNode { dataText = it }
            KdbxConstants.Xml.NAME -> TextNode { iconName = it }
            KdbxConstants.Xml.LAST_MODIFICATION_TIME -> TextNode { lastModificationTime = KdbxXmlTimeHelper.parseDate(it) }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        val uuid = iconUuid
        // 官方：Data 为空串时不置 m_pbCustomIconData（Read.Streamed.cs:308-310），等同于「无 data」
        val encodedData = dataText?.trim()?.takeIf { it.isNotEmpty() }
        if (uuid == null || uuid == KdbxUuid.ZERO || encodedData == null) {
            onDone(null)
            return
        }
        // 缺陷 D19 同族（ISSUE-P2-283）：自定义图标 <Data> 复用宽松解码器。
        // 官方 `Convert.FromBase64String` 容忍内部换行/空白（第三方写入者常按 76 列折行），
        // 严格 `Base64.getDecoder()` 会把合法库整库判损坏。解码器单点定义在
        // [KdbxXmlValueUtil.decodeBase64LenientWhitespace]，禁再造一份。
        val data = try {
            KdbxXmlValueUtil.decodeBase64LenientWhitespace(encodedData)
        } catch (e: IllegalArgumentException) {
            throw KdbxCorruptFileException("CustomIcon Data Base64 损坏", e)
        }
        onDone(CustomIcon(uuid, data, iconName, lastModificationTime))
    }
}
