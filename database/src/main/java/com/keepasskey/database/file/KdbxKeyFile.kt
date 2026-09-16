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
 *
 * ## ISSUE-P2-62（审计 H2）：全程**纯字节解析**，零不可擦 String
 *
 * 原实现把**整个密钥文件** `toString(Charsets.UTF_8)`（不可变、不可清零，寿命上界为下次 GC），
 * 且每次解锁尝试（含口令错误）与保存都会重放。现全部改为 `ByteArray` 上的标签定位与解码：
 * - Base64 / hex 直接在字节区间上解码（`Base64.getDecoder().decode(ByteArray)`）；
 * - 元素文本以字节区间 `[contentStart, childTagEnd)` 承载，压缩空白也在字节上进行；
 * - 版本号 / Hash 属性均为**非敏感标记**（版本常量与密钥摘要前缀），但仍按字节比较 / 解码，
 *   全路径不产生任何含密钥（编码形态）材料的 `String`。
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

        val headOffset = firstContentOffset(raw)
        if (startsWithAscii(raw, headOffset, "<?xml") || startsWithAscii(raw, headOffset, "<KeyFile")) {
            return extractFromXmlKeyFile(raw)
        }

        val compact = stripWhitespace(raw)
        if (compact.size == HEX_KEY_TEXT_LENGTH && compact.all { isHexDigit(it) }) {
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
    private fun extractFromXmlKeyFile(raw: ByteArray): ByteArray {
        val versionContent = extractXmlElementContent(raw, "Meta", "Version")
        val dataContent = extractXmlElementContent(raw, "Key", "Data")
            ?: throw KdbxCorruptFileException("密钥文件格式无效: 缺少 <Data> 元素")
        val compact = stripWhitespace(dataContent)

        return when {
            // 版本缺失或 v1.x → Base64；v2.x → hex + Hash 校验（与原 String 版语义逐字一致）
            versionContent == null || startsWithAscii(versionContent, 0, XML_VERSION_1_0) ->
                decodeBase64Key(compact)
            startsWithAscii(versionContent, 0, XML_VERSION_2_0) ->
                decodeHexKeyWithHash(raw, compact)
            else ->
                throw KdbxCorruptFileException(
                    "密钥文件格式无效: 不支持的版本 [${asciiPreview(versionContent)}]"
                )
        }
    }

    /**
     * 提取 `<parent ...><child ...>文本</child></parent>` 的文本内容（字节区间副本），
     * 找不到父/子标签时返回 null（与原 String 版语义一致）。
     */
    private fun extractXmlElementContent(raw: ByteArray, parentTag: String, childTag: String): ByteArray? {
        val parentStart = findAscii(raw, "<$parentTag", 0) ?: return null
        val childTagStart = findAscii(raw, "<$childTag", parentStart) ?: return null
        val contentStart = indexOfAsciiByte(raw, '>', childTagStart)
        val childTagEnd = findAscii(raw, "</$childTag>", childTagStart)
        if (contentStart < 0 || childTagEnd == null || contentStart >= childTagEnd) {
            throw KdbxCorruptFileException("密钥文件格式无效: <$childTag> 元素结构不完整")
        }
        return raw.copyOfRange(contentStart + 1, childTagEnd)
    }

    /** v1.0：Data 为 Base64 编码的 32 字节密钥（直接在字节上解码） */
    private fun decodeBase64Key(content: ByteArray): ByteArray {
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
    private fun decodeHexKeyWithHash(raw: ByteArray, compact: ByteArray): ByteArray {
        if (compact.size != HEX_KEY_TEXT_LENGTH || compact.any { !isHexDigit(it) }) {
            throw KdbxCorruptFileException(
                "密钥文件格式无效: v2.0 <Data> 应为 $HEX_KEY_TEXT_LENGTH 位十六进制（实际 ${compact.size} 字符）"
            )
        }
        val key = decodeHex(compact)

        val hashStart = findAscii(raw, "Hash=", 0)
        if (hashStart != null) {
            val hashHex = extractQuotedAttributeValue(raw, hashStart)
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

    /** 提取 `Hash="..."` 形式的属性值字节（hex 内容），无引号或空值时返回 null（与官方一致：Hash 缺省不校验） */
    private fun extractQuotedAttributeValue(raw: ByteArray, valueStart: Int): ByteArray? {
        val open = indexOfAsciiByte(raw, '"', valueStart)
        if (open < 0) return null
        val close = indexOfAsciiByte(raw, '"', open + 1)
        if (close < 0) return null
        return raw.copyOfRange(open + 1, close).takeIf { it.isNotEmpty() }
    }

    /** 首个「非 BOM / 非空白」字节偏移；全空白返回 `raw.size`（后续 startsWith 均不命中） */
    private fun firstContentOffset(raw: ByteArray): Int {
        var i = 0
        // UTF-8 BOM（EF BB BF）
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            i = 3
        }
        while (i < raw.size && raw[i].toInt().toChar().isWhitespace()) {
            i++
        }
        return i
    }

    /** 去除全部空白字节（含 BOM 已在定位阶段跳过；此处按原语义仅去空白） */
    private fun stripWhitespace(raw: ByteArray): ByteArray {
        val out = ByteArray(raw.size)
        var n = 0
        for (b in raw) {
            if (!b.toInt().toChar().isWhitespace()) {
                out[n++] = b
            }
        }
        return if (n == raw.size) out else out.copyOf(n)
    }

    /** 在 [raw] 自 [from] 起查找 ASCII 字节 [b]，找不到返回 -1 */
    private fun indexOfAsciiByte(raw: ByteArray, b: Char, from: Int): Int {
        var i = from
        while (i < raw.size) {
            if (raw[i].toInt() == b.code) return i
            i++
        }
        return -1
    }

    /** 在 [raw] 自 [from] 起查找 ASCII 串 [needle]，返回起始偏移或 null */
    private fun findAscii(raw: ByteArray, needle: String, from: Int): Int? {
        if (needle.isEmpty()) return from
        var i = maxOf(from, 0)
        val limit = raw.size - needle.length
        while (i <= limit) {
            if (raw[i].toInt() == needle[0].code && matchesAt(raw, i, needle)) return i
            i++
        }
        return null
    }

    private fun matchesAt(raw: ByteArray, offset: Int, needle: String): Boolean {
        for (j in needle.indices) {
            if (raw[offset + j].toInt() != needle[j].code) return false
        }
        return true
    }

    private fun startsWithAscii(raw: ByteArray, offset: Int, needle: String): Boolean =
        offset >= 0 && offset + needle.length <= raw.size && matchesAt(raw, offset, needle)

    /** 仅用于异常消息的版本预览（版本标记非敏感，不落密钥材料） */
    private fun asciiPreview(bytes: ByteArray): String {
        val sanitized = stripWhitespace(bytes).take(16)
        return buildString(sanitized.size) {
            for (b in sanitized) {
                val c = b.toInt().toChar()
                append(if (c in ' '..'~') c else '?')
            }
        }
    }

    private fun isHexDigit(b: Byte): Boolean {
        val c = b.toInt().toChar()
        return (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
    }

    private fun hexDigit(b: Byte): Int {
        return when (val c = b.toInt().toChar()) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }

    private fun decodeHex(hex: ByteArray): ByteArray = ByteArray(hex.size / 2) { i ->
        val high = hexDigit(hex[i * 2])
        val low = hexDigit(hex[i * 2 + 1])
        check(high >= 0 && low >= 0) { "非法十六进制字符" }
        ((high shl 4) or low).toByte()
    }
}
