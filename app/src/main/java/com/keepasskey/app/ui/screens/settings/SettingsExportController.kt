package com.keepasskey.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.StringsProvider
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * TASK-21 拆分：设置页「导出 / 模板安装 / 调试日志导出」动作控制器。
 * 导出三件套走仓库真实序列化 + SAF 落盘，模板安装真实建组落库，
 * 调试日志导出经脱敏后写 SAF 目标 Uri；反馈文案经 [StringsProvider] 资源解析（P3-23）。
 */
internal class SettingsExportController(
    private val vaultRepository: VaultRepository,
    private val debugLogBuffer: DebugLogBuffer,
    private val appContext: Context?,
    private val strings: StringsProvider,
    private val scope: CoroutineScope
) {

    /** ISSUE-P2-10 (ZT-15)：导出审计记录器（复用 DebugLogBuffer，仅写类型与脱敏目标标识） */
    private val exportAuditRecorder = ExportAuditRecorder(debugLogBuffer)

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    // ========== 断点整改：调试日志导出（SAF CreateDocument 真实落盘） ==========
    private val debugExportFeedbackFlow = MutableStateFlow<UiMessage?>(null)

    /** SAF 另存为结果反馈（成功/失败），由 Screen 层消费后清除 */
    val debugExportFeedback: StateFlow<UiMessage?> = debugExportFeedbackFlow.asStateFlow()

    /**
     * 断点整改：真实导出调试日志——内容经脱敏（移除网址与账号字段）后写入 SAF 目标 Uri。
     * [targetUri] 由 Screen 层 CreateDocument 选择器产生；此前导出仅弹 Snackbar，从未落盘。
     */
    fun exportDebugLogs(targetUri: Uri) {
        val resolver = appContext?.contentResolver
        if (resolver == null) {
            debugExportFeedbackFlow.value = UiMessage(R.string.debug_export_failed)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val sanitizedText = debugLogBuffer.exportSanitizedText()
                val written = resolver.openOutputStream(targetUri)?.use { os ->
                    os.write(sanitizedText.toByteArray(Charsets.UTF_8))
                    os.flush()
                    true
                } ?: false
                debugExportFeedbackFlow.value =
                    if (written) UiMessage(R.string.debug_export_done)
                    else UiMessage(R.string.debug_export_failed)
            } catch (e: Exception) {
                // 只留痕异常类型，不落异常消息（防御性，避免潜在敏感内容回流日志缓冲）
                debugLogBuffer.warn(TAG, "调试日志导出失败: ${e.javaClass.simpleName}")
                debugExportFeedbackFlow.value = UiMessage(R.string.debug_export_failed)
            }
        }
    }

    fun clearDebugExportFeedback() {
        debugExportFeedbackFlow.value = null
    }

    // ========== TASK-13 整改：密码库设置页导出/模板动作真实化 ==========

    private val exportFeedbackFlow = MutableStateFlow<UiMessage?>(null)

    /** 导出/模板动作结果反馈（成功/失败），由 Screen 层消费后清除 */
    val exportFeedback: StateFlow<UiMessage?> = exportFeedbackFlow.asStateFlow()

    fun clearExportFeedback() {
        exportFeedbackFlow.value = null
    }

    /**
     * 导出当前数据库为 KDBX 完整副本（DatabaseSession.exportToBytes 产出的加密 KDBX）
     * 并写入 SAF 目标 Uri——这是 ISSUE-P2-10 明示的默认导出路径。
     */
    fun exportKdbxTo(targetUri: Uri) {
        scope.launch(Dispatchers.IO) {
            exportFeedbackFlow.value = exportAndWrite(
                targetUri, ExportArtifactKind.ENCRYPTED_KDBX, R.string.dbset_export_kdbx_done
            ) { vaultRepository.exportKdbxBytes() }
        }
    }

    /**
     * 导出当前数据库为 KeePass 2.x 兼容明文 XML 并写入 SAF 目标 Uri。
     *
     * ISSUE-P2-10 (ZT-15)：明文路径属「高级选项」，调用前必须经
     * [ExportConfirmationPolicy] 取得用户显式二次确认（由 DatabaseSettingsScreen 的确认弹窗落地）；
     * 本方法只负责在确认后真实序列化落盘，并记录脱敏审计条目。
     */
    fun exportVaultXmlTo(targetUri: Uri) {
        scope.launch(Dispatchers.IO) {
            exportFeedbackFlow.value = exportAndWrite(
                targetUri, ExportArtifactKind.PLAINTEXT_XML, R.string.dbset_export_xml_done
            ) { vaultRepository.exportVaultXmlBytes() }
        }
    }

    /** 导出会话绑定的密钥文件并写入 SAF 目标 Uri */
    fun exportKeyFileTo(targetUri: Uri) {
        scope.launch(Dispatchers.IO) {
            exportFeedbackFlow.value = exportAndWrite(
                targetUri, ExportArtifactKind.KEY_FILE, R.string.dbset_keyfile_exported
            ) { vaultRepository.exportKeyFileBytes() }
        }
    }

    /** 安装条目模板库（真实创建「模板」分组与 5 个模板条目） */
    fun installEntryTemplates() {
        scope.launch {
            val result = vaultRepository.installEntryTemplates()
            exportFeedbackFlow.value = if (result.isSuccess) {
                UiMessage(R.string.dbset_templates_installed)
            } else {
                UiMessage(
                    R.string.settings_action_failed,
                    listOf((result as com.keepasskey.core.result.KdbxResult.Failure).message)
                )
            }
        }
    }

    /** 序列化 → SAF 写盘的公共管线；任一环节失败都映射为可理解的失败反馈，并写脱敏审计条目 */
    private suspend fun exportAndWrite(
        targetUri: Uri,
        artifactKind: ExportArtifactKind,
        successMessageRes: Int,
        bytesProvider: suspend () -> com.keepasskey.core.result.KdbxResult<ByteArray>
    ): UiMessage {
        val rawTarget = targetUri.toString()
        val result = bytesProvider()
        if (!result.isSuccess) {
            val failure = result as com.keepasskey.core.result.KdbxResult.Failure
            exportAuditRecorder.record(artifactKind, rawTarget, success = false)
            return UiMessage(R.string.settings_action_failed, listOf(failure.message))
        }
        val bytes = result.getOrNull()
        val resolver = appContext?.contentResolver
        val written = if (bytes != null && resolver != null) {
            try {
                resolver.openOutputStream(targetUri)?.use { os ->
                    os.write(bytes)
                    os.flush()
                    true
                } ?: false
            } catch (e: Exception) {
                debugLogBuffer.warn(TAG, "SAF 导出写盘失败: ${e.javaClass.simpleName}")
                false
            }
        } else {
            false
        }
        // ISSUE-P2-10 (ZT-15)：审计留痕——只记时间（缓冲统一加戳）、导出类型与目标脱敏标识
        exportAuditRecorder.record(artifactKind, rawTarget, success = written)
        return if (written) {
            UiMessage(successMessageRes)
        } else {
            UiMessage(R.string.settings_action_failed, listOf(strings.get(R.string.export_saf_write_failed)))
        }
    }
}

/**
 * ISSUE-P2-10 (ZT-15)：导出制品类型（审计分类与风险分级共用）。
 */
internal enum class ExportArtifactKind(val auditLabel: String) {
    /** 加密 KDBX：默认导出路径，受主密码保护 */
    ENCRYPTED_KDBX("加密 KDBX"),

    /** KeePass 2.x 兼容明文 XML：高级选项，必须先取得显式二次确认 */
    PLAINTEXT_XML("明文 XML"),

    /** 会话绑定密钥文件：单独备份，泄漏即可配合密文开库 */
    KEY_FILE("密钥文件"),

    /** 条目附件的解密后明文：必须先取得显式二次确认 */
    ATTACHMENT("明文附件")
}

/**
 * ISSUE-P2-10 (ZT-15)：明文导出二次确认决策内核（纯 Kotlin，可 JVM 单测）。
 *
 * 加密 KDBX 属默认安全路径，无需确认；明文 XML / 明文附件必须先取得显式确认，
 * 缺失确认一律 fail-closed（不导出）。
 */
internal object ExportConfirmationPolicy {

    /** 导出风险分级 */
    enum class Risk {
        /** 加密制品：受主密码保护，可直接导出 */
        ENCRYPTED,

        /** 明文制品：出域前必须显式授权 */
        PLAINTEXT
    }

    /** 决策结果 */
    enum class Decision {
        /** 放行导出 */
        PROCEED,

        /** 缺少显式确认：fail-closed，不得写出任何字节 */
        REQUIRE_CONFIRMATION
    }

    /**
     * 依据风险等级与用户确认状态给出决策：
     * 加密导出恒放行；明文导出仅在 [confirmed] 为 true 时放行。
     */
    fun decide(risk: Risk, confirmed: Boolean): Decision =
        if (risk == Risk.PLAINTEXT && !confirmed) {
            Decision.REQUIRE_CONFIRMATION
        } else {
            Decision.PROCEED
        }

    /** 是否允许导出（[decide] 的布尔便捷形式） */
    fun allows(risk: Risk, confirmed: Boolean): Boolean =
        decide(risk, confirmed) == Decision.PROCEED

    /** 由制品类型推导风险等级：仅加密 KDBX 免确认，其余均为明文/敏感制品 */
    fun riskOf(kind: ExportArtifactKind): Risk = when (kind) {
        ExportArtifactKind.ENCRYPTED_KDBX -> Risk.ENCRYPTED
        ExportArtifactKind.PLAINTEXT_XML,
        ExportArtifactKind.KEY_FILE,
        ExportArtifactKind.ATTACHMENT -> Risk.PLAINTEXT
    }
}

/**
 * ISSUE-P2-10 (ZT-15)：导出目标脱敏（纯 Kotlin，可 JVM 单测）。
 *
 * 审计条目严禁出现完整路径、文件名或任何明文内容：仅保留 scheme 与 authority
 * （存储提供方标识，便于判断落点类型），其余部分折叠为定长短摘要，
 * 既可用于同一目的事件关联，又不泄露用户目录/文件名。
 */
internal object ExportAuditSanitizer {

    private const val DIGEST_HEX_LENGTH = 8
    private const val UNKNOWN_TARGET = "<unknown>"
    private const val SCHEME_SEPARATOR = "://"
    private const val MARKER_SEPARATOR = "#"
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F

    /** 生成形如 content://provider.authority#1a2b3c4d 的脱敏标识 */
    fun targetMarker(rawTarget: String): String {
        val trimmed = rawTarget.trim()
        if (trimmed.isEmpty()) return UNKNOWN_TARGET
        val scheme = trimmed.substringBefore(SCHEME_SEPARATOR, "")
        val authority = trimmed.substringAfter(SCHEME_SEPARATOR, "")
            .substringBefore('/')
            .substringBefore('?')
        val prefix = if (scheme.isEmpty()) "" else "$scheme$SCHEME_SEPARATOR"
        return "$prefix$authority$MARKER_SEPARATOR${shortDigest(trimmed)}"
    }

    private fun shortDigest(raw: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(DIGEST_HEX_LENGTH)
        for (byte in bytes) {
            val value = byte.toInt() and NIBBLE_MASK
            builder.append(HEX_DIGITS[value ushr NIBBLE_BITS]).append(HEX_DIGITS[value and NIBBLE_MASK])
            if (builder.length >= DIGEST_HEX_LENGTH) break
        }
        return builder.toString()
    }
}

/**
 * ISSUE-P2-10 (ZT-15)：导出审计落痕（复用 [DebugLogBuffer]，遵循日志脱敏铁律）。
 *
 * 仅记录时间（由缓冲统一加时间戳）、导出类型与目标脱敏标识；绝不记录文件名、路径、
 * 字段值或任何明文内容。导出成功与失败都会留痕，便于事后回溯数据出域行为。
 *
 * ISSUE-P3-03 (43f)：改用 [DebugLogBuffer.audit] 审计通道——审计留痕是治理要求，
 * 不能被用户侧「诊断日志」开关静默关闭（普通诊断事件才受该开关约束）。
 */
internal class ExportAuditRecorder(private val debugLog: DebugLogBuffer) {

    fun record(kind: ExportArtifactKind, rawTarget: String, success: Boolean) {
        val resultLabel = if (success) RESULT_SUCCESS else RESULT_FAILURE
        val marker = ExportAuditSanitizer.targetMarker(rawTarget)
        debugLog.audit(AUDIT_TAG, "导出审计: 类型=${kind.auditLabel}, 目标=$marker, 结果=$resultLabel")
    }

    private companion object {
        const val AUDIT_TAG = "ExportAudit"
        const val RESULT_SUCCESS = "成功"
        const val RESULT_FAILURE = "失败"
    }
}
