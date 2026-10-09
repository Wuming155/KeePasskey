package com.keepasskey.app.ui.model

import com.keepasskey.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 「今天 / 昨天 / M月d日 HH:mm」相对时间文案的**统一出口**（ISSUE-P3-552）。
 *
 * ## 为什么需要它
 *
 * 同一套判据（`today` / `today-1` / `else`）+ 同一套文案资源（`time_today` /
 * `time_yesterday` / `date_pattern_month_day`）此前在三处**逐字重复**
 * （`VaultListSyncController` / `SettingsSyncController` / `ConflictResolutionViewModel`），
 * 且每处**每次调用都重编译** `DateTimeFormatter.ofPattern("HH:mm")`——时间格式化是
 * 列表滚动与设置页刷新热路径上的无谓开销（同仓 `VaultEntryMapper` 早已为此引入
 * `ConcurrentHashMap<Locale, DateTimeFormatter>` 缓存并注释此坑，本处是同一坑的未收敛面）。
 *
 * ## 行为契约（与整改前逐字等价）
 *
 * - 日期前缀：`今天` / `昨天` / 其余走 `date_pattern_month_day` 资源所给的 pattern；
 * - 时间后缀恒为 `HH:mm`，与前缀以单个空格连接；
 * - 「从未同步」由调用方以 [neverText] 显式给出——可空入参统一处理，不再各写一份 `if (millis <= 0)`。
 *
 * pattern → `DateTimeFormatter` 一律缓存（`ofPattern` 的解析开销不可忽略，见上）。
 */
object RelativeTimeFormatter {

    /** 时间后缀 pattern（与整改前三处硬编码逐字一致） */
    private const val PATTERN_TIME = "HH:mm"

    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(PATTERN_TIME)

    /**
     * 月-日 pattern 缓存：pattern 串来自**资源**（随语言切换可变），故按 pattern 串缓存。
     * `ConcurrentHashMap`：本出口同时被列表滚动（主线程）与设置页刷新（IO→Main）触达。
     */
    private val MONTH_DAY_FORMATTERS = ConcurrentHashMap<String, DateTimeFormatter>()

    /** 纯日期 pattern（条目过期日等处原各自硬编码 `"yyyy-MM-dd"`，同批收敛为常量） */
    const val PATTERN_ISO_DATE = "yyyy-MM-dd"

    private val ISO_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(PATTERN_ISO_DATE)

    /**
     * 时间戳（毫秒）→ 相对时间文案。
     *
     * @param millis `<= 0` 表示「本会话尚未执行过」——恒返回 [neverText]，不落日期分支。
     */
    fun formatMillis(strings: StringsProvider, millis: Long, neverText: String): String {
        if (millis <= 0L) return neverText
        return formatInstant(strings, Instant.ofEpochMilli(millis))
    }

    /** [Instant] → 相对时间文案（无「从未」态的调用方用本重载）。 */
    fun formatInstant(strings: StringsProvider, instant: Instant): String =
        render(strings, instant.atZone(ZoneId.systemDefault()))

    /** 纯日期（`yyyy-MM-dd`），用于条目过期日等无时间分量的场景。 */
    fun formatIsoDate(instant: Instant): String =
        instant.atZone(ZoneId.systemDefault()).format(ISO_DATE_FORMATTER)

    private fun render(strings: StringsProvider, local: ZonedDateTime): String {
        val prefix = datePrefix(strings, local)
        return "$prefix ${local.format(TIME_FORMATTER)}"
    }

    private fun datePrefix(strings: StringsProvider, local: ZonedDateTime): String {
        val today = LocalDate.now()
        return when (local.toLocalDate()) {
            today -> strings.get(R.string.time_today)
            today.minusDays(1) -> strings.get(R.string.time_yesterday)
            else -> local.format(monthDayFormatter(strings))
        }
    }

    private fun monthDayFormatter(strings: StringsProvider): DateTimeFormatter {
        val pattern = strings.get(R.string.date_pattern_month_day)
        return MONTH_DAY_FORMATTERS.getOrPut(pattern) { DateTimeFormatter.ofPattern(pattern) }
    }
}
