package com.keepasskey.app.data.repository

import android.content.Context
import android.net.Uri
import com.keepasskey.app.security.VaultFileMetadataProbe
import com.keepasskey.app.util.StreamLimitExceededException
import com.keepasskey.app.util.readBoundedBytes
import com.keepasskey.core.log.AppLog
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * `ISSUE-P1-631`（结构拆分）：漂移处置的**磁盘源访问**——按通道取当前可读字节与 SAF 文档句柄。
 *
 * 自 [VaultFileDriftResolve] 拆出（行数门禁 + 职责单一）：本件只回答「当前库的字节从哪读、
 * 读不到怎么办」，不涉及基线、合并、裁决与提交。上限 [maxDriftReadBytes] 仍由调用方
 * （生产值 `VaultFileDriftResolve.MAX_DRIFT_READ_BYTES`）注入，单测可注入更小值低成本覆盖超限分支。
 */
internal class VaultFileDriftSourceAccess(
    private val context: Context?,
    /** 源文件读取上限（`ISSUE-P3-617`）：超限按「不可读」同口径返回 null，不抛 OOM */
    private val maxDriftReadBytes: Long
) {

    /**
 * 当前库的**可读字节**（本地 `File` 直读；SAF 经 `ContentResolver` 读入）。
 * 不可读返回 null，由调用方按 fail-closed 报「磁盘库文件不可读」。
 *
 * `ISSUE-P3-617`：两处整流 `readBytes()` 改为带硬上限的分块读取——未知或错误申报长度
 * 可绕过读取前检查，超大输入会在解析限制生效前分配完整 `ByteArray`（OOM/ANR）。
 * 本地文件先按 `length()` 快拒（精确长度已知，不建流）；SAF 流（含未知长度）一律由
 * [readBoundedBytes] 累计封顶，超限按不可读同口径返回 null（`resolve` 侧报合并 / 重载失败，
 * 不抛 OOM）。上限见 [MAX_DRIFT_READ_BYTES]。
 */
fun readCurrentBytes(localFile: File?, pathId: String): ByteArray? {
    if (localFile != null) {
        if (!localFile.exists()) return null
        // 快拒：本地文件长度精确已知，超限根本不读进内存
        if (localFile.length() > maxDriftReadBytes) {
            AppLog.w(DRIFT_LOG_TAG, "漂移源文件超出读取上限（>${maxDriftReadBytes} 字节），拒绝合并")
            return null
        }
        return runCatching { localFile.inputStream().use { it.readBoundedBytes(maxDriftReadBytes) } }
            .onFailure { AppLog.w(DRIFT_LOG_TAG, "漂移源文件读取失败: ${it.javaClass.simpleName}") }
            .getOrNull()
    }
    val saf = safChannel(pathId) ?: return null
    return runCatching { saf.openInput().use { it.readBoundedBytes(maxDriftReadBytes) } }
        .onFailure {
            // 超限与 IO 失败同按不可读处置（日志只记类型与上限，不记路径——ISSUE-P3-550）
            if (it is StreamLimitExceededException) {
                AppLog.w(DRIFT_LOG_TAG, "漂移源 SAF 流超出读取上限（>${maxDriftReadBytes} 字节），拒绝合并")
            } else {
                AppLog.w(DRIFT_LOG_TAG, "漂移源 SAF 流读取失败: ${it.javaClass.simpleName}")
            }
        }
        .getOrNull()
}

/** SAF 文档句柄（读流 + 写回）；非 `content://` 或未装配上下文时返回 null */
fun safChannel(pathId: String): SafChannel? {
    val ctx = context ?: return null
    if (!VaultFileMetadataProbe.isSafPath(pathId)) return null
    val uri = runCatching { Uri.parse(pathId) }.getOrNull() ?: return null
    return SafChannel(ctx, uri)
}

/** SAF 文档句柄：读流与写回共用同一 Uri 与上下文（写回实现与解锁 / 建库共用同一份） */
class SafChannel(private val context: Context, private val uri: Uri) {

    fun openInput(): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开数据库文件流")

    val saveWriter: suspend (ByteArray) -> Unit
        get() = SafVaultCreation.saveWriter(context, uri, uri.lastPathSegment.orEmpty())
}
}
