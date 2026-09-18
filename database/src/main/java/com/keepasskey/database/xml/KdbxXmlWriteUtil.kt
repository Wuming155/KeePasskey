package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxTimes

/**
 * KDBX XML 序列化写侧通用助手（流式）。
 */
object KdbxXmlWriteUtil {

    /**
     * 布尔元素规范字面量：官方 .NET 写出形态为**首字母大写** `True` / `False`
     * （读侧同样只认该精确形态，见 `KdbxXmlGroupReader.BOOL_TRUE`）。
     */
    internal const val XML_TRUE = "True"
    internal const val XML_FALSE = "False"

    /** 写出一个布尔元素（统一走 [XML_TRUE] / [XML_FALSE]，避免各处散落字面量） */
    fun boolElement(writer: KdbxXmlStreamWriter, tag: String, value: Boolean) {
        textElement(writer, tag, if (value) XML_TRUE else XML_FALSE)
    }

    fun textElement(writer: KdbxXmlStreamWriter, tag: String, text: String) {
        writer.startElement(tag)
        writer.text(text)
        writer.endElement()
    }

    fun optionalTextElement(writer: KdbxXmlStreamWriter, tag: String, text: String?) {
        if (text != null) {
            textElement(writer, tag, text)
        }
    }

    fun serializeTimes(writer: KdbxXmlStreamWriter, times: KdbxTimes) {
        writer.startElement(KdbxConstants.Xml.TIMES)
        textElement(writer, KdbxConstants.Xml.CREATION_TIME, KdbxXmlTimeHelper.formatDate(times.creationTime))
        textElement(writer, KdbxConstants.Xml.LAST_MODIFICATION_TIME, KdbxXmlTimeHelper.formatDate(times.lastModificationTime))
        textElement(writer, KdbxConstants.Xml.LAST_ACCESS_TIME, KdbxXmlTimeHelper.formatDate(times.lastAccessTime))
        textElement(writer, KdbxConstants.Xml.EXPIRY_TIME, KdbxXmlTimeHelper.formatDate(times.expiryTime))
        boolElement(writer, KdbxConstants.Xml.EXPIRES, times.expires)
        textElement(writer, KdbxConstants.Xml.USAGE_COUNT, times.usageCount.toString())
        textElement(writer, KdbxConstants.Xml.LOCATION_CHANGED, KdbxXmlTimeHelper.formatDate(times.locationChanged))
        writer.endElement()
    }
}
