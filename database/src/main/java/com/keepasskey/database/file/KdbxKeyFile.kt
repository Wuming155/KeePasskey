package com.keepasskey.database.file

import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.database.exception.KdbxCorruptFileException
import java.util.Arrays

/**
 * 密钥文件密钥提取器（修复虚假开关整改：对齐 KeePass 官方密钥文件语义）。
 *
 * 从密钥文件原始字节中解析出 32 字节密钥（对齐 KeePass 2.x `CompositeKey.GetKeyFileData`
 * 与 KeePassXC / pykeepass `compute_key_composite` 的解析梯子）：
 * 1. XML KeyFile 格式（KeePass 2.x .keyx）：
 *    - `<Version>1.0</Version>`：`<Data>` 内容为 **Base64**，解码得 32 字节密钥；
 *    - `<Version>2.0</Version>`：`<Data>` 内容为**十六进制**，解码得 32 字节，
 *      且校验 `Hash` 属性（密钥 SHA-256 的前 4 字节，官方加载路径同样强制校验）；
 * 2. 恰为 32 字节的裸二进制文件：直接作为密钥使用；
 * 3. 去除空白后恰为 64 个十六进制字符的文本文件：解码为 32 字节；
 * 4. 其他任意文件：整文件 SHA-256（任意二进制密钥文件的历史语义）。
 *
 * 注意：[extractKey] 为确定性纯函数——同一密钥文件恒得同一密钥，
 * 读取侧与保存侧（经 [KdbxFile.deriveKeys]）使用同一解析路径保证复合密钥一致。
 */
internal object KdbxKeyFile {

    private const val KEY_LENGTH_BYTES = 32
    private const val HEX_KEY_TEXT_LENGTH = 64
    private const val XML_VERSION_1_0 = "1.0"
    private const val XML_VERSION_2_0 = "2.0"

    /**
     * 从密钥文件原始字节提取 32 字节密钥。
     * 声明为 XML KeyFile 但结构/内容非法时抛出 [KdbxCorruptFileException]，
     * 绝不静默回退为整文件哈希（否则用户以为在用密钥文件实际却用错密钥）。
     */
    fun extractKey(raw: ByteArray): ByteArray {
        if (raw.size == KEY_LENGTH_BYTES) {
            return raw.copyOf()
        }

        val text = raw.toString(Charsets.UTF_8)
        val trimmed = text.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<KeyFile")) {
            return extractFromXmlKeyFile(trimmed)
        }

        val compact = stripWhitespace(text)
        if (compact.length == HEX_KEY_TEXT_LENGTH && compact.all { isHexDigit(it) }) {
            return decodeHex(compact)
        }
        return HashUtil.sha256(raw)
    }

    /**
     * 解析 KeePass XML KeyFile 的 `<Version>` 与 `<Data>` 元素。
     * 仅做标签定位与内容解码，不引入完整 XML 解析器依赖：
     * KeyFile 结构受规范约束（单 `<Meta><Version>` + `<Key><Data>` 节点），
     * 定位式解析足够且天然规避 XXE 攻击面。
     */
    private fun extractFromXmlKeyFile(xml: String): ByteArray {
        val version = extractXmlElementText(xml, "Meta", "Version")
        val dataContent = extractXmlElementText(xml, "Key", "Data")
            ?: throw KdbxCorruptFileException("密钥文件格式无效: 缺少 <Data> 元素")
        val compact = stripWhitespace(dataContent)

        return when {
            version == null || version.startsWith(XML_VERSION_1_0) ->
                decodeBase64Key(compact)
            version.startsWith(XML_VERSION_2_0) ->
                decodeHexKeyWithHash(xml, compact)
            else ->
                throw KdbxCorruptFileException("密钥文件格式无效: 不支持的版本 [$version]")
        }
    }

    /** 提取 `<parent ...><child ...>文本</child></parent>` 的文本内容，找不到时返回 null */
    private fun extractXmlElementText(xml: String, parentTag: String, childTag: String): String? {
        val parentStart = xml.indexOf("<$parentTag")
        if (parentStart < 0) return null
        val childTagStart = xml.indexOf("<$childTag", parentStart)
        if (childTagStart < 0) return null
        val contentStart = xml.indexOf('>', childTagStart)
        val childTagEnd = xml.indexOf("</$childTag>", childTagStart)
        if (contentStart < 0 || childTagEnd < 0 || contentStart >= childTagEnd) {
            throw KdbxCorruptFileException("密钥文件格式无效: <$childTag> 元素结构不完整")
        }
        return xml.substring(contentStart + 1, childTagEnd)
    }

    /** v1.0：Data 为 Base64 编码的 32 字节密钥 */
    private fun decodeBase64Key(content: String): ByteArray {
        val key = runCatching { java.util.Base64.getDecoder().decode(content) }.getOrElse {
            throw KdbxCorruptFileException("密钥文件格式无效: v1.0 <Data> 不是合法 Base64", it)
        }
        if (key.size != KEY_LENGTH_BYTES) {
            throw KdbxCorruptFileException(
                "密钥文件格式无效: v1.0 解码密钥应为 $KEY_LENGTH_BYTES 字节（实际 ${key.size}）"
            )
        }
        return key
    }

    /** v2.0：Data 为十六进制编码的 32 字节密钥，`Hash` 属性 = 密钥 SHA-256 前 4 字节 */
    private fun decodeHexKeyWithHash(xml: String, compact: String): ByteArray {
        if (compact.length != HEX_KEY_TEXT_LENGTH || compact.any { !isHexDigit(it) }) {
            throw KdbxCorruptFileException(
                "密钥文件格式无效: v2.0 <Data> 应为 $HEX_KEY_TEXT_LENGTH 位十六进制（实际 ${compact.length} 字符）"
            )
        }
        val key = decodeHex(compact)

        val hashStart = xml.indexOf("Hash=")
        if (hashStart >= 0) {
            val hashHex = extractQuotedAttributeValue(xml, hashStart)
            if (hashHex != null) {
                val expected = runCatching { decodeHex(hashHex) }.getOrElse {
                    throw KdbxCorruptFileException("密钥文件格式无效: Hash 属性不是合法十六进制", it)
                }
                val actual = HashUtil.sha256(key)
                val mismatch = expected.size > actual.size ||
                        !Arrays.equals(expected, actual.copyOf(expected.size))
                Arrays.fill(actual, 0.toByte())
                if (mismatch) {
                    Arrays.fill(key, 0.toByte())
                    throw KdbxCorruptFileException("密钥文件格式无效: Hash 校验不符（文件已损坏或被篡改）")
                }
            }
        }
        return key
    }

    /** 提取 `Hash="..."` 形式的属性值，无引号或空值时返回 null（与官方一致：Hash 缺省不校验） */
    private fun extractQuotedAttributeValue(xml: String, valueStart: Int): String? {
        val open = xml.indexOf('"', valueStart)
        if (open < 0) return null
        val close = xml.indexOf('"', open + 1)
        if (close < 0) return null
        return xml.substring(open + 1, close).takeIf { it.isNotEmpty() }
    }

    private fun stripWhitespace(text: String): String = buildString(text.length) {
        for (ch in text) {
            if (!ch.isWhitespace()) append(ch)
        }
    }

    private fun isHexDigit(ch: Char): Boolean =
        (ch in '0'..'9') || (ch in 'a'..'f') || (ch in 'A'..'F')

    private fun decodeHex(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        val high = Character.digit(hex[i * 2], 16)
        val low = Character.digit(hex[i * 2 + 1], 16)
        check(high >= 0 && low >= 0) { "非法十六进制字符" }
        ((high shl 4) or low).toByte()
    }
}
