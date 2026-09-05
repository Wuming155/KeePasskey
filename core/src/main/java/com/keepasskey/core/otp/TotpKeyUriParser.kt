package com.keepasskey.core.otp

/**
 * 解析后的 TOTP 配置模型
 */
data class ParsedTotpConfig(
    val secret: String,
    val period: Int = 30,
    val digits: Int = 6,
    val algorithm: String = "SHA1",
    val issuer: String? = null,
    val account: String? = null
)

/**
 * TOTP KeyUri 解析器。
 * 支持标准 RFC 6238 KeyUri（如 otpauth://totp/Issuer:Account?secret=...&period=30&digits=6&algorithm=SHA1）
 * 以及纯 Base32 密钥格式。宽容解析缺省参数，非法输入返回 null。
 */
object TotpKeyUriParser {

    private const val DEFAULT_PERIOD = 30
    private const val DEFAULT_DIGITS = 6
    private const val DEFAULT_ALGORITHM = "SHA1"

    fun parse(uriOrSecret: String?): ParsedTotpConfig? {
        if (uriOrSecret.isNullOrBlank()) return null
        val raw = uriOrSecret.trim()

        if (raw.startsWith("otpauth://", ignoreCase = true)) {
            val parsed = OtpEngine.parseOtpAuthUri(raw) ?: return null
            if (parsed.secretBase32.isBlank()) return null
            val rawLabel = parsed.label
            val account = if (rawLabel.contains(':')) {
                rawLabel.substringAfter(':').trim()
            } else {
                rawLabel.trim()
            }

            return ParsedTotpConfig(
                secret = parsed.secretBase32.trim().replace(" ", "").uppercase(),
                period = if (parsed.periodSeconds > 0) parsed.periodSeconds else DEFAULT_PERIOD,
                digits = if (parsed.digits in 6..8) parsed.digits else DEFAULT_DIGITS,
                algorithm = parsed.algorithm.name,
                issuer = parsed.issuer.ifBlank { null },
                account = account.ifBlank { null }
            )
        }

        // 宽容处理纯 Base32 密钥
        val cleanCandidate = raw.replace(" ", "").replace("=", "").uppercase()
        if (cleanCandidate.isNotEmpty() && cleanCandidate.matches(Regex("^[A-Z2-7]+$"))) {
            return ParsedTotpConfig(
                secret = cleanCandidate,
                period = DEFAULT_PERIOD,
                digits = DEFAULT_DIGITS,
                algorithm = DEFAULT_ALGORITHM
            )
        }

        return null
    }
}
