package com.keepasskey.sync.s3

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * HTTP 日期解析协作单元。
 *
 * 用于解析 S3 响应头中的 RFC 1123 日期（`Last-Modified` 与时钟偏移补偿用的 `Date`）。
 * 解析失败或空串一律返回 0，交由调用方 fail-closed 处理（不补偿、保持现状）。
 */
internal object S3HttpDateCodec {

    private const val HTTP_DATE_PATTERN = "EEE, dd MMM yyyy HH:mm:ss zzz"
    private const val GMT_TIME_ZONE_ID = "GMT"

    /** 解析 RFC 1123 日期为毫秒时间戳；空串 / 解析失败返回 0。 */
    fun parse(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        try {
            val sdf = SimpleDateFormat(HTTP_DATE_PATTERN, Locale.US).apply {
                timeZone = TimeZone.getTimeZone(GMT_TIME_ZONE_ID)
            }
            return sdf.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            return 0L
        }
    }
}
