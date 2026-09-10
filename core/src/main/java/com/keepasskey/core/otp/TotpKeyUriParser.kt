package com.keepasskey.core.otp

import java.nio.charset.StandardCharsets

/**
 * 解析后的 TOTP 配置模型。
 *
 * ISSUE-P2-12 整改：[secret] 由不可擦除的 String 改为 **Base32 文本字节（ASCII）**，
 * 归调用方所有，消费后须显式 fill(0) 擦除；解析层不再物化种子 String。
 */
class ParsedTotpConfig(
    val secret: ByteArray,
    val period: Int = 30,
    val digits: Int = 6,
    val algorithm: String = "SHA1",
    val issuer: String? = null,
    val account: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedTotpConfig) return false
        return secret.contentEquals(other.secret) &&
            period == other.period &&
            digits == other.digits &&
            algorithm == other.algorithm &&
            issuer == other.issuer &&
            account == other.account
    }

    override fun hashCode(): Int {
        var result = secret.contentHashCode()
        result = 31 * result + period
        result = 31 * result + digits
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + (issuer?.hashCode() ?: 0)
        result = 31 * result + (account?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        // 绝不输出种子内容，仅呈现长度（与 ProtectedString.toString 一致的安全约定）
        return "ParsedTotpConfig(secretLen=" + secret.size + ", period=" + period + ", digits=" + digits +
            ", algorithm=" + algorithm + ", issuer=" + issuer + ", account=" + account + ")"
    }
}

/**
 * TOTP KeyUri 解析器。
 * 支持标准 RFC 6238 KeyUri（如 otpauth://totp/Issuer:Account?secret=...&period=30&digits=6&algorithm=SHA1）
 * 以及纯 Base32 密钥格式。宽容解析缺省参数，非法输入返回 null。
 *
 * ISSUE-P2-12：解析全程字节语义——otpauth URI 与种子均不物化为 String，
 * 仅 label / issuer / account 等非敏感描述转字符串；种子以 ASCII Base32 字节承载。
 */
object TotpKeyUriParser {

    private const val DEFAULT_PERIOD = 30
    private const val DEFAULT_DIGITS = 6
    private const val DEFAULT_ALGORITHM = "SHA1"

    private const val KEY_SECRET = "secret"
    private const val KEY_PERIOD = "period"
    private const val KEY_DIGITS = "digits"
    private const val KEY_ALGORITHM = "algorithm"
    private const val KEY_ISSUER = "issuer"

    private const val ASCII_CASE_OFFSET = 32

    private const val BYTE_SPACE = 0x20
    private const val BYTE_TAB = 0x09
    private const val BYTE_LF = 0x0A
    private const val BYTE_VT = 0x0B
    private const val BYTE_FF = 0x0C
    private const val BYTE_CR = 0x0D
    private const val BYTE_EQUALS = 0x3D
    private const val BYTE_AMPERSAND = 0x26
    private const val BYTE_QUESTION = 0x3F
    private const val BYTE_SLASH = 0x2F

    private const val CHAR_LOWER_A = 0x61
    private const val CHAR_LOWER_Z = 0x7A
    private const val CHAR_UPPER_A = 0x41
    private const val CHAR_UPPER_Z = 0x5A
    private const val CHAR_TWO = 0x32
    private const val CHAR_SEVEN = 0x37

    private val OTPAUTH_PREFIX: ByteArray = "otpauth://".toByteArray(StandardCharsets.US_ASCII)

    /**
     * 字节语义解析入口（ISSUE-P2-12）：输入不物化为 String，种子全程以 ASCII 字节承载。
     * [uriOrSecret] 归调用方所有，本方法只读不写；返回配置的 [ParsedTotpConfig.secret]
     * 为**全新副本**，用毕须由调用方 fill(0) 擦除。
     */
    fun parse(uriOrSecret: ByteArray?): ParsedTotpConfig? {
        if (uriOrSecret == null || uriOrSecret.isEmpty()) return null
        val working = trimAsciiWhitespace(uriOrSecret)
        return try {
            when {
                working.isEmpty() -> null
                startsWithIgnoreCase(working, OTPAUTH_PREFIX) -> parseOtpAuthUri(working)
                else -> parsePlainBase32(working)
            }
        } finally {
            // 工作副本含种子字节，成功/失败路径一律擦除
            working.fill(0)
        }
    }

    /**
     * String 入口兼容层：内部转 UTF-8 字节后立即擦除。
     * 生产路径（VaultEntryMapper / RealVaultRepository）请直接使用 [parse] 的 ByteArray 重载，
     * 避免把种子固化为不可擦除的 String。
     */
    fun parse(uriOrSecret: String?): ParsedTotpConfig? {
        if (uriOrSecret.isNullOrBlank()) return null
        val bytes = uriOrSecret.toByteArray(StandardCharsets.UTF_8)
        return try {
            parse(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun parseOtpAuthUri(uri: ByteArray): ParsedTotpConfig? {
        // 形如 otpauth://<type>/<label>?<query>
        val slash = indexOfByte(uri, BYTE_SLASH, OTPAUTH_PREFIX.size)
        val restStart = if (slash >= 0) slash + 1 else uri.size
        val question = indexOfByte(uri, BYTE_QUESTION, restStart)
        val labelEnd = if (question >= 0) question else uri.size
        val label = String(uri, restStart, labelEnd - restStart, StandardCharsets.UTF_8)

        val query = if (question >= 0) uri.copyOfRange(question + 1, uri.size) else ByteArray(0)
        var secretRaw: ByteArray? = null
        var period = DEFAULT_PERIOD
        var digits = DEFAULT_DIGITS
        var algorithm = DEFAULT_ALGORITHM
        var issuerParam: String? = null

        try {
            var index = 0
            while (index <= query.size) {
                var nextAmp = indexOfByte(query, BYTE_AMPERSAND, index)
                if (nextAmp < 0) nextAmp = query.size
                val eq = indexOfByte(query, BYTE_EQUALS, index).takeIf { it in index until nextAmp }
                if (eq != null) {
                    val key = String(query, index, eq - index, StandardCharsets.UTF_8)
                    val valueStart = eq + 1
                    val valueLength = nextAmp - valueStart
                    when {
                        key.equals(KEY_SECRET, ignoreCase = true) ->
                            secretRaw = query.copyOfRange(valueStart, nextAmp)
                        key.equals(KEY_PERIOD, ignoreCase = true) ->
                            period = String(query, valueStart, valueLength, StandardCharsets.UTF_8)
                                .toIntOrNull() ?: DEFAULT_PERIOD
                        key.equals(KEY_DIGITS, ignoreCase = true) ->
                            digits = String(query, valueStart, valueLength, StandardCharsets.UTF_8)
                                .toIntOrNull() ?: DEFAULT_DIGITS
                        key.equals(KEY_ALGORITHM, ignoreCase = true) ->
                            algorithm = normalizeAlgorithm(
                                String(query, valueStart, valueLength, StandardCharsets.UTF_8)
                            )
                        key.equals(KEY_ISSUER, ignoreCase = true) ->
                            issuerParam = String(query, valueStart, valueLength, StandardCharsets.UTF_8)
                    }
                }
                if (nextAmp >= query.size) break
                index = nextAmp + 1
            }

            val rawSecret = secretRaw ?: return null
            val normalized = normalizeBase32(rawSecret)
            if (normalized.isEmpty()) {
                normalized.fill(0)
                return null
            }
            val account = if (label.contains(':')) label.substringAfter(':').trim() else label.trim()
            val issuer = (issuerParam ?: label.substringBefore(':')).trim()
            return ParsedTotpConfig(
                secret = normalized,
                period = if (period > 0) period else DEFAULT_PERIOD,
                digits = if (digits in 6..8) digits else DEFAULT_DIGITS,
                algorithm = algorithm,
                issuer = issuer.ifBlank { null },
                account = account.ifBlank { null }
            )
        } finally {
            query.fill(0)
            secretRaw?.fill(0)
        }
    }

    private fun parsePlainBase32(candidate: ByteArray): ParsedTotpConfig? {
        val clean = normalizeBase32(candidate)
        return try {
            if (clean.isEmpty() || !isBase32Alphabet(clean)) {
                null
            } else {
                ParsedTotpConfig(
                    secret = clean.copyOf(),
                    period = DEFAULT_PERIOD,
                    digits = DEFAULT_DIGITS,
                    algorithm = DEFAULT_ALGORITHM
                )
            }
        } finally {
            clean.fill(0)
        }
    }

    private fun normalizeAlgorithm(raw: String): String = when (raw.uppercase()) {
        "SHA256" -> "SHA256"
        "SHA512" -> "SHA512"
        else -> DEFAULT_ALGORITHM
    }

    /** 去除空白与 '=' 填充并转大写 ASCII；返回全新数组（原数组不修改） */
    private fun normalizeBase32(raw: ByteArray): ByteArray {
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

    private fun isBase32Alphabet(bytes: ByteArray): Boolean {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (!(v in CHAR_UPPER_A..CHAR_UPPER_Z || v in CHAR_TWO..CHAR_SEVEN)) return false
        }
        return true
    }

    private fun trimAsciiWhitespace(bytes: ByteArray): ByteArray {
        var start = 0
        var end = bytes.size
        while (start < end && isAsciiWhitespace(bytes[start].toInt() and 0xFF)) start++
        while (end > start && isAsciiWhitespace(bytes[end - 1].toInt() and 0xFF)) end--
        return bytes.copyOfRange(start, end)
    }

    private fun isAsciiWhitespace(value: Int): Boolean =
        value == BYTE_SPACE || value == BYTE_TAB || value == BYTE_LF ||
            value == BYTE_VT || value == BYTE_FF || value == BYTE_CR

    private fun indexOfByte(bytes: ByteArray, target: Int, from: Int = 0): Int {
        var i = from
        while (i < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) == target) return i
            i++
        }
        return -1
    }

    private fun startsWithIgnoreCase(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (toUpperAscii(bytes[i]) != toUpperAscii(prefix[i])) return false
        }
        return true
    }

    private fun toUpperAscii(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return if (v in CHAR_LOWER_A..CHAR_LOWER_Z) v - ASCII_CASE_OFFSET else v
    }
}
