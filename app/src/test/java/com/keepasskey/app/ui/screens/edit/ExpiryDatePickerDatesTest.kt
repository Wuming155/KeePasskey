package com.keepasskey.app.ui.screens.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/**
 * ISSUE-P3-389 AC②：过期时间 ↔ DatePicker 毫秒双向换算的时区不变量守卫。
 *
 * Material3 `DatePickerState.selectedDateMillis` 平台契约 = 所选日历日的 **UTC** 零点毫秒；
 * 旧缺陷形态：双向都按设备默认时区（`ZoneId.systemDefault()`）解释，非 UTC 时区用户
 * 编辑过期时间**双向各偏一天**。本类把设备默认时区表驱动拨到 `America/New_York`
 * （UTC-5 / 夏令时 UTC-4）与 `Asia/Shanghai`（UTC+8，无夏令时）两端，锁定换算
 * 与设备时区完全无关：
 * - 「日期 → 毫秒」两端同值，且恒为 UTC 零点（金值：2026-01-01 UTC 零点 = 1767225600000）；
 * - 「毫秒 → 日期」按 UTC 取回同一天——若回归成本地时区解释，美东端会读出前一日，
 *   该端即为双向各一的反向捕获（东八端只捕获「日期 → 毫秒」方向，两端缺一不可）；
 * - 含美东 2026 年夏令时开始日（03-08）/ 结束日（11-01）、闰日与年界日。
 * 每例结束恢复默认时区，避免污染同 JVM 内的其他用例。
 */
class ExpiryDatePickerDatesTest {

    private fun withDefaultTimeZone(zoneId: String, block: () -> Unit) {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
        try {
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /** 表驱动两端：覆盖西半球负偏移（含 DST）与东半球正偏移（无 DST）。 */
    private val zones = listOf("America/New_York", "Asia/Shanghai")

    @Test
    fun `日期转选择器毫秒在两端时区下同为该日UTC零点`() {
        val millisByZone = mutableMapOf<String, Long>()
        for (zone in zones) {
            withDefaultTimeZone(zone) {
                val millis = expiryDateAsPickerMillis(LocalDate.of(2026, 1, 1))
                assertEquals(1_767_225_600_000L, millis)
                millisByZone[zone] = millis!!
            }
        }
        assertEquals(2, millisByZone.size)
        assertEquals(
            millisByZone.getValue(zones[0]),
            millisByZone.getValue(zones[1])
        )
    }

    @Test
    fun `双向换算在两端时区下含夏令时切换日与闰日年界均不漂移`() {
        val dates = listOf(
            LocalDate.of(2024, 2, 29), // 闰日
            LocalDate.of(2026, 1, 1), // 年界
            LocalDate.of(2026, 3, 8), // 美东 2026 年夏令时开始日
            LocalDate.of(2026, 11, 1), // 美东 2026 年夏令时结束日
            LocalDate.of(2026, 12, 31) // 年界
        )
        for (zone in zones) {
            withDefaultTimeZone(zone) {
                for (date in dates) {
                    val millis = expiryDateAsPickerMillis(date)
                    assertEquals(date, pickerMillisAsExpiryDate(millis))
                }
            }
        }
    }

    @Test
    fun `选择器毫秒转日期在两端时区下按UTC取日`() {
        for (zone in zones) {
            withDefaultTimeZone(zone) {
                assertEquals(
                    LocalDate.of(2026, 1, 1),
                    pickerMillisAsExpiryDate(1_767_225_600_000L)
                )
                assertEquals(
                    LocalDate.of(2026, 3, 8),
                    pickerMillisAsExpiryDate(1_772_928_000_000L)
                )
            }
        }
    }

    @Test
    fun `空值透传且与时区无关`() {
        for (zone in zones) {
            withDefaultTimeZone(zone) {
                assertNull(expiryDateAsPickerMillis(null))
                assertNull(pickerMillisAsExpiryDate(null))
            }
        }
    }
}
