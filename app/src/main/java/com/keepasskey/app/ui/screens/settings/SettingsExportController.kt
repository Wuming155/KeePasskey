package com.keepasskey.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.StringsProvider
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

    /** 导出当前数据库为 KDBX 完整副本并写入 SAF 目标 Uri */
    fun exportKdbxTo(targetUri: Uri) {
        scope.launch(Dispatchers.IO) {
            exportFeedbackFlow.value = exportAndWrite(
                targetUri, R.string.dbset_export_kdbx_done
            ) { vaultRepository.exportKdbxBytes() }
        }
    }

    /** 导出当前数据库为 KeePass 2.x 兼容明文 XML 并写入 SAF 目标 Uri */
    fun exportVaultXmlTo(targetUri: Uri) {
        scope.launch(Dispatchers.IO) {
            exportFeedbackFlow.value = exportAndWrite(
                targetUri, R.string.dbset_export_xml_done
            ) { vaultRepository.exportVaultXmlBytes() }
        }
    }

    /** 导出会话绑定的密钥文件并写入 SAF 目标 Uri */
    fun exportKeyFileTo(targetUri: Uri) {
        scope.launch(Dispatchers.IO) {
            exportFeedbackFlow.value = exportAndWrite(
                targetUri, R.string.dbset_keyfile_exported
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

    /** 序列化 → SAF 写盘的公共管线；任一环节失败都映射为可理解的失败反馈 */
    private suspend fun exportAndWrite(
        targetUri: Uri,
        successMessageRes: Int,
        bytesProvider: suspend () -> com.keepasskey.core.result.KdbxResult<ByteArray>
    ): UiMessage {
        val result = bytesProvider()
        if (!result.isSuccess) {
            val failure = result as com.keepasskey.core.result.KdbxResult.Failure
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
        return if (written) {
            UiMessage(successMessageRes)
        } else {
            UiMessage(R.string.settings_action_failed, listOf(strings.get(R.string.export_saf_write_failed)))
        }
    }
}
