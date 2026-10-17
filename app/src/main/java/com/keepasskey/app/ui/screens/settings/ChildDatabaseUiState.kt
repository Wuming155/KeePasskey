package com.keepasskey.app.ui.screens.settings

import androidx.annotation.StringRes
import com.keepasskey.app.R
import com.keepasskey.app.data.childdb.ChildDatabaseFailureReason
import com.keepasskey.app.data.childdb.ChildDatabaseMountState
import com.keepasskey.app.ui.model.UiMessage

/**
 * 子库挂载的 UI 状态与文案映射（`ISSUE-P3-20`；`ISSUE-P2-645` 自 `SettingsUiState.kt` 拆出）。
 *
 * 纯展示层类型与纯函数：不引用 `android.net.Uri`，可在 JVM 单测直接断言；
 * 子库凭据与条目明文一律不进入本文件的任何类型。
 */
// ===================== ISSUE-P3-20：子库挂载（UI 接线）状态与文案映射 =====================

/**
 * 单条子库挂载的**展示态**。
 *
 * 只承载非敏感信息（别名 + 状态 + 是否有凭据重试入口）：子库凭据与条目明文
 * 一律不进入本类型，也不进入任何 `StateFlow`（核心层投影只暴露非敏感展示字段）。
 */
data class ChildDatabaseMountUiState(
    /** 挂载身份（仅用于触发解锁 / 卸载，无密钥含义） */
    val mountId: String,
    /** 用户可见别名（核心层已归一化，1..64 可见字符） */
    val alias: String,
    /** 状态呈现（文案或不确定进度） */
    val status: ChildDatabaseStatus,
    /**
     * 是否提供「重新输入凭据解锁」入口。
     *
     * [ChildDatabaseMountState.Opened]（已打开）与 [ChildDatabaseMountState.Opening]（进行中）为 false，
     * 其余状态为 true——**「已挂载」不等于「已解锁」**，未解锁时必须给出真实的重试入口。
     */
    val canRetryWithCredentials: Boolean
)

/**
 * 子库运行时状态的**呈现方式**。
 *
 * [Opening] 刻意不映射任何文案：解密打开是瞬时态，用「未解锁」或「已解锁」表达都会失真，
 * UI 以不确定进度指示器呈现「正在打开」这一事实本身。
 */
sealed interface ChildDatabaseStatus {

    /** 有明确文案的状态；文案参数（如已解锁的条目数）由 [UiMessage] 承载 */
    data class Text(val message: UiMessage) : ChildDatabaseStatus

    /** 正在解密打开（无文案：不用「未解锁」谎报进度，也不用「已解锁」提前报喜） */
    data object Opening : ChildDatabaseStatus
}

/** 子库操作反馈：成功与失败以 [isError] 显式区分配色，不靠猜测文案内容 */
data class ChildDatabaseFeedback(val message: UiMessage, val isError: Boolean)

/**
 * 子库挂载对话框整体状态。
 *
 * [available] 为 false 表示会话管理器未注入（仅单测 / 异常装配场景）：UI 如实呈现**不可用**
 * （控件整体禁用），不呈现任何点得动却没反应的假入口。
 */
data class ChildDatabaseUiState(
    val available: Boolean = false,
    val mounts: List<ChildDatabaseMountUiState> = emptyList(),
    val feedback: ChildDatabaseFeedback? = null
)

/**
 * 子库**状态 / 失败分型 → 文案**映射（纯函数，无 Android 依赖，可 JVM 直测）。
 *
 * 领域层只报键（`ChildDatabaseMountState` / `ChildDatabaseFailureReason` 枚举），
 * 用户文案一律在此收敛到 `strings.xml` 资源 ID；失败分型经
 * [ChildDatabaseFailureReason.of] 从失败结果取回（自动穿透 `cause` 链）。
 *
 * 两处 `when` 均为**穷尽分支**：新增状态或分型会在此处编译失败，
 * 杜绝「新分型静默套用未知错误文案」。
 */
internal object ChildDatabaseStatusText {

    /** 运行时状态 → 呈现方式 */
    fun of(state: ChildDatabaseMountState): ChildDatabaseStatus = when (state) {
        ChildDatabaseMountState.Closed ->
            ChildDatabaseStatus.Text(UiMessage(R.string.dbset_child_db_state_locked))

        ChildDatabaseMountState.Opening -> ChildDatabaseStatus.Opening

        is ChildDatabaseMountState.Opened -> ChildDatabaseStatus.Text(
            UiMessage(
                R.string.dbset_child_db_opened_summary,
                listOf(state.snapshot.entries.size)
            )
        )

        ChildDatabaseMountState.CredentialRejected ->
            ChildDatabaseStatus.Text(UiMessage(R.string.dbset_child_db_state_rejected))

        ChildDatabaseMountState.SourceUnavailable ->
            ChildDatabaseStatus.Text(UiMessage(R.string.dbset_child_db_state_unavailable))

        is ChildDatabaseMountState.Failed -> ChildDatabaseStatus.Text(UiMessage(of(state.reason)))
    }

    /** 13 种失败分型 → 专用文案资源 */
    @StringRes
    fun of(reason: ChildDatabaseFailureReason): Int = when (reason) {
        ChildDatabaseFailureReason.INVALID_ALIAS -> R.string.dbset_child_db_err_invalid_alias
        ChildDatabaseFailureReason.INVALID_SOURCE -> R.string.dbset_child_db_err_invalid_source
        ChildDatabaseFailureReason.DUPLICATE_MOUNT -> R.string.dbset_child_db_err_duplicate
        ChildDatabaseFailureReason.MOUNT_NOT_FOUND -> R.string.dbset_child_db_err_mount_not_found
        ChildDatabaseFailureReason.CREDENTIAL_MISSING -> R.string.dbset_child_db_err_credential_missing
        ChildDatabaseFailureReason.CREDENTIAL_REJECTED -> R.string.dbset_child_db_err_credential_rejected
        ChildDatabaseFailureReason.THROTTLED -> R.string.dbset_child_db_err_throttled
        ChildDatabaseFailureReason.SOURCE_UNAVAILABLE -> R.string.dbset_child_db_err_source_unavailable
        ChildDatabaseFailureReason.CORRUPT_FILE -> R.string.dbset_child_db_err_corrupt
        ChildDatabaseFailureReason.UNSUPPORTED_VERSION -> R.string.dbset_child_db_err_unsupported_version
        ChildDatabaseFailureReason.IO_ERROR -> R.string.dbset_child_db_err_io
        ChildDatabaseFailureReason.TERMINATED -> R.string.dbset_child_db_err_terminated
        ChildDatabaseFailureReason.UNKNOWN -> R.string.dbset_child_db_err_unknown
    }

    /** 是否提供凭据重试入口：仅「已打开」与「正在打开」不提供 */
    fun allowsCredentialRetry(state: ChildDatabaseMountState): Boolean =
        state !is ChildDatabaseMountState.Opened && state !is ChildDatabaseMountState.Opening
}

/** 挂载成功反馈（仅在核心层真实返回成功后才使用，不预置、不乐观上报） */
internal fun childDatabaseMountedFeedback(): ChildDatabaseFeedback =
    ChildDatabaseFeedback(UiMessage(R.string.dbset_child_db_mounted), isError = false)

/** 失败反馈：分型经 `cause` 链自动穿透后映射到专用文案 */
internal fun childDatabaseFailureFeedback(error: Throwable): ChildDatabaseFeedback =
    ChildDatabaseFeedback(
        UiMessage(ChildDatabaseStatusText.of(ChildDatabaseFailureReason.of(error))),
        isError = true
    )

/**
 * 来源展示名（非敏感元数据）：取 Uri / 路径末段，供挂载表单回显「已选哪个文件」。
 *
 * 纯字符串处理（**不引用 `android.net.Uri`**），故可在 JVM 单测直接断言；
 * 空串或全空白一律回落为原串，绝不产出空标签。
 */
internal fun childDatabaseSourceDisplayName(sourceUri: String): String {
    val trimmed = sourceUri.trim()
    return trimmed.substringAfterLast('/').ifBlank { trimmed }
}
