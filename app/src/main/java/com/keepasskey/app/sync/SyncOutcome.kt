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

    /** 离线模式或网络不可达，保留本地安全副本 */
    data object Offline : SyncOutcome()

    /** 同步过程发生错误 */
    data class Error(val message: String) : SyncOutcome()
}
