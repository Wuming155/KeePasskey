package com.keepasskey.app.ui.screens.importer

import com.keepasskey.app.data.importer.ImportFailureReason
import com.keepasskey.app.data.importer.ImportOutcome
import com.keepasskey.app.data.importer.ImportSource

/**
 * 明文导入的界面状态（ISSUE-P3-19 交付物 1.3）。
 *
 * 全部字段非敏感：只承载数据源标识、计数报告与**归类后的失败原因**（`@StringRes`），
 * 绝不承载任何条目字段明文（标题/用户名/密码/URL/备注均不进入 UI 状态流）。
 */
sealed interface ImportUiState {

    /** 空闲：无进行中的导入，也不展示报告。 */
    data object Idle : ImportUiState

    /** 正在读取/解析 [source] 的数据。 */
    data class Parsing(val source: ImportSource) : ImportUiState

    /** 完成：[outcome] 为落库结果报告。 */
    data class Done(val outcome: ImportOutcome) : ImportUiState

    /** 失败：[reason] 为归类原因，UI 经其 `@StringRes` 取文案。 */
    data class Failed(val source: ImportSource?, val reason: ImportFailureReason) : ImportUiState
}
