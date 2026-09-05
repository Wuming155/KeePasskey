package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxTimes

/**
 * KDBX XML 序列化写侧通用助手（流式）。
 */
object KdbxXmlWriteUtil {

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
        textElement(writer, KdbxConstants.Xml.EXPIRES, if (times.expires) "True" else "False")
        textElement(writer, KdbxConstants.Xml.USAGE_COUNT, times.usageCount.toString())
        textElement(writer, KdbxConstants.Xml.LOCATION_CHANGED, KdbxXmlTimeHelper.formatDate(times.locationChanged))
        writer.endElement()
    }
}
