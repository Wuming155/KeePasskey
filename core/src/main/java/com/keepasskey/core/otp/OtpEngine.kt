package com.keepasskey.core.otp

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * 纯 Kotlin 实现的 RFC 6238 (TOTP) 与 RFC 4226 (HOTP) 动态令牌计算引擎。
 * 支持 SHA-1, SHA-256, SHA-512，6位/8位代码，以及标准的 30s/60s 步长。
 * 遵循严格的敏感数据原则：Base32 解码与密钥计算直接使用 ByteArray。
 */
object OtpEngine {

    enum class HashAlgorithm(val hmacAlgorithm: String) {
        SHA1("HmacSHA1"),
        SHA256("HmacSHA256"),
        SHA512("HmacSHA512")
    }

    /**
     * 计算指定时间戳的 TOTP 动态验证码
     * @param secretKeyBase32 Base32 编码的密钥字符串
     * @param timestampMillis 当前毫秒时间戳
     * @param periodSeconds 步长 (默认 30 秒)
     * @param digits 验证码位数 (通常为 6 或 8)
     * @param algorithm 哈希算法 (默认 SHA1)
     * @return 格式化的验证码字符串 (如 "123456")
     */
    fun calculateTotp(
        secretKeyBase32: String,
        timestampMillis: Long = System.currentTimeMillis(),
        periodSeconds: Int = 30,
        digits: Int = 6,
        algorithm: HashAlgorithm = HashAlgorithm.SHA1
    ): String {
        val counter = (timestampMillis / 1000L) / periodSeconds
        return calculateHotp(secretKeyBase32, counter, digits, algorithm)
    }

    /**
     * 获取当前周期剩余秒数
     */
    fun getRemainingSeconds(
        timestampMillis: Long = System.currentTimeMillis(),
        periodSeconds: Int = 30
    ): Int {
        val currentSecond = (timestampMillis / 1000L) % periodSeconds
        return (periodSeconds - currentSecond).toInt()
    }

    /**
     * 计算基于计数器的 HOTP 验证码 (RFC 4226)
     */
    fun calculateHotp(
        secretKeyBase32: String,
        counter: Long,
        digits: Int = 6,
        algorithm: HashAlgorithm = HashAlgorithm.SHA1
    ): String {
        val secretBytes = Base32Decoder.decode(secretKeyBase32)
        return calculateHotpRaw(secretBytes, counter, digits, algorithm)
    }

    /**
     * 原始字节流计算 HOTP 验证码
     */
    fun calculateHotpRaw(
        secretBytes: ByteArray,
        counter: Long,
        digits: Int = 6,
        algorithm: HashAlgorithm = HashAlgorithm.SHA1
    ): String {
        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance(algorithm.hmacAlgorithm)
        mac.init(SecretKeySpec(secretBytes, algorithm.hmacAlgorithm))
        val hash = mac.doFinal(counterBytes)

        // 动态截断 (Dynamic Truncation)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        val otp = binary % 10.0.pow(digits).toInt()
        return otp.toString().padStart(digits, '0')
    }

    /**
     * 解析 KeyUri 格式 (otpauth://totp/Example:alice@google.com?secret=JBSWY3DPEHPK3PXP&issuer=Example)
     */
    fun parseOtpAuthUri(uriString: String): OtpParameters? {
        if (!uriString.startsWith("otpauth://")) return null
        val type = uriString.substringAfter("otpauth://").substringBefore('/')
        val rest = uriString.substringAfter("otpauth://$type/")
        val label = rest.substringBefore('?')
        val query = rest.substringAfter('?', "")

        val params = query.split('&').associate {
            val key = it.substringBefore('=')
            val value = it.substringAfter('=', "")
            key to value
        }

        val secret = params["secret"] ?: return null
        val period = params["period"]?.toIntOrNull() ?: 30
        val digits = params["digits"]?.toIntOrNull() ?: 6
        val algorithmStr = params["algorithm"]?.uppercase() ?: "SHA1"
        val algorithm = when (algorithmStr) {
            "SHA256" -> HashAlgorithm.SHA256
            "SHA512" -> HashAlgorithm.SHA512
            else -> HashAlgorithm.SHA1
        }
        val issuer = params["issuer"] ?: label.substringBefore(':', "")

        return OtpParameters(
            type = type,
            label = label,
            issuer = issuer,
            secretBase32 = secret,
            periodSeconds = period,
            digits = digits,
            algorithm = algorithm
        )
    }

    data class OtpParameters(
        val type: String,
        val label: String,
        val issuer: String,
        val secretBase32: String,
        val periodSeconds: Int = 30,
        val digits: Int = 6,
        val algorithm: HashAlgorithm = HashAlgorithm.SHA1
    )
}

/**
 * 纯 Kotlin RFC 4648 Base32 解码器
 */
object Base32Decoder {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(base32: String): ByteArray {
        val clean = base32.trim().uppercase().replace("=", "").replace(" ", "")
        var buffer = 0
        var bitsLeft = 0
        val output = mutableListOf<Byte>()

        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return output.toByteArray()
    }
}
