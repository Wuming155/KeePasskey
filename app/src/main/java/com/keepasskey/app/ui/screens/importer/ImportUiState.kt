package com.keepasskey.app.ui.screens.importer

import com.keepasskey.app.data.importer.ImportFailureReason
import com.keepasskey.app.data.importer.ImportOutcome
import com.keepasskey.app.data.importer.ImportSource

/**
 * 明文导入的界面状态（ISSUE-P3-19 交付物 1.3）。
 *
 * 全部字段非敏感：只承载数据源标识、计数报告与**归类后的失败原因**（`@StringRes`），
 * 绝不承载任何条目字段明文（标题/用户名/密码/URL/备注均不进入 UI 状态流）。
 *
 * `ISSUE-P2-354 AC④`：[Parsing] 扩展为**分阶段 / 计数进度**并提供取消入口（取消动作经回调上行，
 * 协程 cancellation 由 `VaultImportController.cancelImport` 执行）。
 */
sealed interface ImportUiState {

    /** 空闲：无进行中的导入，也不展示报告。 */
    data object Idle : ImportUiState

    /**
     * 正在导入 [source]：[stage] 为当前阶段，落库阶段附 [processed] / [total] 计数
     * （READING / PARSING 阶段 `total` 恒为 null = 总数尚不可知，UI 不得谎报百分比）。
     */
    data class Parsing(
        val source: ImportSource,
        val stage: ImportStage = ImportStage.READING,
        val processed: Int = 0,
        val total: Int? = null
    ) : ImportUiState

    /** 完成：[outcome] 为落库结果报告。 */
    data class Done(val outcome: ImportOutcome) : ImportUiState

    /** 失败：[reason] 为归类原因，UI 经其 `@StringRes` 取文案。 */
    data class Failed(val source: ImportSource?, val reason: ImportFailureReason) : ImportUiState
}

/** 导入阶段（ISSUE-P2-354 AC④：分阶段可量化进度；顺序即管线执行顺序）。 */
enum class ImportStage {
    /** 读取所选文件字节（SAF IO） */
    READING,

    /** 解析源格式为批次（CPU） */
    PARSING,

    /** 逐条落库（含冲突判定与保存） */
    PERSISTING
}
