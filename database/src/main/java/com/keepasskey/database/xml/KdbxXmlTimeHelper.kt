package com.keepasskey.database.xml

import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import java.time.Instant
import java.util.Base64

/**
 * KDBX XML 时间戳（Times 节点）编解码工具类。
 *
 * 官方标准（KeePass 2.61.1 §4 与 §8.1 / KeePassDX）：
 * - KDBX 4 标准编码为 Base64(int64 .NET Ticks)，Little-Endian 8 字节；
 * - 1 tick = 100ns，纪元基准为 0001-01-01 00:00:00 UTC（与 Unix 纪元相差 62,135,596,800 秒）；
 * - 读侧同时宽容支持 KeePass 2.x 的秒级时间戳以及 KDBX 3 风格的 ISO-8601 字符串；
 * - 严格解析：若元素存在但内容损坏，抛出 [KdbxCorruptFileException]。
 */
object KdbxXmlTimeHelper {

    private const val EPOCH_OFFSET_SECONDS = 62135596800L
    private const val TICKS_PER_SECOND = 10_000_000L
    private const val EPOCH_OFFSET_TICKS = EPOCH_OFFSET_SECONDS * TICKS_PER_SECOND
    private const val TICKS_THRESHOLD = 10_000_000_000_000L

    /**
     * 将 [Instant] 格式化为官方 KDBX 4 标准 Base64(.NET Ticks) 字符串
     */
    fun formatDate(instant: Instant): String {
        val ticks = EPOCH_OFFSET_TICKS + instant.epochSecond * TICKS_PER_SECOND + (instant.nano / 100)
        val bytes = LittleEndianUtil.longTo8Bytes(ticks)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * 将 [Instant] 编码为 .NET Ticks 原始数值
     */
    fun instantToTicks(instant: Instant): Long {
        return EPOCH_OFFSET_TICKS + instant.epochSecond * TICKS_PER_SECOND + (instant.nano / 100)
    }

    /**
     * 将 .NET Ticks 原始数值解析为 [Instant]
     */
    fun ticksToInstant(ticks: Long): Instant {
        val netTicks = ticks - EPOCH_OFFSET_TICKS
        val epochSec = Math.floorDiv(netTicks, TICKS_PER_SECOND)
        val nanoTicks = Math.floorMod(netTicks, TICKS_PER_SECOND)
        return Instant.ofEpochSecond(epochSec, nanoTicks * 100)
    }

    /**
     * ISO-8601 日历日期嗅探模式（形如 "2024-01-01T..." 或 "2024-01-01..."）。
     * 必须以「4 位数字 + 连字符」开头——标准 Base64 字母表（A-Za-z0-9+/=）不含连字符，
     * 因此 Base64 编码的 Ticks 值绝不可能命中本模式，杜绝含大写 "T" 的 Base64
     * 时间值被误判为 ISO-8601 的随机性缺陷。
     */
    private val iso8601Pattern = Regex("^\\d{4}-\\d{2}-\\d{2}")

    /**
     * 从 XML 字符串解析时间戳。
     * 若 [dateStr] 为 null 或 blank（元素缺失）返回 [defaultInstant]；
     * 若内容存在但格式不符合规范，抛出 [KdbxCorruptFileException]。
     */
    fun parseDate(dateStr: String?, defaultInstant: Instant = Instant.now()): Instant {
        if (dateStr.isNullOrBlank()) return defaultInstant
        val clean = dateStr.trim()

        // 兼容 KDBX 3 格式的 ISO-8601 字符串（严格模式嗅探，见 iso8601Pattern KDoc）
        if (iso8601Pattern.containsMatchIn(clean)) {
            return try {
                Instant.parse(clean)
            } catch (e: Exception) {
                throw KdbxCorruptFileException("非法的 ISO-8601 时间格式: $clean", e)
            }
        }

        val bytes = try {
            Base64.getDecoder().decode(clean)
        } catch (e: Exception) {
            throw KdbxCorruptFileException("非法的 Base64 时间字符串: $clean", e)
        }

        if (bytes.size != 8) {
            throw KdbxCorruptFileException("非法的二进制时间数据长度: ${bytes.size}，期望 8 字节")
        }

        val value = LittleEndianUtil.bytesToLong(bytes)
        return if (value >= TICKS_THRESHOLD) {
            // .NET Ticks 编码 (100ns)
            try {
                ticksToInstant(value)
            } catch (e: Exception) {
                throw KdbxCorruptFileException("超出合法范围的 Ticks 时间戳: $value", e)
            }
        } else {
            // 秒级时间戳兼容 (KeePass 2.x 历史模式)
            val epochSec = value - EPOCH_OFFSET_SECONDS
            try {
                Instant.ofEpochSecond(epochSec)
            } catch (e: Exception) {
                throw KdbxCorruptFileException("超出合法范围的秒级时间戳: $value", e)
            }
        }
    }
}
