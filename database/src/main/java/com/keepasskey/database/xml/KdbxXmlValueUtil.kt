package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.database.exception.KdbxCorruptFileException
import java.util.Base64

/**
 * KDBX XML 值编解码通用助手（流式解析与序列化共用）。
 */
object KdbxXmlValueUtil {

    /**
     * 解码容忍空白的 Base64 文本（缺陷 D19 / P2）。
     *
     * 官方用 `.NET Convert.FromBase64String`（KdbxFile.Read.Streamed.cs:792），它**容忍**
     * 内部换行/空白（第三方写入者常按 76 列折行，`.NET XmlWriter.WriteBase64` 亦按块折行）。
     * `java.util.Base64.getDecoder()` 是严格基本解码器，遇任何空白即抛
     * `IllegalArgumentException` → 整个库被判损坏。
     *
     * **为何不使用 `Base64.getMimeDecoder()`**（经 JDK 21 实测）：MIME 解码器会**静默丢弃**
     * 一切非 Base64 字母表字符——`"!!!NOT_VALID_BASE64@@@"` 解出 10 字节、`"###CORRUPT###"`
     * 解出 5 字节而不抛异常。受保护值的字节数直接驱动内层流密码 keystream 位置，
     * 静默接受非法字符会解出错长明文并使**后续所有受保护字段永久错位**（保存即固化损坏）。
     * 因此这里只剥离空白（等价于「容忍换行」这一官方语义），其余非法字符一律交由
     * 严格基本解码器拒绝。
     *
     * @throws IllegalArgumentException 含非空白非法字符或长度非法时抛出；
     *         调用方须转换为 [KdbxCorruptFileException]，**严禁降级为原始字节**。
     */
    fun decodeBase64LenientWhitespace(text: String): ByteArray {
        val stripped = stripAsciiWhitespace(text)
        if (stripped.isEmpty()) return ByteArray(0)
        return Base64.getDecoder().decode(stripped)
    }

    /**
     * 剥离全部 ASCII 空白（空格 / 制表 / 换行 / 回车 / 换页 / 垂直制表）。
     *
     * 逐字符显式剔除而非 `replace(Regex, "")`：不引入正则引擎与平台正则差异
     * （ISSUE-P1-12 教训——正则/平台 API 的静态逻辑不能仅凭宿主单测判定在 Android 上可用），
     * 且无中间 String 配对分配。
     */
    private fun stripAsciiWhitespace(text: String): String {
        var needsStrip = false
        for (ch in text) {
            if (ch.isAsciiWhitespace()) {
                needsStrip = true
                break
            }
        }
        if (!needsStrip) return text

        val builder = StringBuilder(text.length)
        for (ch in text) {
            if (!ch.isAsciiWhitespace()) builder.append(ch)
        }
        return builder.toString()
    }

    private fun Char.isAsciiWhitespace(): Boolean =
        this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000B' || this == '\u000C'

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
