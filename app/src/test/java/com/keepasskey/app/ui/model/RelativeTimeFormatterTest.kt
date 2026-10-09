package com.keepasskey.app.ui.model

import com.keepasskey.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * [RelativeTimeFormatter] 的 Locale 口径单测（`ISSUE-P3-560` ③）：
 * 中文 `M月d日` 与英文 `MMM d` 两 Locale × 「今天 / 昨天 / 其余」三支。
 *
 * 整改前 `DateTimeFormatter.ofPattern(pattern)` **未传 Locale**（取创建时的默认 Locale）
 * ⇒ 英文资源 `MMM d` 在非英文系统 Locale 下渲染出「10月 9 14:30」这类**混合语言**文案。
 */
class RelativeTimeFormatterTest {

    private fun provider(locale: Locale?, pattern: String): StringsProvider = object : StringsProvider {
        override fun get(id: Int, vararg args: Any?): String = when (id) {
            R.string.time_today -> "今天"
            R.string.time_yesterday -> "昨天"
            R.string.date_pattern_month_day -> pattern
            else -> ""
        }

        override val locale: Locale? get() = locale
    }

    private fun instantAt(date: LocalDate, hour: Int = 14, minute: Int = 30): Instant =
        date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant()

    @Test
    fun `中文 locale 其余日期按 M月d日 渲染`() {
        val target = LocalDate.now().minusDays(10)
        val text = RelativeTimeFormatter.formatInstant(
            provider(Locale.SIMPLIFIED_CHINESE, "M月d日"),
            instantAt(target)
        )
        assertEquals("${target.monthValue}月${target.dayOfMonth}日 14:30", text)
    }

    @Test
    fun `英文 pattern 在中文系统 Locale 下仍按应用内英文渲染`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            val target = LocalDate.now().minusDays(10)
            val expectedPrefix = target.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))

            val text = RelativeTimeFormatter.formatInstant(provider(Locale.ENGLISH, "MMM d"), instantAt(target))

            assertEquals("英文资源 + 英文应用内语言不得按系统 Locale 渲染：[$text]", "$expectedPrefix 14:30", text)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `今天与昨天两支按资源文案且与 Locale 无关`() {
        val today = LocalDate.now()
        for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
            val pattern = if (locale == Locale.ENGLISH) "MMM d" else "M月d日"
            val provider = provider(locale, pattern)
            val time = today.atTime(14, 30).format(DateTimeFormatter.ofPattern("HH:mm", locale))
            assertEquals(
                "今天 $time",
                RelativeTimeFormatter.formatInstant(provider, instantAt(today))
            )
            assertEquals(
                "昨天 $time",
                RelativeTimeFormatter.formatInstant(provider, instantAt(today.minusDays(1)))
            )
        }
    }

    @Test
    fun `毫秒入参为零或负值恒返回从未文案`() {
        val provider = provider(Locale.SIMPLIFIED_CHINESE, "M月d日")
        assertEquals("从未同步", RelativeTimeFormatter.formatMillis(provider, 0L, "从未同步"))
        assertEquals("从未同步", RelativeTimeFormatter.formatMillis(provider, -1L, "从未同步"))
    }

    @Test
    fun `纯日期出口按 yyyy-MM-dd 渲染`() {
        val target = LocalDate.now().minusDays(10)
        val expected = target.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()))
        assertEquals(expected, RelativeTimeFormatter.formatIsoDate(instantAt(target)))
    }
}
