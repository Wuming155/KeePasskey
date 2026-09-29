package com.keepasskey.core.otp

/**
 * TOTP URI 字节编解码辅助（ISSUE-P2-289 / P3-305 拆分续）。
 * 自 [TotpKeyUriParser] 按单一职责拆出（行数门禁）。
 */
internal object TotpUriTextCodec {

    const val BYTE_SPACE = 0x20
    const val BYTE_TAB = 0x09
    const val BYTE_LF = 0x0A
    const val BYTE_VT = 0x0B
    const val BYTE_FF = 0x0C
    const val BYTE_CR = 0x0D
    const val BYTE_EQUALS = 0x3D
    const val BYTE_PERCENT = 0x25
    const val BYTE_AMPERSAND = 0x26
    const val BYTE_QUESTION = 0x3F
    const val BYTE_SLASH = 0x2F
    const val BYTE_COLON = 0x3A

    const val ASCII_CASE_OFFSET = 32
    const val CHAR_LOWER_A = 0x61
    const val CHAR_LOWER_Z = 0x7A
    const val CHAR_UPPER_A = 0x41
    const val CHAR_UPPER_Z = 0x5A
    const val CHAR_TWO = 0x32
    const val CHAR_SEVEN = 0x37

    /**
     * 百分号解码（`%XX` → 对应字节；非法 `%` 序列按字面量原样保留）。
     * 返回全新数组；调用方对含种子语义的产物承担擦除义务。
     */
    fun percentDecode(raw: ByteArray): ByteArray {
        val out = ByteArray(raw.size)
        var size = 0
        var i = 0
        while (i < raw.size) {
            val b = raw[i].toInt() and 0xFF
            if (b == BYTE_PERCENT && i + 2 <= raw.size - 1) {
                val hi = hexValue(raw[i + 1].toInt() and 0xFF)
                val lo = hexValue(raw[i + 2].toInt() and 0xFF)
                if (hi >= 0 && lo >= 0) {
                    out[size++] = ((hi shl 4) or lo).toByte()
                    i += 3
                    continue
                }
            }
            out[size++] = raw[i]
            i++
        }
        if (size == out.size) return out
        val result = out.copyOf(size)
        out.fill(0)
        return result
    }

    fun hexValue(value: Int): Int = when (value) {
        in 0x30..0x39 -> value - 0x30
        in 0x41..0x46 -> value - 0x41 + 10
        in 0x61..0x66 -> value - 0x61 + 10
        else -> -1
    }

    /** 去除空白与 '=' 填充并转大写 ASCII；返回全新数组 */
    fun normalizeBase32(raw: ByteArray): ByteArray {
        val buffer = ByteArray(raw.size)
        var size = 0
        for (b in raw) {
            val v = b.toInt() and 0xFF
            when {
                isAsciiWhitespace(v) || v == BYTE_EQUALS -> Unit
                v in CHAR_LOWER_A..CHAR_LOWER_Z -> buffer[size++] = (v - ASCII_CASE_OFFSET).toByte()
                else -> buffer[size++] = b
            }
        }
        if (size == buffer.size) return buffer
        val result = buffer.copyOf(size)
        buffer.fill(0)
        return result
    }

    fun isBase32Alphabet(bytes: ByteArray): Boolean {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (!(v in CHAR_UPPER_A..CHAR_UPPER_Z || v in CHAR_TWO..CHAR_SEVEN)) return false
        }
        return true
    }

    fun trimAsciiWhitespace(bytes: ByteArray): ByteArray {
        var start = 0
        var end = bytes.size
        while (start < end && isAsciiWhitespace(bytes[start].toInt() and 0xFF)) start++
        while (end > start && isAsciiWhitespace(bytes[end - 1].toInt() and 0xFF)) end--
        return bytes.copyOfRange(start, end)
    }

    fun isAsciiWhitespace(value: Int): Boolean =
        value == BYTE_SPACE || value == BYTE_TAB || value == BYTE_LF ||
            value == BYTE_VT || value == BYTE_FF || value == BYTE_CR

    fun indexOfByte(bytes: ByteArray, target: Int, from: Int = 0): Int {
        var i = from
        while (i < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) == target) return i
            i++
        }
        return -1
    }

    fun startsWithIgnoreCase(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (toUpperAscii(bytes[i]) != toUpperAscii(prefix[i])) return false
        }
        return true
    }

    fun toUpperAscii(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return if (v in CHAR_LOWER_A..CHAR_LOWER_Z) v - ASCII_CASE_OFFSET else v
    }

    fun queryValueString(query: ByteArray, start: Int, end: Int): String =
        String(percentDecode(query.copyOfRange(start, end)), Charsets.UTF_8)
}
