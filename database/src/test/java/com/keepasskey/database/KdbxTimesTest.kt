package com.keepasskey.database

import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import com.keepasskey.database.xml.KdbxXmlTimeHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * KDBX 4 XML Times 节点时间戳编解码单测。
 *
 * **golden 依据**：KDBX 4 规范（`kdbx_4.html`「Page History 0.2」）
 * "Base64 string of the Int64 number of **seconds** elapsed since 0001-01-01 00:00 UTC"，
 * 与官方 KeePass 2.61.1 `KdbxFile.Write.cs:799`
 * `long lSec = dt.Ticks / TimeSpan.TicksPerSecond;`（写出）/
 * `KdbxFile.Read.Streamed.cs:934-935`（读入）一致。
 *
 * 本类固化两条独立的防回归线：
 * 1. 写出值必须是**秒**级量级（< 2^40）——若再次退化为 .NET Ticks（10^17 量级），
 *    [testOfficialSecondsGoldenVector20240101] 立即失败；
 * 2. 读侧仍须读回本仓早期误写的 .NET Ticks 旧产物（[testLegacyTicksStillReadableAsHistoricalCompat]）。
 */
class KdbxTimesTest {

    /**
     * 2024-01-01T00:00:00Z 的官方秒级编码：
     * 62135596800（0001-01-01 到 Unix 纪元的秒数）+ 1704067200（该时刻的 Unix 秒）。
     */
    private val officialSeconds2024 = 63839664000L

    /** 官方秒级编码的 Base64 golden（LE 8 字节：80 F7 23 DD 0E 00 00 00）。 */
    private val officialBase64_2024 = "gPcj3Q4AAAA="

    @Test
    fun testOfficialSecondsGoldenVector20240101() {
        val expectedInstant = Instant.parse("2024-01-01T00:00:00Z")

        // 编码值 = 官方 dt.Ticks / TicksPerSecond
        assertEquals(officialSeconds2024, KdbxXmlTimeHelper.instantToDotNetSeconds(expectedInstant))

        val formattedBase64 = KdbxXmlTimeHelper.formatDate(expectedInstant)
        assertEquals(officialBase64_2024, formattedBase64)

        val decodedBytes = Base64.getDecoder().decode(formattedBase64)
        assertEquals(8, decodedBytes.size)
        val encodedValue = LittleEndianUtil.bytesToLong(decodedBytes)
        assertEquals(officialSeconds2024, encodedValue)

        // golden 量级断言：官方秒级时间戳必 < 2^40（公元 9999 年的上界约 3.2×10^11）。
        // 本仓历史缺陷写出 .NET Ticks（约 6.4×10^17，是官方值的 10^7 倍），
        // 会让官方 KeePass 的 `lSec * TicksPerSecond` 立即溢出；本断言杜绝该退化。
        assertTrue(
            "formatDate 必须产出秒级量级（< 2^40），实际为 $encodedValue；若约 10^7 倍则说明又写成了 .NET Ticks",
            encodedValue < SECONDS_MAGNITUDE_UPPER_BOUND
        )

        // 读侧主路径：官方秒 → 时刻
        assertEquals(expectedInstant, KdbxXmlTimeHelper.parseDate(formattedBase64))
    }

    @Test
    fun testSubSecondIsTruncatedLikeOfficial() {
        // 官方只写整秒（Ticks / TicksPerSecond 为整除），亚秒被丢弃，故写-读是「截断到秒」的恒等
        val instant = Instant.ofEpochSecond(1704067200L, 500_000_000L)
        val encoded = KdbxXmlTimeHelper.formatDate(instant)

        assertEquals(officialSeconds2024, LittleEndianUtil.bytesToLong(Base64.getDecoder().decode(encoded)))
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), KdbxXmlTimeHelper.parseDate(encoded))
    }

    /**
     * 读侧**历史遗留兼容**（非官方格式）：本仓早期版本把 .NET Ticks 当官方格式写出，
     * 官方 KeePass 无法打开这类产物（`lSec * 10^7` 溢出）；本仓读侧必须仍能读回自己的旧产物。
     * `KdbxXmlTimeHelper` 的 `TICKS_THRESHOLD` 分支即为此保留。
     */
    @Test
    fun testLegacyTicksStillReadableAsHistoricalCompat() {
        val legacyTicks = 638396640000000000L
        val base64 = Base64.getEncoder().encodeToString(LittleEndianUtil.longTo8Bytes(legacyTicks))

        // 与官方秒级向量指向同一时刻，但量级是 Ticks（10^7 倍）
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), KdbxXmlTimeHelper.parseDate(base64))
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), KdbxXmlTimeHelper.ticksToInstant(legacyTicks))
    }

    @Test
    fun testIso8601Compatibility() {
        val isoStr = "2023-08-15T12:30:45Z"
        val parsed = KdbxXmlTimeHelper.parseDate(isoStr)
        assertEquals(Instant.parse(isoStr), parsed)
    }

    /**
     * 读侧必须接受按官方公式（不经过本仓写侧）独立构造的秒级 Base64——
     * 这是与官方 KeePass / KeePassXC / KeePassDX 互操作的读入 golden。
     */
    @Test
    fun testOfficialSecondsBase64FromIndependentFormula() {
        val expectedInstant = Instant.parse("2022-05-10T08:00:00Z")
        // 官方：lSec = dt.Ticks / TimeSpan.TicksPerSecond = 62135596800 + Unix 秒
        val officialSeconds = 62135596800L + expectedInstant.epochSecond
        val base64 = Base64.getEncoder().encodeToString(LittleEndianUtil.longTo8Bytes(officialSeconds))

        assertEquals(expectedInstant, KdbxXmlTimeHelper.parseDate(base64))
    }

    @Test
    fun testEmptyOrNullDateReturnsDefault() {
        val defaultTime = Instant.parse("2020-01-01T00:00:00Z")
        assertEquals(defaultTime, KdbxXmlTimeHelper.parseDate(null, defaultTime))
        assertEquals(defaultTime, KdbxXmlTimeHelper.parseDate("", defaultTime))
        assertEquals(defaultTime, KdbxXmlTimeHelper.parseDate("   ", defaultTime))
    }

    /**
     * D11/D12 回归：时间戳缺失的缺省值是 0001-01-01T00:00:00Z（官方未初始化时间的
     * .NET `DateTime.MinValue` = KDBX 纪元原点），既不是 now()（虚假「刚修改」），
     * 也不是 Unix 纪元 1970-01-01。
     *
     * 缺省值恒早于 1970，故在「lastModificationTime 越新越胜出」的三方合并中
     * **不可能**虚假胜出对端。
     */
    @Test
    fun testMissingDateDefaultsToAncientEpochOrigin() {
        assertEquals(Instant.parse("0001-01-01T00:00:00Z"), KdbxXmlTimeHelper.ANCIENT_INSTANT)
        assertEquals(Instant.ofEpochSecond(-62135596800L), KdbxXmlTimeHelper.ANCIENT_INSTANT)

        assertEquals(KdbxXmlTimeHelper.ANCIENT_INSTANT, KdbxXmlTimeHelper.parseDate(null))
        assertEquals(KdbxXmlTimeHelper.ANCIENT_INSTANT, KdbxXmlTimeHelper.parseDate(""))
        assertEquals(KdbxXmlTimeHelper.ANCIENT_INSTANT, KdbxXmlTimeHelper.parseDate("   "))

        // 缺省值不得参与「越新越胜出」的合并误判：必须早于任何真实库时间戳（< 1970）
        assertTrue(
            "远古缺省必须早于 Unix 纪元，实际 ${KdbxXmlTimeHelper.ANCIENT_INSTANT}",
            KdbxXmlTimeHelper.ANCIENT_INSTANT.isBefore(Instant.EPOCH)
        )

        // 写回为 8 个零字节（官方 DateTime.MinValue 的秒数 = 0），可无损往返
        val ancientBase64 = KdbxXmlTimeHelper.formatDate(KdbxXmlTimeHelper.ANCIENT_INSTANT)
        assertEquals(0L, LittleEndianUtil.bytesToLong(Base64.getDecoder().decode(ancientBase64)))
        assertEquals(KdbxXmlTimeHelper.ANCIENT_INSTANT, KdbxXmlTimeHelper.parseDate(ancientBase64))
    }

    /** `<Times>` 元素整体缺失时的缺省 [com.keepasskey.core.model.KdbxTimes] 与缺省时刻同源。 */
    @Test
    fun testAncientTimesDefaultsAreAllEpochOrigin() {
        val times = KdbxXmlTimeHelper.ancientTimes()

        assertEquals(KdbxXmlTimeHelper.ANCIENT_INSTANT, times.creationTime)
        assertEquals(KdbxXmlTimeHelper.ANCIENT_INSTANT, times.lastModificationTime)
        assertEquals(KdbxXmlTimeHelper.ANCIENT_INSTANT, times.lastAccessTime)
        assertEquals(KdbxXmlTimeHelper.ANCIENT_INSTANT, times.expiryTime)
        assertEquals(KdbxXmlTimeHelper.ANCIENT_INSTANT, times.locationChanged)
        assertFalse(times.expires)
        assertEquals(0L, times.usageCount)
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
        // 回归测试：Base64 时间值含大写 "T" 曾被 contains("T") 误判为 ISO-8601 并抛损坏异常。
        // 现嗅探模式要求「4 位数字 + 连字符」开头，而 Base64 字母表不含连字符，故凡合法值必解析成功。
        val instant = Instant.parse("2019-06-15T08:30:00Z")
        var found: Pair<Long, String>? = null
        for (offset in 0L..512L) {
            val encoded = KdbxXmlTimeHelper.formatDate(instant.plusSeconds(offset))
            if (encoded.contains('T')) {
                found = offset to encoded
                break
            }
        }
        assertNotNull("样本区间内应存在编码含大写 'T' 的合法秒级时间值", found)

        val (offset, encoded) = found!!
        assertEquals(instant.plusSeconds(offset), KdbxXmlTimeHelper.parseDate(encoded))
    }

    private companion object {
        /**
         * 官方秒级时间戳的量级上界：2^40 = 1,099,511,627,776。
         * 公元 9999 年的官方秒级值约 3.2×10^11 < 2^40，而 .NET Ticks 约 10^17 ≥ 2^40。
         */
        const val SECONDS_MAGNITUDE_UPPER_BOUND = 1L shl 40
    }
}
