package com.keepasskey.app.ui.screens.settings

import android.net.Uri
import com.keepasskey.app.data.importer.ImportSource
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.app.ui.screens.importer.VaultImportController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 明文导入的 UI 侧门面（ISSUE-P3-29：自 `SettingsViewModel.kt` 拆出，纯结构性拆分）。
 *
 * ISSUE-P3-19（ZT-43d）：全链路（SAF 读字节 → 解析 → 落库 → 出报告）由 [VaultImportController] 承载，
 * 本类只做「控制器缺失时恒 Idle」的如实回落 —— 不呈现任何导入反馈，绝不产生假进度/假回执。
 */
internal class SettingsImportPresenter(
    private val controller: VaultImportController?
) {

    /** 导入状态（Idle / Parsing / Done / Failed）。无控制器时恒为 Idle，UI 不渲染任何导入反馈。 */
    val state: StateFlow<ImportUiState> = controller?.uiState ?: MutableStateFlow(ImportUiState.Idle)

    /** 按数据源 + SAF Uri 启动一次导入：解析 → 落库 → 出报告，全部由控制器负责。 */
    fun start(source: ImportSource, uri: Uri) {
        controller?.startImport(source, uri)
    }

    /** 关闭导入结果报告对话框。 */
    fun dismissReport() {
        controller?.reset()
    }
}
