package com.keepasskey.app.sync

import com.keepasskey.sync.merge.ConflictedEntryPair

/**
 * 云同步结果状态模型 (Wave 3-E P0-5)
 *
 * ISSUE-P3-25 拆分：原样搬出自 `SyncCoordinator.kt`——顶层 sealed class，
 * 包路径（com.keepasskey.app.sync）与 public 可见性均不变，消费方无需改动。
 */
sealed class SyncOutcome {
    /** 数据库已与云端保持最新同步 */
    data object UpToDate : SyncOutcome()

    /** 本地修改赢并已成功上传云端 */
    data object UploadedLocal : SyncOutcome()

    /** 三方自动合并成功并已同步回写云端与本地 */
    data object MergedAndUploaded : SyncOutcome()

    /** 发生条目同字段冲突，需用户在冲突界面决策 */
    data class ConflictNeedsUser(val conflicts: List<ConflictedEntryPair>) : SyncOutcome()

    /**
     * 云端副本（[remotePath]）登记的归属库与当前库不同（`ISSUE-P2-291`）：
     * 同步已中止于任何网络写之前，须用户显式确认「整库覆盖并改绑当前库」
     * （`SyncCoordinator.confirmVaultBindingTakeover`）或取消，严禁静默 PUT。
     */
    data class VaultBindingMismatch(val remotePath: String) : SyncOutcome()

    /** 离线模式或网络不可达，保留本地安全副本 */
    data object Offline : SyncOutcome()

    /**
     * 同步过程发生错误。
     *
     * [message] **必须是本仓产出、已本地化的文案**（`strings.get(R.string.*)`）——
     * `ISSUE-P2-548` 之前存在把原始异常 `message` 灌进本类的调用点
     * （`SyncConflictAutoMerge` 的合并上传失败），而本类有三处 UI 直出消费点
     * ⇒ 构成「端点 / 主机 / 协议细节 → 界面」的端到端泄露链。
     * 判据已机检化：`tools/doc/check_message_not_in_user_text.py`。
     */
    data class Error(val message: String) : SyncOutcome()
}

/**
 * [SyncOutcome.Error] 的用户可见文案（ISSUE-P3-550 的**命名出口**）。
 *
 * 存在的理由不是省字符，而是给「显示同步错误文案」留**唯一可审点**——
 * 三处 UI 消费点（`VaultListSyncController` / `SettingsSyncController` /
 * `ConflictResolutionViewModel`）此前各自直读 `.message`，任何一处被改成读异常细节
 * 都无人察觉；收敛后 QC 只需盯这一个出口及其上游产出点。
 */
val SyncOutcome.Error.userText: String get() = message
