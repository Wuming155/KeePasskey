package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.database.exception.KdbxCorruptFileException
import java.util.Base64

/**
 * KDBX XML 值编解码通用助手（流式解析与序列化共用）。
 */
object KdbxXmlValueUtil {

    fun parseRequiredUuid(text: String?, context: String): KdbxUuid {
        if (text.isNullOrBlank()) {
            throw KdbxCorruptFileException("缺少必需的 UUID 节点 ($context)")
        }
        val clean = text.trim()
        return try {
            val bytes = Base64.getDecoder().decode(clean)
            if (bytes.size != 16) {
                throw KdbxCorruptFileException("UUID 字节长度非法: ${bytes.size}，期望 16 字节 ($context)")
            }
            KdbxUuid(bytes)
        } catch (e: IllegalArgumentException) {
            throw KdbxCorruptFileException("UUID Base64 编码损坏: $clean ($context)", e)
        }
    }

    fun parseOptionalUuid(text: String?): KdbxUuid? {
        if (text.isNullOrBlank()) return null
        val clean = text.trim()
        return try {
            val bytes = Base64.getDecoder().decode(clean)
            if (bytes.size != 16) {
                throw KdbxCorruptFileException("可选 UUID 字节长度非法: ${bytes.size}，期望 16 字节")
            }
            KdbxUuid(bytes)
        } catch (e: IllegalArgumentException) {
            throw KdbxCorruptFileException("可选 UUID Base64 编码损坏: $clean", e)
        }
    }

    fun encodeUuid(uuid: KdbxUuid): String {
        return Base64.getEncoder().encodeToString(uuid.toByteArray())
    }
}
