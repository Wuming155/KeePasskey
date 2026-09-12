package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.logger.DebugLogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-10 (ZT-15) 导出治理纯内核单测。
 *
 * 覆盖四条验收基线：
 * 1. 确认缺失时不导出（fail-closed）；
 * 2. 加密 KDBX 为默认免确认路径；
 * 3. 明文 XML / 附件必须显式确认；
 * 4. 审计条目写入且不含完整路径、文件名或任何敏感内容。
 */
class ExportConfirmationPolicyTest {

    @Test
    fun `未确认时明文导出 fail-closed`() {
        assertEquals(
            ExportConfirmationPolicy.Decision.REQUIRE_CONFIRMATION,
            ExportConfirmationPolicy.decide(ExportConfirmationPolicy.Risk.PLAINTEXT, confirmed = false)
        )
        assertFalse(
            ExportConfirmationPolicy.allows(ExportConfirmationPolicy.Risk.PLAINTEXT, confirmed = false)
        )
    }

    @Test
    fun `显式确认后明文导出放行`() {
        assertTrue(
            ExportConfirmationPolicy.allows(ExportConfirmationPolicy.Risk.PLAINTEXT, confirmed = true)
        )
    }

    @Test
    fun `加密 KDBX 为默认路径且无需确认`() {
        assertEquals(
            ExportConfirmationPolicy.Risk.ENCRYPTED,
            ExportConfirmationPolicy.riskOf(ExportArtifactKind.ENCRYPTED_KDBX)
        )
        assertEquals(
            ExportConfirmationPolicy.Decision.PROCEED,
            ExportConfirmationPolicy.decide(ExportConfirmationPolicy.Risk.ENCRYPTED, confirmed = false)
        )
    }

    @Test
    fun `明文 XML 与附件归入明文风险等级`() {
        assertEquals(
            ExportConfirmationPolicy.Risk.PLAINTEXT,
            ExportConfirmationPolicy.riskOf(ExportArtifactKind.PLAINTEXT_XML)
        )
        assertEquals(
            ExportConfirmationPolicy.Risk.PLAINTEXT,
            ExportConfirmationPolicy.riskOf(ExportArtifactKind.ATTACHMENT)
        )
    }

    @Test
    fun `明文 CSV 亦归入明文风险等级且未确认时不放行`() {
        assertEquals(
            ExportConfirmationPolicy.Risk.PLAINTEXT,
            ExportConfirmationPolicy.riskOf(ExportArtifactKind.PLAINTEXT_CSV)
        )
        assertFalse(
            ExportConfirmationPolicy.allows(ExportConfirmationPolicy.Risk.PLAINTEXT, confirmed = false)
        )
        assertTrue(
            ExportConfirmationPolicy.allows(ExportConfirmationPolicy.Risk.PLAINTEXT, confirmed = true)
        )
    }

    @Test
    fun `目标脱敏只保留 scheme 与 authority 并剥离文件名`() {
        val marker = ExportAuditSanitizer.targetMarker(SENSITIVE_TARGET)

        assertTrue(marker.startsWith("content://$DOWNLOAD_PROVIDER"))
        assertTrue(marker.contains("#"))
        assertFalse("脱敏标识不得包含文件名", marker.contains("MySecretVault"))
        assertFalse("脱敏标识不得包含目录名", marker.contains("Download"))
        assertFalse("脱敏标识不得包含原始路径片段", marker.contains("primary"))
    }

    @Test
    fun `目标脱敏对空值给出占位符且对同一目标稳定`() {
        assertEquals("<unknown>", ExportAuditSanitizer.targetMarker(""))
        assertEquals(
            ExportAuditSanitizer.targetMarker(SENSITIVE_TARGET),
            ExportAuditSanitizer.targetMarker(SENSITIVE_TARGET)
        )
    }

    @Test
    fun `审计条目含时间类型与脱敏目标但不含敏感内容`() {
        val buffer = DebugLogBuffer()
        val recorder = ExportAuditRecorder(buffer)

        recorder.record(ExportArtifactKind.PLAINTEXT_XML, SENSITIVE_TARGET, success = true)

        val line = buffer.snapshot().single()
        assertTrue("审计须含导出类型", line.contains(ExportArtifactKind.PLAINTEXT_XML.auditLabel))
        assertTrue("审计须含脱敏目标标识", line.contains(ExportAuditSanitizer.targetMarker(SENSITIVE_TARGET)))
        assertTrue("审计须含结果", line.contains("成功"))
        assertTrue("审计须带时间戳", TIMESTAMP_PATTERN.containsMatchIn(line))
        assertFalse("审计严禁回写文件名", line.contains("MySecretVault"))
    }

    @Test
    fun `审计对附件类型与失败结果同样留痕`() {
        val buffer = DebugLogBuffer()
        val recorder = ExportAuditRecorder(buffer)

        recorder.record(ExportArtifactKind.ATTACHMENT, SENSITIVE_TARGET, success = false)

        val line = buffer.snapshot().single()
        assertTrue(line.contains(ExportArtifactKind.ATTACHMENT.auditLabel))
        assertTrue(line.contains("失败"))
        assertFalse(line.contains("MySecretVault"))
    }

    companion object {
        private const val DOWNLOAD_PROVIDER = "com.android.providers.downloads.documents"
        // 故意包含文件名与目录名，用于验证脱敏后不泄漏
        private const val SENSITIVE_TARGET =
            "content://$DOWNLOAD_PROVIDER/document/primary%3ADownload%2FMySecretVault.csv"
        private val TIMESTAMP_PATTERN = Regex("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}]")
    }
}
