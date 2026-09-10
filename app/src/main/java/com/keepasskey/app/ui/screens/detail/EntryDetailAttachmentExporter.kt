package com.keepasskey.app.ui.screens.detail

import android.content.Context
import android.net.Uri
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.settings.ExportArtifactKind
import com.keepasskey.app.ui.screens.settings.ExportAuditRecorder

/**
 * 附件明文导出的写出与审计留痕（断点3 整改 / ISSUE-P2-10 (ZT-15)）。
 *
 * ISSUE-P3-31 批次 C：由 `EntryDetailViewModel`（原 708 行）按**纯结构性拆分**搬出。
 * 三条早退分支（附件字节缺失 / `ContentResolver` 缺失 / 输出流为 null）
 * 与「每条分支都先记一次失败审计」的顺序**逐字保留**。
 *
 * 注意：用户确认（[ExportConfirmationPolicy] 的 fail-closed 判定）**仍在调用方**完成——
 * 本类只在已获授权后执行写出，绝不自行放行。
 */
internal class EntryDetailAttachmentExporter(
    private val vaultRepository: VaultRepository,
    private val appContext: Context?,
    private val exportAuditRecorder: ExportAuditRecorder?,
    private val debugLog: DebugLogBuffer?
) {

    /**
     * 把 [attachment] 的解密字节写入 [targetUri]，并记录审计。
     *
     * @return 面向用户的结果消息（成功提示或如实失败提示），由调用方下发到 UI 状态。
     */
    suspend fun export(entryId: String, attachment: UiAttachment, targetUri: Uri): UiMessage {
        val rawTarget = targetUri.toString()
        return try {
            val bytes = vaultRepository.getAttachmentData(entryId, attachment.fileName)
            if (bytes == null) return recordFailure(rawTarget)

            val resolver = appContext?.contentResolver ?: return recordFailure(rawTarget)
            val stream = resolver.openOutputStream(targetUri) ?: return recordFailure(rawTarget)

            stream.use { os ->
                os.write(bytes)
                os.flush()
            }
            exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = true)
            UiMessage(R.string.detail_attachment_export_toast, listOf(attachment.fileName))
        } catch (e: Exception) {
            // 只留痕异常类型，不落异常消息或附件名（防御性，避免敏感内容回流日志缓冲）
            exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = false)
            debugLog?.warn(TAG, "附件导出失败: ${e.javaClass.simpleName}")
            UiMessage(R.string.detail_attachment_export_failed)
        }
    }

    private fun recordFailure(rawTarget: String): UiMessage {
        exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = false)
        return UiMessage(R.string.detail_attachment_export_failed)
    }

    private companion object {
        private const val TAG = "EntryDetailViewModel"
    }
}
