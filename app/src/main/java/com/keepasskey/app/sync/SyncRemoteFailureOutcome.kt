package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.sync.model.SyncException

/**
 * 远端失败 → 用户可见结论的**单点归类**（ISSUE-P2-548）。
 *
 * ## 为什么需要它
 *
 * 整改前「鉴权失败 / 协议失败 / 服务端 5xx」与「真连不上网」在同步周期里共用同一个
 * 降级结果，app 层一律产出 [SyncOutcome.Offline] ⇒ UI 显示「离线，已保留本地副本」，
 * 且失败通知在同周期内被 `Offline` 结论撤掉：**密码填错也能显示成"一切正常"**，
 * 库可长期未同步成功而用户零感知。
 *
 * ## 归类口径
 *
 * 沿用 `ISSUE-P2-402` 已在 `SyncCycleCommitPaths` 确立的三分法（此前只在该一处落地，
 * 其余路径仍一律误报离线）：
 * - [SyncRemoteFailureKind.OFFLINE]：`SyncException.NetworkError`（连接失败 / 超时）——
 *   **设计内降级**，用户无需任何操作，网络恢复即自愈，故不亮失败通知；
 * - [SyncRemoteFailureKind.AUTH]：401 / 403——用户必须改凭据才可能恢复；
 * - [SyncRemoteFailureKind.REJECTED]：其余（协议错误 / 5xx / 配额 / 未知）——用户需知悉。
 *
 * [remoteFailureDescriptor] 只取**状态码或异常类名**，绝不取 `message`
 * （`ISSUE-P3-550`：异常 message 携带服务器可控串，禁止进 UI）。
 */
internal enum class SyncRemoteFailureKind {
    /** 真·网络不可达：设计内降级 */
    OFFLINE,

    /** 鉴权被拒（401 / 403） */
    AUTH,

    /** 其余远端拒绝 / 服务端失败 */
    REJECTED,
}

/** 纯函数归类（JVM 可单测）：[cause] 为 null 时按「其余」处置，绝不落到离线口径。 */
internal fun classifyRemoteFailure(cause: Throwable?): SyncRemoteFailureKind = when (cause) {
    is SyncException.NetworkError -> SyncRemoteFailureKind.OFFLINE
    is SyncException.AuthenticationError -> SyncRemoteFailureKind.AUTH
    else -> SyncRemoteFailureKind.REJECTED
}

/**
 * 归类 → [SyncOutcome] 的单点映射：所有远端失败路径（首传探测 / openRemote 降级 /
 * 上传失败）一律经本函数产出结论，杜绝「同一起因在不同路径给出不同口径」。
 */
internal fun StringsProvider.remoteFailureOutcome(cause: Throwable?): SyncOutcome =
    when (classifyRemoteFailure(cause)) {
        SyncRemoteFailureKind.OFFLINE -> SyncOutcome.Offline
        SyncRemoteFailureKind.AUTH -> SyncOutcome.Error(get(R.string.sync_error_auth_failed))
        SyncRemoteFailureKind.REJECTED -> SyncOutcome.Error(
            get(R.string.sync_error_remote_rejected, remoteFailureDescriptor(cause))
        )
    }

/**
 * 用户可见的失败标识：优先 HTTP 状态码（协议错误），否则异常类名；
 * 恒**不含** `message`（服务器可控串不得进 UI，ISSUE-P3-550）。
 */
internal fun remoteFailureDescriptor(cause: Throwable?): String =
    (cause as? SyncException.ProtocolError)?.statusCode?.toString()
        ?: cause?.javaClass?.simpleName
        ?: "Unknown"
