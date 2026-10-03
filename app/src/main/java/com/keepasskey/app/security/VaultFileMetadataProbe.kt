package com.keepasskey.app.security

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

/**
 * `ISSUE-P3-447` AC②：库文件**元数据探针**——本地 `File` 与 SAF `content://` 两通道的统一基线入口。
 *
 * ## 为什么需要它
 *
 * `ISSUE-P2-378` 的外部修改漂移防护要求「打开」与「保存」两时点各取一次真实元数据
 * （`lastModified` + `size`）。本地 `File` 通道一直直读文件属性；SAF 通道此前**没有**元数据管道——
 * 打开时落的是 `fromMetadata(path, 0, 0)` 占位假值、保存时 `current` 恒 `null`，
 * 于是「假基线与 null 现值相遇」按策略恒判漂移，SAF 库**每次保存**都被中止
 * （2026-10-02 临时止血：`file == null` 时干脆不落基线，代价是 SAF 通道漂移防护整体缺位）。
 * 本探针即替换止血口径的真实元数据来源。
 *
 * ## 口径
 *
 * - **本地 `File`**：直读 `File.length()` / `File.lastModified()`（`fromFile`）。
 * - **SAF `content://`**：经 `ContentResolver.query` 取 `OpenableColumns.SIZE` 与
 *   `DocumentsContract.Document.COLUMN_LAST_MODIFIED`（**毫秒**，与 `File.lastModified()` 同单位）。
 * - **元数据不可读**（无上下文 / 查询失败 / 提供方不返回该列）⇒ 返回 `null`，
 *   由调用方按「宁可不提示」放行（**不得**误报漂移——提供方不暴露元数据不等于文件被改）。
 *   缺列时该维度按 0 参与比对：size 仍可判漂移，`lastModified` 两侧同为 0 则不判——
 *   这是「可检测面收窄」而非「谎报未漂移」，与 `VaultFileDriftPolicy` 的既有容差口径一致。
 *
 * 探针只读元数据，**不读取库内容**，也不产生任何敏感数据。
 */
internal object VaultFileMetadataProbe {

    /**
     * 按 [pathIdentifier] 通道取基线；元数据不可读（含本地文件不存在）返回 `null`。
     *
     * @param context SAF 通道必需；null（纯 JVM 单测未装配）时 SAF 通道返回 null
     */
    fun baselineFor(context: Context?, pathIdentifier: String): VaultFileBaseline? {
        if (pathIdentifier.isBlank()) return null
        return if (pathIdentifier.startsWith(SAF_SCHEME)) {
            safBaseline(context, pathIdentifier)
        } else {
            VaultFileBaseline.fromFile(File(pathIdentifier))
        }
    }

    /** 判断是否为 SAF / `content://` 通道的路径标识（本地 `File` 与 SAF 的分流判据单点化） */
    fun isSafPath(pathIdentifier: String?): Boolean =
        pathIdentifier?.startsWith(SAF_SCHEME) == true

    private fun safBaseline(context: Context?, pathIdentifier: String): VaultFileBaseline? {
        val resolverContext = context ?: return null
        val uri = runCatching { Uri.parse(pathIdentifier) }.getOrNull() ?: return null
        val metadata = runCatching {
            resolverContext.contentResolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val mtimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                val mtime = if (mtimeIndex >= 0 && !cursor.isNull(mtimeIndex)) cursor.getLong(mtimeIndex) else 0L
                mtime to size
            }
        }.getOrNull() ?: return null
        return VaultFileBaseline.fromMetadata(
            pathIdentifier = pathIdentifier,
            lastModifiedMillis = metadata.first,
            sizeBytes = metadata.second
        )
    }

    private const val SAF_SCHEME = "content://"

    /** 只查需要的两列：`size` 与 `last_modified`（不取显示名等无关元数据） */
    private val PROJECTION = arrayOf(
        OpenableColumns.SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )
}
