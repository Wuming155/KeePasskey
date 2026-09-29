package com.keepasskey.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import com.keepasskey.app.data.importer.ImportSource
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.app.ui.screens.importer.VaultImportController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 明文导入 + `.kdbx` 并入的 UI 侧门面（ISSUE-P3-29：自 `SettingsViewModel.kt` 拆出）。
 *
 * ISSUE-P3-19（ZT-43d）：全链路由 [VaultImportController] 承载，
 * 本类只做「控制器缺失时恒 Idle」的如实回落 —— 不呈现任何导入反馈。
 *
 * ISSUE-P3-384：`.kdbx` 并入状态与凭据提交经同一控制器。
 */
internal class SettingsImportPresenter(
    private val controller: VaultImportController?,
    private val appContext: Context? = null
) {

    /** 导入状态（Idle / Parsing / Done / Failed）。 */
    val state: StateFlow<ImportUiState> = controller?.uiState ?: MutableStateFlow(ImportUiState.Idle)

    /** ISSUE-P3-384：`.kdbx` 并入状态。 */
    val mergeState: StateFlow<ImportUiState> = controller?.mergeUiState ?: MutableStateFlow(ImportUiState.Idle)

    /** 按数据源 + SAF Uri 启动一次导入。 */
    fun start(source: ImportSource, uri: Uri) {
        controller?.startImport(source, uri)
    }

    /** 关闭导入结果报告对话框。 */
    fun dismissReport() {
        controller?.reset()
    }

    /** ISSUE-P2-354 AC④：取消进行中的导入。 */
    fun cancel() {
        controller?.cancelImport()
    }

    /** ISSUE-P3-384：提交第二库凭据（密码 + 已读出的密钥文件字节）。 */
    fun submitMergeCredentials(passwordChars: CharArray, keyFileData: ByteArray? = null) {
        controller?.submitMergeCredentials(passwordChars, keyFileData)
    }

    /**
     * ISSUE-P3-384：UI 入口——密码 + 可选密钥文件 Uri。
     * 字节读取与清零在本方法完成；无 Context / 读取失败时仅用密码。
     */
    fun submitMergeCredentials(passwordChars: CharArray, keyFileUri: Uri?) {
        val ctx = appContext
        val keyBytes = keyFileUri?.let { uri ->
            try {
                ctx?.contentResolver?.openInputStream(uri)?.use { it.readBytes() }
            } catch (t: Throwable) {
                null
            }
        }
        controller?.submitMergeCredentials(passwordChars, keyBytes)
    }

    /** ISSUE-P3-384：取消等待凭据 / 进行中的并入。 */
    fun cancelMerge() {
        controller?.cancelMerge()
    }

    /** ISSUE-P3-384：关闭并入报告。 */
    fun dismissMergeReport() {
        controller?.resetMerge()
    }
}
