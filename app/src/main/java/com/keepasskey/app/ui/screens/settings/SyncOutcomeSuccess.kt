package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.sync.SyncOutcome

/**
 * 「本次同步是否已让远端收敛」的**单一判据**（`ISSUE-P2-651/652` 编排收口）。
 *
 * 设置页两条编排（普通同步 `triggerSync` 与保存回执同步 `verifyAndSyncSavedConfiguration`）
 * 都用本判据决定「能否改写最近同步时间 / 能否置位连接已验证」。
 *
 * `LocalPendingChanges` **不**计入：`ISSUE-P1-591` 明确该结果表示远端已接受本轮上传版本，
 * 但本地在窗口内又保存出了新版本，下一轮才上传——据此写「已同步」或置位已验证即谎报。
 * 冲突、绑定不符、离线与失败同样不计入。
 *
 * 与 `SyncOutcome.isInSyncWithRemote()`（本地检查点记录判据）取同一集合，但**语义不同名**：
 * 那个问「能否记录本地检查点」，本判据问「能否对用户说已同步、并把连接标记为已验证」。
 */
internal fun SyncOutcome.isSuccessfulSync(): Boolean = when (this) {
    is SyncOutcome.UpToDate,
    is SyncOutcome.UploadedLocal,
    is SyncOutcome.MergedAndUploaded -> true

    is SyncOutcome.LocalPendingChanges,
    is SyncOutcome.ConflictNeedsUser,
    is SyncOutcome.VaultBindingMismatch,
    is SyncOutcome.Offline,
    is SyncOutcome.Error -> false
}
