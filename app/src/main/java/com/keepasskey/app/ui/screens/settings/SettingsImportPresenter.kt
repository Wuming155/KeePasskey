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

    /**
     * ISSUE-P3-384：提交第二库凭据（密码 + 已读出的密钥文件字节）。
     *
     * **原件清零收口点（ISSUE-P3-580）**：提交即消费（数组所有权随向下透传转移），本方法在
     * **返回前**（正常 / 异常 / 控制器缺失三条路径一致）对两原件 `fill(0)`——这是本链**唯一**的
     * 原件清零点：下游 `VaultImportController.submitMergeCredentials` 与
     * `KdbxMergeController.submitCredentials` 均为只读借用（后者只清自身 `copyOf` 副本，见其 KDoc），
     * 故此处清零不触及后台消费用的副本。
     */
    fun submitMergeCredentials(passwordChars: CharArray, keyFileData: ByteArray? = null) {
        try {
            controller?.submitMergeCredentials(passwordChars, keyFileData)
        } finally {
            passwordChars.fill('0')
            keyFileData?.fill(0)
        }
    }

    /**
     * ISSUE-P3-384：UI 入口——密码 + 可选密钥文件 Uri。
     *
     * 控制器同步复制密码后，在受控作业中经 KeyFileAccess 异步读取所选密钥文件。
     * 读取失败停止并入，不得降级成仅密码提交；本入口只清零交入的密码原件。
     */
    fun submitMergeCredentials(passwordChars: CharArray, keyFileUri: Uri?) {
        try {
            controller?.submitMergeCredentialsFromUri(passwordChars, keyFileUri)
        } finally {
            passwordChars.fill('0')
        }
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
