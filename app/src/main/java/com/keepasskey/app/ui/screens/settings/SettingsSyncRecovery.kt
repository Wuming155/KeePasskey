package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.sync.launchGuarded
import com.keepasskey.app.sync.userText
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 同步「待恢复采纳」状态与用户处置（`ISSUE-P2-645` 拆分自 `SettingsSyncController`，纯搬运）。
 *
 * 负责探测活动库是否仍有待用户处置的同步恢复材料，并在用户确认「保留本地」时条件发布本地版本。
 * 控制器只转交状态流与失败回调，不再重复这两段编排。
 */
internal class SettingsSyncRecovery(
    private val syncCoordinator: SyncCoordinator,
    private val state: MutableStateFlow<SettingsSyncController.SyncUiState>,
    private val strings: StringsProvider,
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit,
    private val formatTimestamp: () -> String
) {

    /** 刷新恢复提示标记（水合、每次同步与处置完成后调用）。 */
    suspend fun refresh() {
        val required = syncCoordinator.hasPendingUserRecoveryForActiveVault()
        val canKeepLocal = syncCoordinator.hasPendingAdoptionRecoveryForActiveVault()
        state.update {
            it.copy(syncRecoveryRequired = required, syncRecoveryCanKeepLocal = canKeepLocal)
        }
    }

    /** 用户确认恢复：保留当前本地库并条件发布（不回退为「静默以远端为准」）。 */
    fun keepLocal() {
        if (state.value.isSyncing) return
        val provider = state.value.provider
        scope.launchGuarded(onFailure = onFailure) {
            state.update {
                it.copy(
                    isSyncing = true,
                    syncFeedbackMessage = UiMessage(R.string.sync_recovery_uploading, listOf(provider.protocol))
                )
            }
            val outcome = syncCoordinator.recoverPendingAcceptanceByPublishingLocal()
            refresh()
            val succeeded = outcome is SyncOutcome.UploadedLocal
            val feedback = if (succeeded) {
                UiMessage(R.string.sync_recovery_upload_complete, listOf(provider.protocol))
            } else if (outcome is SyncOutcome.Error) {
                UiMessage(R.string.sync_feedback_error, listOf(outcome.userText))
            } else {
                UiMessage(R.string.sync_feedback_error, listOf(strings.get(R.string.sync_error_recovery_required)))
            }
            state.update {
                it.copy(
                    isSyncing = false,
                    syncFeedbackMessage = feedback,
                    lastSyncTimeText = if (succeeded) formatTimestamp() else it.lastSyncTimeText,
                    isConnectionVerified = if (succeeded) true else it.isConnectionVerified
                )
            }
        }
    }
}
