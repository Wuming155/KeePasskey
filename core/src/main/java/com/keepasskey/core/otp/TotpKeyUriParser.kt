package com.keepasskey.core.otp

import java.nio.charset.StandardCharsets

/**
 * TOTP KeyUri 解析器。
 * 支持标准 RFC 6238 KeyUri（如 otpauth://totp/Issuer:Account?secret=...&period=30&digits=6&algorithm=SHA1）
 * 以及纯 Base32 密钥格式。宽容解析缺省参数，非法输入返回 null。
 *
 * ISSUE-P2-12：解析全程字节语义——otpauth URI 与种子均不物化为 String，
 * 仅 label / issuer / account 等非敏感描述转字符串；种子以 ASCII Base32 字节承载。
 *
 * ISSUE-P3-305：产出模型 [ParsedTotpConfig] 已自本文件按**纯结构性拆分**搬出至同包同名文件；
 * 同时把原 103 行的 `parseOtpAuthUri` 内的查询段扫描拆为 [scanQueryParameters]（函数体逐行搬运）。
 */
object TotpKeyUriParser {

    private const val DEFAULT_PERIOD = 30
    private const val DEFAULT_DIGITS = 6
    private const val DEFAULT_ALGORITHM = "SHA1"

    /** ISSUE-P3-383：Steam Guard 算法标识（`steam://` 与 `algorithm=STEAM` 共用） */
    const val ALGORITHM_STEAM = "STEAM"
    private const val STEAM_DIGITS = 5
    private const val TYPE_STEAM = "steam"

    /** RFC 6238 / 官方实现共同接受的验证码位数区间（钳制上下界，ISSUE-P3-273 复用）。 */
    private const val DIGITS_MIN = 6
    private const val DIGITS_MAX = 8

    private const val KEY_SECRET = "secret"
    private const val KEY_PERIOD = "period"
    private const val KEY_DIGITS = "digits"
    private const val KEY_ALGORITHM = "algorithm"
    private const val KEY_ISSUER = "issuer"
    // ISSUE-P3-49：HOTP 计数器参数
    private const val KEY_COUNTER = "counter"
    private const val TYPE_HOTP = "hotp"

    private const val ASCII_CASE_OFFSET = 32

    private const val BYTE_SPACE = 0x20
    private const val BYTE_TAB = 0x09
    private const val BYTE_LF = 0x0A
    private const val BYTE_VT = 0x0B
    private const val BYTE_FF = 0x0C
    private const val BYTE_CR = 0x0D
    private const val BYTE_EQUALS = 0x3D
    private const val BYTE_PERCENT = 0x25
    private const val BYTE_AMPERSAND = 0x26
    private const val BYTE_QUESTION = 0x3F
    private const val BYTE_SLASH = 0x2F
    private const val BYTE_COLON = 0x3A

    private const val CHAR_LOWER_A = 0x61
    private const val CHAR_LOWER_Z = 0x7A
    private const val CHAR_UPPER_A = 0x41
    private const val CHAR_UPPER_Z = 0x5A
    private const val CHAR_TWO = 0x32
    private const val CHAR_SEVEN = 0x37

    private val OTPAUTH_PREFIX: ByteArray = "otpauth://".toByteArray(StandardCharsets.US_ASCII)
    private val STEAM_PREFIX: ByteArray = "steam://".toByteArray(StandardCharsets.US_ASCII)

    /**
     * 字节语义解析入口（ISSUE-P2-12）：输入不物化为 String，种子全程以 ASCII 字节承载。
     * [uriOrSecret] 归调用方所有，本方法只读不写；返回配置的 [ParsedTotpConfig.secret]
     * 为**全新副本**，用毕须由调用方 fill(0) 擦除。
     *
     * ISSUE-P3-273：`period` / `digits` 的**缺省值可由调用方注入**（用户偏好「默认刷新周期 /
     * 验证码位数」）——只在条目自身未声明该参数时生效（[parsePlainBase32] 的纯 Base32 种子、
     * 或 otpauth URI 缺 `period` / `digits` 参数）。注入值**同样过钳制**：
     * 非正的周期与不在 `6..8` 的位数一律回落内置常量，故「不得绕过钳制语义」不被破坏。
     *
     * ISSUE-P3-383：新增 `steam://` 前缀与 `algorithm=STEAM` 两形态；Steam 恒 5 字符字母表，
     * **不**走 `6..8` 位数钳制（隔离于 RFC 路径，见 [ALGORITHM_STEAM]）。
     */
    fun parse(
        uriOrSecret: ByteArray?,
        defaultPeriodSeconds: Int = DEFAULT_PERIOD,
        defaultDigits: Int = DEFAULT_DIGITS
    ): ParsedTotpConfig? {
        if (uriOrSecret == null || uriOrSecret.isEmpty()) return null
        val period = if (defaultPeriodSeconds > 0) defaultPeriodSeconds else DEFAULT_PERIOD
        val digits = if (defaultDigits in DIGITS_MIN..DIGITS_MAX) defaultDigits else DEFAULT_DIGITS
        val working = trimAsciiWhitespace(uriOrSecret)
        return try {
            when {
                working.isEmpty() -> null
                startsWithIgnoreCase(working, STEAM_PREFIX) ->
                    parseSteamUri(working, period)
                startsWithIgnoreCase(working, OTPAUTH_PREFIX) ->
                    parseOtpAuthUri(working, period, digits)
                else -> parsePlainBase32(working, period, digits)
            }
        } finally {
            // 工作副本含种子字节，成功/失败路径一律擦除
            working.fill(0)
        }
    }

    /**
     * ISSUE-P3-383：`steam://<Base32 种子>[?secret=...]` —— 恒 Steam 算法 / 5 字符。
     */
    private fun parseSteamUri(uri: ByteArray, defaultPeriodSeconds: Int): ParsedTotpConfig? {
        val rest = uri.copyOfRange(STEAM_PREFIX.size, uri.size)
        try {
            val question = indexOfByte(rest, BYTE_QUESTION)
            if (question >= 0) {
                val secretPart = rest.copyOfRange(0, question)
                val query = rest.copyOfRange(question + 1, rest.size)
                val scan = scanQueryParameters(query, defaultPeriodSeconds, STEAM_DIGITS)
                try {
                    val period = if (scan.period > 0) scan.period else defaultPeriodSeconds
                    val secretSource = scan.secretRaw ?: secretPart
                    val normalized = normalizeBase32(secretSource)
                    if (normalized.isEmpty() || !isBase32Alphabet(normalized)) {
                        normalized.fill(0)
                        return null
                    }
                    return ParsedTotpConfig(
                        secret = normalized,
                        period = period,
                        digits = STEAM_DIGITS,
                        algorithm = ALGORITHM_STEAM,
                        issuer = "Steam",
                        account = null,
                        isHotp = false,
                        counter = 0L,
                        warnings = scan.warnings
                    )
                } finally {
                    secretPart.fill(0)
                    query.fill(0)
                    scan.secretRaw?.fill(0)
                }
            }
            val normalized = normalizeBase32(rest)
            return if (normalized.isEmpty() || !isBase32Alphabet(normalized)) {
                normalized.fill(0)
                null
            } else {
                ParsedTotpConfig(
                    secret = normalized,
                    period = defaultPeriodSeconds,
                    digits = STEAM_DIGITS,
                    algorithm = ALGORITHM_STEAM,
                    issuer = "Steam",
                    account = null,
                    isHotp = false,
                    counter = 0L
                )
            }
        } finally {
            rest.fill(0)
        }
    }

    /**
     * String 入口兼容层：内部转 UTF-8 字节后立即擦除。
     * 生产路径（VaultEntryMapper / RealVaultRepository）请直接使用 [parse] 的 ByteArray 重载，
     * 避免把种子固化为不可擦除的 String。
     *
     * ISSUE-P2-15：收敛为**仅测试可见**（`internal`）——String 入参本身即不可擦除的种子物化入口，
     * 生产代码不得再经此重载；核心单测（`core/src/test`）因同模块 friend 可见性仍可调用。
     */
    internal fun parse(
        uriOrSecret: String?,
        defaultPeriodSeconds: Int = DEFAULT_PERIOD,
        defaultDigits: Int = DEFAULT_DIGITS
    ): ParsedTotpConfig? {
        if (uriOrSecret.isNullOrBlank()) return null
        val bytes = uriOrSecret.toByteArray(StandardCharsets.UTF_8)
        return try {
            parse(bytes, defaultPeriodSeconds, defaultDigits)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 查询段扫描产出（ISSUE-P3-305：原 `parseOtpAuthUri` 内联变量按职责聚合）。
     *
     * [secretRaw] 归调用方所有——调用方在 `finally` 中一律 `fill(0)`（与拆分前同一擦除口径）。
     */
    private class QueryScan(
        val secretRaw: ByteArray?,
        val period: Int,
        val digits: Int,
        val algorithm: String,
        val issuerParam: String?,
        val counter: Long,
        val warnings: List<String>
    )

    /**
     * 扫描 `?` 之后的查询段（ISSUE-P3-305：自 `parseOtpAuthUri` 逐行搬出，判定顺序与
     * 各键的解析口径一字未改）。
     *
     * `query` 归调用方所有（本方法只读不写）；返回的 [QueryScan.secretRaw] 为**全新数组**，
     * 由调用方按擦除义务清零。
     */
    private fun scanQueryParameters(
        query: ByteArray,
        defaultPeriodSeconds: Int,
        defaultDigits: Int
    ): QueryScan {
        var secretRaw: ByteArray? = null
        var period = defaultPeriodSeconds
        var digits = defaultDigits
        var algorithm = DEFAULT_ALGORITHM
        var issuerParam: String? = null
        var counter = 0L
        val warnings = mutableListOf<String>()

        var index = 0
        while (index <= query.size) {
            var nextAmp = indexOfByte(query, BYTE_AMPERSAND, index)
            if (nextAmp < 0) nextAmp = query.size
            val eq = indexOfByte(query, BYTE_EQUALS, index).takeIf { it in index until nextAmp }
            if (eq != null) {
                val key = String(query, index, eq - index, StandardCharsets.UTF_8)
                val valueStart = eq + 1
                // ISSUE-P2-289 AC①：参数值一律先百分号解码再归一（禁自写切分产生语义分歧；
                // `secret=...%3D%3D` 不再被解成错误密钥）
                when {
                    key.equals(KEY_SECRET, ignoreCase = true) -> {
                        val rawValue = query.copyOfRange(valueStart, nextAmp)
                        secretRaw = percentDecode(rawValue)
                        rawValue.fill(0)
                    }
                    key.equals(KEY_PERIOD, ignoreCase = true) ->
                        period = queryValueString(query, valueStart, nextAmp)
                            .toIntOrNull() ?: defaultPeriodSeconds
                    key.equals(KEY_DIGITS, ignoreCase = true) -> {
                        val parsed = queryValueString(query, valueStart, nextAmp).toIntOrNull()
                        // ISSUE-P2-289 AC③：越界回落带诊断（禁静默改写）；钳制语义不变
                        if (parsed != null && parsed !in DIGITS_MIN..DIGITS_MAX) {
                            warnings += "digits=$parsed 超出 $DIGITS_MIN..$DIGITS_MAX，已回落 $defaultDigits 位"
                        }
                        digits = parsed ?: defaultDigits
                    }
                    key.equals(KEY_ALGORITHM, ignoreCase = true) -> {
                        val raw = queryValueString(query, valueStart, nextAmp)
                        val normalized = normalizeAlgorithm(raw)
                        // ISSUE-P2-289 AC③：不支持的算法回落 SHA1 带诊断（禁静默改写）
                        if (normalized == DEFAULT_ALGORITHM &&
                            !raw.equals(DEFAULT_ALGORITHM, ignoreCase = true) &&
                            !raw.equals(ALGORITHM_STEAM, ignoreCase = true) &&
                            raw.isNotBlank()
                        ) {
                            warnings += "algorithm=$raw 不支持，已回落 $DEFAULT_ALGORITHM"
                        }
                        algorithm = normalized
                    }
                    key.equals(KEY_ISSUER, ignoreCase = true) ->
                        issuerParam = queryValueString(query, valueStart, nextAmp)
                    key.equals(KEY_COUNTER, ignoreCase = true) ->
                        counter = queryValueString(query, valueStart, nextAmp)
                            .toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                }
            }
            if (nextAmp >= query.size) break
            index = nextAmp + 1
        }
        return QueryScan(secretRaw, period, digits, algorithm, issuerParam, counter, warnings)
    }

    private fun parseOtpAuthUri(
        uri: ByteArray,
        defaultPeriodSeconds: Int,
        defaultDigits: Int
    ): ParsedTotpConfig? {
        // 形如 otpauth://<type>/<label>?<query>
        val slash = indexOfByte(uri, BYTE_SLASH, OTPAUTH_PREFIX.size)
        // ISSUE-P3-49：类型段（totp / hotp）
        val typeEnd = if (slash >= 0) slash else uri.size
        val type = String(uri, OTPAUTH_PREFIX.size, typeEnd - OTPAUTH_PREFIX.size, StandardCharsets.US_ASCII)
        val isHotp = type.equals(TYPE_HOTP, ignoreCase = true)
        val restStart = if (slash >= 0) slash + 1 else uri.size
        val question = indexOfByte(uri, BYTE_QUESTION, restStart)
        val labelEnd = if (question >= 0) question else uri.size
        // ISSUE-P2-289 AC①：label 先百分号解码再归一（`%20` / `%40` 不再原样入库显示）
        val label = String(percentDecode(uri.copyOfRange(restStart, labelEnd)), StandardCharsets.UTF_8)

        val query = if (question >= 0) uri.copyOfRange(question + 1, uri.size) else ByteArray(0)
        val scan = scanQueryParameters(query, defaultPeriodSeconds, defaultDigits)
        try {
            val rawSecret = scan.secretRaw ?: return null
            val normalized = normalizeBase32(rawSecret)
            // ISSUE-P3-383：Steam 算法路径——恒 5 字符，不走 RFC 6..8 位数钳制
            if (scan.algorithm.equals(ALGORITHM_STEAM, ignoreCase = true) ||
                issuerLooksLikeSteam(scan.issuerParam, label)
            ) {
                if (normalized.isEmpty() || !isBase32Alphabet(normalized)) {
                    normalized.fill(0)
                    return null
                }
                val account = if (label.contains(':')) label.substringAfter(':').trim() else label.trim()
                val issuer = (scan.issuerParam ?: label.substringBefore(':')).trim().ifBlank { "Steam" }
                return ParsedTotpConfig(
                    secret = normalized,
                    period = if (scan.period > 0) scan.period else defaultPeriodSeconds,
                    digits = STEAM_DIGITS,
                    algorithm = ALGORITHM_STEAM,
                    issuer = issuer,
                    account = account.ifBlank { null },
                    isHotp = false,
                    counter = 0L,
                    warnings = scan.warnings
                )
            }
            // ISSUE-P2-289 AC②：新输入解析为**严格口径**——含字母表外字符即解析失败
            // （调用方如实报「URI 非法」，禁由下游宽容解码静默解出错误密钥）；
            // 存量库展示的宽容口径在 `OtpEngine.Base32Decoder`（作用域分列，互不外推）
            if (normalized.isEmpty() || !isBase32Alphabet(normalized)) {
                normalized.fill(0)
                return null
            }
            val account = if (label.contains(':')) label.substringAfter(':').trim() else label.trim()
            val issuer = (scan.issuerParam ?: label.substringBefore(':')).trim()
            return ParsedTotpConfig(
                secret = normalized,
                period = if (scan.period > 0) scan.period else defaultPeriodSeconds,
                digits = if (scan.digits in DIGITS_MIN..DIGITS_MAX) scan.digits else defaultDigits,
                algorithm = scan.algorithm,
                issuer = issuer.ifBlank { null },
                account = account.ifBlank { null },
                isHotp = isHotp,
                counter = if (isHotp) scan.counter else 0L,
                warnings = scan.warnings
            )
        } finally {
            query.fill(0)
            scan.secretRaw?.fill(0)
        }
    }

    private fun issuerLooksLikeSteam(issuerParam: String?, label: String): Boolean {
        val candidates = listOfNotNull(issuerParam, label.substringBefore(':'))
        return candidates.any { it.trim().equals("Steam", ignoreCase = true) }
    }

    private fun queryValueString(query: ByteArray, start: Int, end: Int): String =
        TotpUriTextCodec.queryValueString(query, start, end)

    private fun percentDecode(raw: ByteArray): ByteArray = TotpUriTextCodec.percentDecode(raw)

    private fun hexValue(value: Int): Int = TotpUriTextCodec.hexValue(value)

    private fun parsePlainBase32(
        candidate: ByteArray,
        defaultPeriodSeconds: Int,
        defaultDigits: Int
    ): ParsedTotpConfig? {
        val clean = normalizeBase32(candidate)
        return try {
            if (clean.isEmpty() || !isBase32Alphabet(clean)) {
                null
            } else {
                ParsedTotpConfig(
                    secret = clean.copyOf(),
                    period = defaultPeriodSeconds,
                    digits = defaultDigits,
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
        ALGORITHM_STEAM -> ALGORITHM_STEAM
        else -> DEFAULT_ALGORITHM
    }

    private fun normalizeBase32(raw: ByteArray): ByteArray = TotpUriTextCodec.normalizeBase32(raw)

    private fun isBase32Alphabet(bytes: ByteArray): Boolean = TotpUriTextCodec.isBase32Alphabet(bytes)

    private fun trimAsciiWhitespace(bytes: ByteArray): ByteArray = TotpUriTextCodec.trimAsciiWhitespace(bytes)

    private fun isAsciiWhitespace(value: Int): Boolean = TotpUriTextCodec.isAsciiWhitespace(value)

    private fun indexOfByte(bytes: ByteArray, target: Int, from: Int = 0): Int =
        TotpUriTextCodec.indexOfByte(bytes, target, from)

    private fun startsWithIgnoreCase(bytes: ByteArray, prefix: ByteArray): Boolean =
        TotpUriTextCodec.startsWithIgnoreCase(bytes, prefix)

    private fun toUpperAscii(b: Byte): Int = TotpUriTextCodec.toUpperAscii(b)
}

