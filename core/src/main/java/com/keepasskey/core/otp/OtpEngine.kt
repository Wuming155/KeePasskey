package com.keepasskey.core.otp

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
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
     *
     * TASK-46 敏感数据铁律：种子经 Base32 解码为字节流后**全程 ByteArray 态**参与计算，
     * 引擎侧不持有、不复制、不篡改调用方密钥，绝不还原为 String。
     * @param secretKey 二进制密钥字节（归调用方所有，用毕须由调用方显式 `fill(0)` 擦除）
     * @param timestampMillis 当前毫秒时间戳
     * @param periodSeconds 步长 (默认 30 秒)
     * @param digits 验证码位数 (通常为 6 或 8)
     * @param algorithm 哈希算法 (默认 SHA1)
     * @return 格式化的验证码字符串 (如 "123456")
     */
    fun calculateTotp(
        secretKey: ByteArray,
        timestampMillis: Long = System.currentTimeMillis(),
        periodSeconds: Int = 30,
        digits: Int = 6,
        algorithm: HashAlgorithm = HashAlgorithm.SHA1
    ): String {
        val counter = (timestampMillis / 1000L) / periodSeconds
        return calculateHotp(secretKey, counter, digits, algorithm)
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
     *
     * TASK-46 敏感数据铁律：HMAC-over-counter 计算链路（`SecretKeySpec`/摘要/动态截断）
     * 中间值全部为字节数组，不产生任何 String 形式的密钥中间副本。
     * @param secretKey 二进制密钥字节（归调用方所有，用毕须由调用方显式 `fill(0)` 擦除）
     */
    fun calculateHotp(
        secretKey: ByteArray,
        counter: Long,
        digits: Int = 6,
        algorithm: HashAlgorithm = HashAlgorithm.SHA1
    ): String {
        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance(algorithm.hmacAlgorithm)
        mac.init(SecretKeySpec(secretKey, algorithm.hmacAlgorithm))
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
 * 纯 Kotlin RFC 4648 Base32 解码器。
 *
 * TASK-46 借用语义（对齐 `KdbxKeyFile` / `SyncCredentialsStore`）：[decode] 每次返回
 * **调用方独占的新 ByteArray**，解码器无内部缓存与驻留；调用方用毕（含失败路径）
 * 须显式 `fill(0)` 擦除，杜绝种子字节残留在堆内存。
 */
object Base32Decoder {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private const val ASCII_LOWER_A = 'a'
    private const val ASCII_LOWER_Z = 'z'
    private const val ASCII_CASE_OFFSET = 32

    /**
     * 解码 Base32 文本为字节流。
     * 宽容策略：忽略 `=` 填充、空白与字母表外字符（与既有线上语义一致，避免存量
     * 库文件 TOTP 展示回退）；本方法不抛出异常，空/无效输入返回空数组。
     * @return 全新的字节数组，归调用方所有，用毕须 `fill(0)` 擦除
     */
    fun decode(base32: String): ByteArray =
        decode(base32.toByteArray(StandardCharsets.UTF_8))

    /**
     * ISSUE-P2-12 字节语义重载：直接按 ASCII 字节解析 Base32，
     * 全程不物化不可擦除的种子 String；行为与原 String 版本一致
     * （忽略空白、'=' 填充与字母表外字符，输出为全新字节数组）。
     * @param base32Bytes 归调用方所有，本方法只读不写
     */
    fun decode(base32Bytes: ByteArray): ByteArray {
        var buffer = 0
        var bitsLeft = 0
        val output = mutableListOf<Byte>()

        for (raw in base32Bytes) {
            val c = (raw.toInt() and 0xFF).toChar()
            val upper = if (c in ASCII_LOWER_A..ASCII_LOWER_Z) c - ASCII_CASE_OFFSET else c
            val value = ALPHABET.indexOf(upper)
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
