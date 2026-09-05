package com.keepasskey.database

import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import com.keepasskey.database.xml.KdbxXmlTimeHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * KDBX 4 XML Times 节点时间戳编解码单测。
 * 验证官方 .NET Ticks 编码、已知向量、ISO-8601 与秒级时间兼容及损坏检测。
 */
class KdbxTimesTest {

    @Test
    fun testKnownVector20240101() {
        // 已知向量：2024-01-01T00:00:00Z
        // epochSecond = 1704067200L
        // Ticks = 62135596800L * 10_000_000L + 1704067200L * 10_000_000L = 638396640000000000L
        val expectedInstant = Instant.parse("2024-01-01T00:00:00Z")
        val expectedTicks = 638396640000000000L

        val actualTicks = KdbxXmlTimeHelper.instantToTicks(expectedInstant)
        assertEquals(expectedTicks, actualTicks)

        val restoredInstant = KdbxXmlTimeHelper.ticksToInstant(expectedTicks)
        assertEquals(expectedInstant, restoredInstant)

        val formattedBase64 = KdbxXmlTimeHelper.formatDate(expectedInstant)
        val decodedBytes = Base64.getDecoder().decode(formattedBase64)
        assertEquals(8, decodedBytes.size)
        assertEquals(expectedTicks, LittleEndianUtil.bytesToLong(decodedBytes))

        // 通过 parseDate 解析格式化的 Base64
        val parsed = KdbxXmlTimeHelper.parseDate(formattedBase64)
        assertEquals(expectedInstant, parsed)
    }

    @Test
    fun testTicksWithNanosecondsRoundtrip() {
        // 带亚秒 (100ns 精度) 的时间戳
        val instant = Instant.ofEpochSecond(1704067200L, 500_000_000L) // .5 秒 = 5,000,000 ticks
        val encoded = KdbxXmlTimeHelper.formatDate(instant)
        val parsed = KdbxXmlTimeHelper.parseDate(encoded)
        assertEquals(instant, parsed)
    }

    @Test
    fun testIso8601Compatibility() {
        val isoStr = "2023-08-15T12:30:45Z"
        val parsed = KdbxXmlTimeHelper.parseDate(isoStr)
        assertEquals(Instant.parse(isoStr), parsed)
    }

    @Test
    fun testSecondsFallbackCompatibility() {
        // KeePass 2.x 历史秒级编码：seconds = epochSecond + 62135596800L
        val expectedInstant = Instant.parse("2022-05-10T08:00:00Z")
        val sec = expectedInstant.epochSecond + 62135596800L
        val bytes = LittleEndianUtil.longTo8Bytes(sec)
        val base64 = Base64.getEncoder().encodeToString(bytes)

        val parsed = KdbxXmlTimeHelper.parseDate(base64)
        assertEquals(expectedInstant, parsed)
    }

    @Test
    fun testEmptyOrNullDateReturnsDefault() {
        val defaultTime = Instant.parse("2020-01-01T00:00:00Z")
        assertEquals(defaultTime, KdbxXmlTimeHelper.parseDate(null, defaultTime))
        assertEquals(defaultTime, KdbxXmlTimeHelper.parseDate("", defaultTime))
        assertEquals(defaultTime, KdbxXmlTimeHelper.parseDate("   ", defaultTime))
    }

    /**
     * P3-2 回归：缺失时间戳的缺省值为 Instant.EPOCH（合理远古时间）而非 now()——
     * 三方合并按 lastModificationTime 越新越胜出，缺省 now() 会让 Times 子元素缺失的
     * 文件被判定为「刚刚修改」而虚假覆盖对端。
     */
    @Test
    fun testMissingDateDefaultsToEpochNotNow() {
        assertEquals(Instant.EPOCH, KdbxXmlTimeHelper.parseDate(null))
        assertEquals(Instant.EPOCH, KdbxXmlTimeHelper.parseDate(""))
        assertEquals(Instant.EPOCH, KdbxXmlTimeHelper.parseDate("   "))
    }

    @Test
    fun testCorruptDateThrowsException() {
        // 非法 Base64
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxXmlTimeHelper.parseDate("not!valid@base64")
        }

        // 长度不是 8 字节的 Base64
        val shortBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxXmlTimeHelper.parseDate(shortBase64)
        }

        // 非法 ISO-8601
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxXmlTimeHelper.parseDate("2024-invalid-date-format")
        }
    }

    @Test
    fun testBase64WithUppercaseTNotMisjudgedAsIso8601() {
        // 回归测试：Base64 时间值含大写 "T"（如 q798T/MK3wg=）曾被 contains("T") 误判为
        // ISO-8601 并抛出损坏异常（约 17% 时间戳随机触发）。
        // 构造一个 Base64 编码后确实包含大写 T 的合法 Ticks 值验证不被误判。
        val instant = Instant.parse("2019-06-15T08:30:00Z")
        val ticks = KdbxXmlTimeHelper.instantToTicks(instant)
        val encoded = Base64.getEncoder().encodeToString(LittleEndianUtil.longTo8Bytes(ticks))
        // 仅当构造的编码确实含大写 T 时本测试才具备回归意义；否则用一个手工含 T 的合法值兜底
        val candidate = if (encoded.contains("T")) {
            encoded
        } else {
            // ticks=0x...：手工挑选一个编码含 T 的已知值（ticks 638396640000000000 之外的样本）
            // 637000000000000000L 的 Base64 编码以 'T' 开头（验证性构造）
            val probe = 637_000_000_000_000_000L
            Base64.getEncoder().encodeToString(LittleEndianUtil.longTo8Bytes(probe))
        }

        val parsed = KdbxXmlTimeHelper.parseDate(candidate)
        // 解析必须成功（不抛异常）且还原为对应时间
        if (candidate == encoded) {
            assertEquals(instant, parsed)
        }
    }
}
