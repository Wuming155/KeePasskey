package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.sync.userText
import com.keepasskey.app.ui.model.UiMessage

/**
 * 同步结果 → 界面反馈的**唯一映射**（`ISSUE-P2-645` / `ISSUE-P2-652`）。
 *
 * 设置页两条编排（普通同步 `triggerSync` 与保存回执同步 `verifyAndSyncSavedConfiguration`）
 * 此前各写一份逐字相同的 `when (outcome)`；一旦漂移，同一结果会在两条入口显示不同文案。
 * 收敛到本文件后，新增分支只有一处需要改。
 *
 * 成功三态挂 [UiMessage.SYNC_STATUS_DURATION_MS] 短档；错误 / 冲突 / 离线 / 待同步需要阅读，
 * **不**缩短。`SyncOutcome.Error` 只经 `userText` 命名出口取文案（`ISSUE-P3-550`）。
 */
internal fun syncOutcomeFeedback(outcome: SyncOutcome, protocol: String): UiMessage = when (outcome) {
    is SyncOutcome.UpToDate -> UiMessage(
        R.string.sync_feedback_done,
        listOf(protocol),
        durationMillis = UiMessage.SYNC_STATUS_DURATION_MS
    )
    is SyncOutcome.UploadedLocal -> UiMessage(
        R.string.sync_feedback_uploaded,
        listOf(protocol),
        durationMillis = UiMessage.SYNC_STATUS_DURATION_MS
    )
    is SyncOutcome.MergedAndUploaded -> UiMessage(
        R.string.sync_feedback_merged,
        listOf(protocol),
        durationMillis = UiMessage.SYNC_STATUS_DURATION_MS
    )
    // ISSUE-P1-591：远端已接受本轮上传版本，但本地仍有未同步修改——如实提示，不谎报已同步
    is SyncOutcome.LocalPendingChanges -> UiMessage(R.string.sync_feedback_local_pending)
    is SyncOutcome.ConflictNeedsUser -> UiMessage(R.string.sync_feedback_conflict)
    // ISSUE-P2-291 AC②：绑定拦截只提示，确认入口在库列表页（下拉刷新触发）
    is SyncOutcome.VaultBindingMismatch -> UiMessage(R.string.sync_vault_binding_mismatch)
    is SyncOutcome.Offline -> UiMessage(R.string.sync_feedback_offline)
    is SyncOutcome.Error -> UiMessage(R.string.sync_feedback_error, listOf(outcome.userText))
}
