package com.keepasskey.sync.s3

import com.keepasskey.core.log.AppLog
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * S3 **XML 元素**时间解析协作单元（`ISSUE-P3-656`）。
 *
 * 与 [S3HttpDateCodec] 的分工是**协议归口**，不可互换：
 * - HTTP 响应头（`Date` / `Last-Modified`）是 RFC 1123 文本，归 [S3HttpDateCodec]；
 * - `ListObjectsV2` 响应体里的 `<LastModified>` 是 **ISO-8601**（如 `2026-10-06T00:00:00.000Z`），
 *   归本单元。
 *
 * 整改前 `S3DirectoryList` 把 XML 时间交给了只认 RFC 1123 的 [S3HttpDateCodec]，解析必然失败并
 * 静默回 0——目录列表里的真实修改时间全部丢失。因此本单元**只**处理 ISO-8601，不排除复用
 * HTTP parser 的可能（两者语义不同，混用即缺陷）。
 *
 * 失败口径：无法解析返回 `null`（调用方据此保留「未知」，不伪造时间、不抛异常中断整页列举）。
 * 合规 S3 恒为 UTC（`Z`）；对缺失时区的非规范实现按 UTC 解释，避免把可用时间降级成未知。
 */
internal object S3XmlTimeCodec {

    private const val LOG_TAG = "S3XmlTimeCodec"

    /**
     * 解析 ISO-8601 时间戳为 epoch 毫秒；空串 / 非法值返回 `null`。
     *
     * 支持三种形态（按序尝试，全部按 UTC 归一）：
     * 1. `2026-10-06T00:00:00.000Z` / `2026-10-06T00:00:00Z`（[Instant.parse]）；
     * 2. `2026-10-06T08:00:00+08:00`（[OffsetDateTime]）；
     * 3. `2026-10-06T00:00:00`（非规范实现省略时区，按 UTC 解释）。
     */
    fun parseLastModified(text: String?): Long? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        parseInstant(value)?.let { return it.toEpochMilli() }
        parseOffset(value)?.let { return it.toEpochMilli() }
        parseLocalAsUtc(value)?.let { return it.toEpochMilli() }
        // 只记录「存在但无法解析」这一事实，不回显原始值以外的东西（时间是公开元数据）
        AppLog.w(LOG_TAG, "S3 XML 时间无法按 ISO-8601 解析，降级为未知")
        return null
    }

    private fun parseInstant(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun parseOffset(value: String): Instant? = try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun parseLocalAsUtc(value: String): Instant? = try {
        LocalDateTime.parse(value).toInstant(ZoneOffset.UTC)
    } catch (_: DateTimeParseException) {
        null
    }
}
