package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncProviderConfigSnapshot
import com.keepasskey.app.sync.launchGuarded
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 「保存成功 → 测连 → 同步」的顺序编排（`ISSUE-P2-652`，自 [SettingsSyncController] 拆出）。
 *
 * 存在的意义是**同一份保存回执贯穿两个网络阶段**：测连与同步各自构造一次 Provider，但 endpoint /
 * 对象路径 / 密封凭据全部取自这份回执。若中途用户再次保存了新配置，本轮的结论只对旧回执成立
 * ——不得把新配置标成「已验证」，也不得让旧轮次的成功外溢到新版。
 */
internal class SettingsSyncSavedFlow(
    private val coordinator: SyncCoordinator,
    private val state: MutableStateFlow<SettingsSyncController.SyncUiState>,
    private val scope: CoroutineScope,
    private val configSaver: SettingsSyncConfigSaver,
    private val onFailure: (Throwable) -> Unit,
    private val onRecoveryRefresh: suspend () -> Unit,
    private val formatTimestamp: () -> String,
    private val clearBusyIfStillBusy: () -> Unit
) {

    /** 以 [snapshot] 为回执执行测连 + 同步；忙态期间不重复进入。 */
    fun run(snapshot: SyncProviderConfigSnapshot) {
        if (state.value.isSyncing) return
        scope.launchGuarded(onFailure = onFailure) {
            try {
                val provider = snapshot.provider
                state.update {
                    it.copy(
                        isSyncing = true,
                        isConnectionVerified = false,
                        syncFeedbackMessage = UiMessage(
                            R.string.sync_feedback_connecting,
                            listOf(provider.protocol)
                        )
                    )
                }

                if (coordinator.testConnection(snapshot).isFailure) {
                    state.update {
                        it.copy(
                            isSyncing = false,
                            isConnectionVerified = false,
                            syncFeedbackMessage = UiMessage(R.string.sync_gate_test_failed)
                        )
                    }
                    return@launchGuarded
                }

                // 测连通过即该回执的连接成立；但期间若又保存了新配置，结论不得外溢到新版。
                state.update {
                    it.copy(
                        isConnectionVerified = configSaver.isLatest(snapshot.revision),
                        syncFeedbackMessage = UiMessage(
                            R.string.sync_feedback_connecting,
                            listOf(provider.protocol)
                        )
                    )
                }
                val outcome = coordinator.syncNow(snapshot)
                onRecoveryRefresh()
                val succeeded = outcome.isSuccessfulSync()
                val feedback = syncOutcomeFeedback(outcome, provider.protocol)
                state.update {
                    it.copy(
                        isSyncing = false,
                        syncFeedbackMessage = feedback,
                        lastSyncTimeText = if (succeeded) formatTimestamp() else it.lastSyncTimeText,
                        // 本标记表示「该回执的**连接**已验证」，由上方测连结论给出，不因随后的
                        // 同步业务失败而回退（失败原因已在反馈条如实呈现）；唯一失效条件是期间又保存了新配置。
                        isConnectionVerified = configSaver.isLatest(snapshot.revision)
                    )
                }
            } finally {
                // 取消（锁库 / 作用域销毁）不经 onFailure：兜底复位忙态，避免指示器永久停在「同步中」。
                clearBusyIfStillBusy()
            }
        }
    }
}
