package com.keepasskey.app.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 远端目录浏览路径与条目展示辅助（对齐 keepass2android 文件选择器经验）。
 *
 * - [breadcrumbSegments]：把相对路径拆成可点击面包屑（仿安卓资源管理器 / Kp2a getParentPath）；
 * - [formatRemoteSize] / [formatRemoteModified]：PROPFIND 带来的 size / Last-Modified 展示；
 * - [isKdbxName]：选库场景默认过滤用。
 */
internal object RemoteBrowsePaths {

    /**
     * 相对路径 → 面包屑段（从根到当前）。
     * 空串 / 根 → 仅返回根标签（调用方用 `/` 或「根」）。
     */
    fun breadcrumbSegments(directoryPath: String): List<String> {
        val trimmed = directoryPath.trim().trim('/')
        if (trimmed.isEmpty()) return listOf("/")
        val parts = trimmed.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf("/")
        return buildList {
            add("/")
            parts.forEach { add(it) }
        }
    }

    /**
     * 面包屑第 [index] 段对应的**目录路径**（可直接交给 browse）。
     * index=0 → 根（空串）；index=n → 前 n 个路径段。
     */
    fun breadcrumbPathAt(segments: List<String>, index: Int): String {
        if (index <= 0) return ""
        val body = segments.drop(1).take(index)
        return body.joinToString("/")
    }

    /** 是否 KeePass 库文件名（不区分大小写）。 */
    fun isKdbxName(name: String): Boolean =
        name.trim().endsWith(".kdbx", ignoreCase = true)

    /** 条目是否应在「只看库文件」过滤下显示。 */
    fun visibleUnderKdbxOnly(entryIsDirectory: Boolean, name: String): Boolean =
        entryIsDirectory || isKdbxName(name)

    /** 大小展示：目录或未知为 0；否则 B / KiB / MiB。 */
    fun formatRemoteSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        return if (bytes < 1024L) {
            "$bytes B"
        } else if (bytes < 1024L * 1024L) {
            String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
        }
    }

    /** 最后修改时间展示；未知/0 返回空串。 */
    fun formatRemoteModified(millis: Long): String {
        if (millis <= 0L) return ""
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
    }

    /** 列表行副文案（大小 + 修改时间，空段省略）。 */
    fun entrySubtitle(contentLength: Long, lastModifiedMillis: Long): String {
        val size = formatRemoteSize(contentLength)
        val modified = formatRemoteModified(lastModifiedMillis)
        return listOf(size, modified).filter { it.isNotBlank() }.joinToString(" · ")
    }
}
