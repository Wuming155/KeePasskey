package com.keepasskey.app.sync

import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.merge.ConflictedGroupPair
import com.keepasskey.sync.merge.MetaFieldConflict

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

    /**
     * `ISSUE-P1-591`：本轮已让**远端与实际上传 / 确认的版本**一致，但上传窗口内用户又保存出了
     * 新版本（会话活动树已非该版本）⇒ 本地**仍有待同步修改**，下一轮才会上传。
     *
     * 它**不是**「已收敛」结论（`isInSyncWithRemote()` 为 false，不得记录本地文件检查点），
     * 也不得回显「已同步」——否则会向用户谎报未上传的 B 已上云（`AUD-03` 的直接后果）。
     */
    data object LocalPendingChanges : SyncOutcome()

    /** 三方自动合并成功并已同步回写云端与本地 */
    data object MergedAndUploaded : SyncOutcome()

    /**
     * 发生同字段冲突，需用户在冲突界面决策。
     *
     * `ISSUE-P2-660`：[conflicts] 为条目级，[groupConflicts] 为**分组级**（同字段双侧异值）。
     * 两者必须一并带出——同步面此前只读条目冲突，分组分歧由 `KdbxGroupFieldMerge` 按 LWW
     * 静默定案，用户无任何提示。默认空表保持既有构造点源码兼容。
     */
    data class ConflictNeedsUser(
        val conflicts: List<ConflictedEntryPair>,
        val groupConflicts: List<ConflictedGroupPair> = emptyList(),
        /** `ISSUE-P2-659`：库级 Meta 的残余冲突（标量双侧异值且无时间戳可裁） */
        val metaConflicts: List<MetaFieldConflict> = emptyList()
    ) : SyncOutcome()

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
