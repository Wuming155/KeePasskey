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
 *
 * **职责边界（ISSUE-P3-90）**：本引擎**只**做令牌计算；`otpauth://` URI 的解析在
 * [TotpKeyUriParser]（含 period / digits 钳制与 PSL 域校验）。此前本类另有一份公开的
 * `parseOtpAuthUri` 副本，对 `period` / `digits` **无任何钳制**且零调用点——
 * 为避免后来者误接入该未加固副本，已整体删除（连同其 `OtpParameters` 类型）。
 */
object OtpEngine {

    enum class HashAlgorithm(val hmacAlgorithm: String) {
        SHA1("HmacSHA1"),
        SHA256("HmacSHA256"),
        SHA512("HmacSHA512")
    }

    /**
     * ISSUE-P3-173：`10^digits` 查表，替代每次取码各一次 `10.0.pow(...)` 浮点运算。
     *
     * 表覆盖 `digits ∈ [0, 9]`；越界位数回退原浮点路径，故既有行为（含 `digits ≥ 10` 时
     * `toInt()` 饱和到 `Int.MAX_VALUE`）逐字不变——本表只替换计算方式，不改判定语义。
     */
    private val POW10 = intArrayOf(
        1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000, 1_000_000_000
    )

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

        val otp = binary % tenPow(digits)
        return otp.toString().padStart(digits, '0')
    }

    /** `10^digits`：表内直取，越界回退原浮点路径（ISSUE-P3-173，语义逐字不变） */
    private fun tenPow(digits: Int): Int =
        if (digits in POW10.indices) POW10[digits] else 10.0.pow(digits).toInt()
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
     * ISSUE-P3-173：字节值 → 5 bit 值的反查表（字母表外字符为 -1）。
     *
     * 取代原 `ALPHABET.indexOf(upper)`——后者是 `String.indexOf(Char)` 的**每字符 32 步
     * 线性扫描**（`O(32n)`），而本表按字节值 O(1) 直取。表按 256 项建，避免额外的越界判断。
     */
    private val DECODE_TABLE = IntArray(256) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

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
     *
     * ISSUE-P3-173：改为**两趟法 + 预分配 `ByteArray`**——Base32 每有效字符产出 5 bit，
     * 故输出长度恒为 `有效字符数 × 5 / 8`，可先精确算长再一次填充。原实现用
     * `MutableList<Byte>` 逐字节装箱、末尾 `toByteArray()` 再整体复制一次。
     *
     * @param base32Bytes 归调用方所有，本方法只读不写
     */
    fun decode(base32Bytes: ByteArray): ByteArray {
        var validCount = 0
        for (raw in base32Bytes) {
            if (valueOf(raw) >= 0) validCount++
        }

        val output = ByteArray(validCount * 5 / 8)
        var outIndex = 0
        var buffer = 0
        var bitsLeft = 0
        for (raw in base32Bytes) {
            val value = valueOf(raw)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output[outIndex++] = ((buffer shr bitsLeft) and 0xFF).toByte()
            }
        }
        return output
    }

    /** 单字节 → 5 bit 值（小写归一后查表）；字母表外字符返回 -1（宽容跳过） */
    private fun valueOf(raw: Byte): Int {
        val c = (raw.toInt() and 0xFF).toChar()
        val upper = if (c in ASCII_LOWER_A..ASCII_LOWER_Z) c - ASCII_CASE_OFFSET else c
        return DECODE_TABLE[upper.code]
    }
}
