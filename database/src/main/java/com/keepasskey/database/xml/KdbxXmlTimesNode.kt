package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxTimes
import org.xml.sax.Attributes

/**
 * <Times> 子树（Group / Entry 共用）。
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
