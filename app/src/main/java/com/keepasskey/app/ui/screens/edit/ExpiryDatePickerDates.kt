package com.keepasskey.app.ui.screens.edit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * ISSUE-P3-389：条目过期时间与 Material3 DatePicker 之间毫秒值的**双向 UTC 换算**纯函数。
 *
 * Material3 `DatePickerState.selectedDateMillis` 的平台契约是「所选日历日的 **UTC** 零点毫秒」；
 * 旧实现（`EntryEditExpiryEditor`）双向都按设备默认时区（`ZoneId.systemDefault()`）解释，
 * 非 UTC 时区用户（如 America/New_York、Asia/Shanghai）编辑过期时间**双向各偏一天**——
 * 选中 1 月 1 日被存成 12 月 31 日，回显后再次确认继续漂移。
 * 两个函数一律锚定 UTC，结果与设备默认时区完全无关；
 * 两端时区参数化守卫见 `app/src/test/.../edit/ExpiryDatePickerDatesTest`。
 */

/**
 * 过期日 → 选择器毫秒：取该日历日的 UTC 零点毫秒（即平台 `selectedDateMillis` 语义）。
 * `null`（未选择 / 永不过期）原样透传。
 */
internal fun expiryDateAsPickerMillis(date: LocalDate?): Long? =
    date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

/**
 * 选择器毫秒 → 过期日：按 UTC 取回年月日（零点以下不存在——平台契约保证是整日零点）。
 * `null` 原样透传。
 */
internal fun pickerMillisAsExpiryDate(millis: Long?): LocalDate? =
    millis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
