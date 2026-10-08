package com.keepasskey.app.sync

/**
 * 同步结果 → **脱敏日志描述**（`ISSUE-P2-529` 顺带自 `SyncCoordinator` 原样切出：
 * 该函数是纯函数、不读协调器任何成员，与协调器无共同变化理由；切出使后者回到行数分档档沿之内）。
 *
 * **两条脱敏口径随本函数一并保留，改前必读**：
 * - `SyncOutcome.Error` 的描述**不得**内嵌 `message`——其可能携带端点 URL / 主机 / 桶名
 *   （如 `InvalidEndpointError` 直接拼接原始 endpoint），属敏感插值（`ISSUE-P2-69`）；
 *   失败详情只由 UI 提示承载，日志只留结果类型；
 * - `VaultBindingMismatch` 只记类型与形态，**不记库 UUID 全文**（`ISSUE-P2-291`）。
 *
 * 命名刻意与 [ResumeSyncProbeCoordinator] 的同类私有函数区分（后者描述的是探测结论，
 * 不是同步周期结果）——同名会让两处口径在阅读时混淆。
 */
internal fun syncOutcomeDescription(outcome: SyncOutcome): String = when (outcome) {
    is SyncOutcome.UpToDate -> "UpToDate(与云端一致)"
    is SyncOutcome.UploadedLocal -> "UploadedLocal(本地已上传)"
    is SyncOutcome.MergedAndUploaded -> "MergedAndUploaded(合并后已上传)"
    is SyncOutcome.ConflictNeedsUser -> "ConflictNeedsUser(条目冲突数=${outcome.conflicts.size})"
    is SyncOutcome.Offline -> "Offline(离线/网络不可达)"
    // ISSUE-P2-69：Error.message 可能内嵌端点 URL / 主机 / 桶名（如 InvalidEndpointError
    // 直接拼接原始 endpoint），属敏感插值，不得进入调试日志缓冲；
    // 失败详情已由 UI 提示承载，日志只保留结果类型。
    is SyncOutcome.Error -> "Error(同步失败，详情见界面提示)"
    // ISSUE-P2-291：绑定拦截不携带凭据，但同样只记类型与路径形态，不记库 UUID 全文
    is SyncOutcome.VaultBindingMismatch -> "VaultBindingMismatch(远端归属另一库，待用户确认)"
}
