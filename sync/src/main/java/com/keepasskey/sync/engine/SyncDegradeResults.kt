package com.keepasskey.sync.engine

import com.keepasskey.sync.model.SyncException

/**
 * 降级读缓存的**分型收口**（`ISSUE-P2-548`）。
 *
 * ## 为什么单独成文件
 *
 * 「远端连不上」与「远端可达但拒绝」在整改前共用同一个降级结果
 * （[SyncOpenResult.RemoteUnreachableUsingCache] / [SyncCommitResult.RemoteUnreachable]）
 * ⇒ app 层一律产出 `SyncOutcome.Offline`：**密码填错也显示成「离线，已保留本地副本」**，
 * 且同周期的失败通知被 `Offline` 结论撤掉，用户长期零感知。
 *
 * 判据只认 [SyncException.NetworkError]（连接失败 / 超时）——那是用户无需任何操作、
 * 网络恢复即自愈的**设计内降级**；其余起因（鉴权 / 协议 / 5xx / 配额）一律视为
 * 「远端可达但拒绝」，由 app 层按 `ISSUE-P2-402` 既有口径生成具体文案。
 *
 * 两条降级路径（open 与 commit）必须**同进同出**，故并列在同一文件、同一 KDoc 之下：
 * 只改一侧会让「打开阶段报告鉴权失败、上传阶段仍报离线」的裂口重新出现。
 * 本文件独立存在亦是为 `SyncEngine.kt` 的行数分档闸门让路（该文件此前已贴 500 行上限）。
 */

/** open 路径：远端拒绝 ⇒ [SyncOpenResult.RemoteRejectedUsingCache]，真不可达 ⇒ 原结果。 */
internal fun degradeUsingCache(cachedBytes: ByteArray, cause: Throwable?): SyncOpenResult =
    if (cause is SyncException.NetworkError) {
        SyncOpenResult.RemoteUnreachableUsingCache(cachedBytes)
    } else {
        SyncOpenResult.RemoteRejectedUsingCache(cachedBytes, cause)
    }

/** commit 路径（本地变更恒已安全保留在缓存）：同口径分型。 */
internal fun degradeCommitUsingCache(keptLocal: Boolean, cause: Throwable?): SyncCommitResult =
    if (cause is SyncException.NetworkError) {
        SyncCommitResult.RemoteUnreachable(keptLocal)
    } else {
        SyncCommitResult.RemoteRejected(keptLocal, cause)
    }
