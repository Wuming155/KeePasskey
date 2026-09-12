package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxTimes
import org.xml.sax.Attributes

/**
 * `<Times>` 子树（Group / Entry 共用）。
 *
 * 字段语义一一对齐官方 `KeePassLib/Serialization/KdbxFile.Read.Streamed.cs`
 * 的 `KdbContext.GroupTimes` / `EntryTimes` 上下文（:486-507）：
 * - 时间子元素缺失 → 取远古缺省 [KdbxXmlTimeHelper.ANCIENT_INSTANT]（0001-01-01T00:00:00Z），
 *   对应官方未初始化时间的 .NET `DateTime.MinValue`；**绝不取 now()**——
 *   否则缺 `<Times>` 的文件会被三方合并误判为「刚刚修改」而虚假覆盖对端；
 * - `<Expires>` 走官方 `ReadBool(xr, false)`（:501）：仅精确 `"True"` / `"False"` 合法，
 *   其余（含 `<Expires>1</Expires>`、空串、大小写变体）一律回落 `false`；
 * - `<UsageCount>` 走官方 `ReadULong(xr, 0)`（:503）的 TUInt64 防御性解析（非法/负数 → 0）。
 *
 * ISSUE-P3-25 拆分：原样搬出自 `KdbxXmlGroupReader.kt`（拆分前为 internal，同包同模块，可见性未变）。
 */
internal class TimesNode(
    private val onDone: (KdbxTimes) -> Unit
) : SaxNode() {

    private var creationTime: String? = null
    private var lastModificationTime: String? = null
    private var lastAccessTime: String? = null
    private var expiryTime: String? = null
    private var expires: Boolean = DEFAULT_EXPIRES
    private var usageCount: Long = DEFAULT_USAGE_COUNT
    private var locationChanged: String? = null

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.CREATION_TIME -> TextNode { creationTime = it }
            KdbxConstants.Xml.LAST_MODIFICATION_TIME -> TextNode { lastModificationTime = it }
            KdbxConstants.Xml.LAST_ACCESS_TIME -> TextNode { lastAccessTime = it }
            KdbxConstants.Xml.EXPIRY_TIME -> TextNode { expiryTime = it }
            KdbxConstants.Xml.EXPIRES -> TextNode {
                expires = KdbxXmlScalarParsers.parseBool(it, DEFAULT_EXPIRES)
            }
            KdbxConstants.Xml.USAGE_COUNT -> TextNode {
                usageCount = KdbxXmlScalarParsers.parseUsageCountOrZero(it)
            }
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

    private companion object {
        /** 官方 `tl.Expires = ReadBool(xr, false)`（KdbxFile.Read.Streamed.cs:501）。 */
        const val DEFAULT_EXPIRES = false

        /** 官方 `tl.UsageCount = ReadULong(xr, 0)`（KdbxFile.Read.Streamed.cs:503）。 */
        const val DEFAULT_USAGE_COUNT = 0L
    }
}
