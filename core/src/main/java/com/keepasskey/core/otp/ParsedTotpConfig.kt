package com.keepasskey.core.otp

/**
 * 解析后的 TOTP 配置模型。
 *
 * ISSUE-P2-12 整改：[secret] 由不可擦除的 String 改为 **Base32 文本字节（ASCII）**，
 * 归调用方所有，消费后须显式 fill(0) 擦除；解析层不再物化种子 String。
 *
 * ISSUE-P3-305：本值对象自 `TotpKeyUriParser.kt` 按**纯结构性拆分**搬出（同包，
 * 全限定名 `com.keepasskey.core.otp.ParsedTotpConfig` 不变）；解析器只保留字节解析面。
 */
class ParsedTotpConfig(
    val secret: ByteArray,
    val period: Int = 30,
    val digits: Int = 6,
    val algorithm: String = "SHA1",
    val issuer: String? = null,
    val account: String? = null,
    // ISSUE-P3-49：HOTP（RFC 4226）支持——`otpauth://hotp/...` 类型段与 `counter` 参数。
    // isHotp=false 时 [counter] 无意义（恒 0）。
    val isHotp: Boolean = false,
    val counter: Long = 0,
    /**
     * ISSUE-P2-289 AC③：解析期的**非致命诊断**（如 `digits` 越界回落、
     * `algorithm` 不支持回落 SHA1）——供 UI 如实呈现，**禁静默改写**。
     * 仅元数据（不含种子），不参与 equals/hashCode（不影响配置身份）。
     */
    val warnings: List<String> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedTotpConfig) return false
        return secret.contentEquals(other.secret) &&
            period == other.period &&
            digits == other.digits &&
            algorithm == other.algorithm &&
            issuer == other.issuer &&
            account == other.account &&
            isHotp == other.isHotp &&
            counter == other.counter
    }

    override fun hashCode(): Int {
        var result = secret.contentHashCode()
        result = 31 * result + period
        result = 31 * result + digits
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + (issuer?.hashCode() ?: 0)
        result = 31 * result + (account?.hashCode() ?: 0)
        result = 31 * result + isHotp.hashCode()
        result = 31 * result + counter.hashCode()
        return result
    }

    override fun toString(): String {
        // 绝不输出种子内容，仅呈现长度（与 ProtectedString.toString 一致的安全约定）
        return "ParsedTotpConfig(secretLen=" + secret.size + ", period=" + period + ", digits=" + digits +
            ", algorithm=" + algorithm + ", issuer=" + issuer + ", account=" + account +
            ", isHotp=" + isHotp + ", counter=" + counter + ", warnings=" + warnings.size + ")"
    }
}
