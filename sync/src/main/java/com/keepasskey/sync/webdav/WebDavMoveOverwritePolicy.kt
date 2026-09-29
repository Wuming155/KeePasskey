package com.keepasskey.sync.webdav

import com.keepasskey.core.log.AppLog
import com.keepasskey.sync.model.SyncException

/**
 * WebDAV MOVE 覆盖已有目标的 409/423 兜底判定（ISSUE-P2-381）。
 *
 * 背景：部分服务器对 MOVE 覆盖已有目标报 409/423（仅靠 `Overwrite:T` 不可靠；
 * keepass2android `WebDavStorage.java:235` 注释 + 提交 `9c8ee243` 实证）。
 *
 * 语义：
 * - 409/423 → 调用方应 DELETE 目标（404 容忍）后单次无预条件重试；
 * - 412 → ConflictError（ETag 预条件失败），与 409/423 分开报错；
 * - 其它状态码 → ProtocolError（通用 MOVE 失败）。
 *
 * 抽为纯策略：错误文案与「是否可重试」的判定可在纯 JVM 上断言，
 * 不依赖真实 DAV 服务器。
 */
internal object WebDavMoveOverwritePolicy {

    /** HTTP 409 Conflict：服务器拒绝 MOVE 覆盖已有目标 */
    const val HTTP_CONFLICT = 409

    /** HTTP 423 Locked：与 409 同型的覆盖拒绝形态 */
    const val HTTP_LOCKED = 423

    /** HTTP 412 Precondition Failed：ETag / If 预条件失败 */
    const val HTTP_PRECONDITION_FAILED = 412

    /** MOVE 是否应走「DELETE 目标 + 无预条件重试」兜底 */
    fun isOverwriteConflict(httpCode: Int): Boolean =
        httpCode == HTTP_CONFLICT || httpCode == HTTP_LOCKED

    /** MOVE 是否应转 ConflictError（预条件失败，不可用 DELETE+重试解决） */
    fun isPreconditionFailure(httpCode: Int): Boolean =
        httpCode == HTTP_PRECONDITION_FAILED

    /** 覆盖冲突最终失败时的报错文案（与 412 分开，ISSUE-P2-381 AC②） */
    fun overwriteConflictMessage(httpCode: Int): String =
        "WebDAV 原子写入 MOVE 失败：服务器拒绝覆盖已有目标 (HTTP $httpCode)，" +
            "已 DELETE 目标并重试仍失败，已清理临时文件"

    /** 非预条件、非覆盖冲突时的通用 MOVE 失败文案 */
    fun genericMoveFailureMessage(httpCode: Int): String =
        "WebDAV 原子写入 MOVE 失败 (HTTP $httpCode)，已清理临时文件"

    /** 412 ConflictError 文案（与覆盖冲突文案区分） */
    fun preconditionFailureMessage(): String =
        "WebDAV 原子写入 MOVE 失败：远端已被其他人修改 (HTTP 412)"

    /**
     * 记录一次覆盖冲突兜底日志（不含路径 / 密码等任何用户数据）。
     * 调用方在 DELETE 目标之前调用。
     */
    fun logOverwriteConflict(httpCode: Int) {
        AppLog.i(
            "WebDavSyncProvider",
            "MOVE 覆盖已有目标返回 HTTP $httpCode，先 DELETE 目标（404 容忍）再重试"
        )
    }

    /** 按最终失败形态构造 ProtocolError */
    fun protocolErrorFor(overwriteConflictCode: Int?, lastMoveCode: Int): SyncException.ProtocolError {
        val code = overwriteConflictCode
        return if (code != null) {
            SyncException.ProtocolError(code, overwriteConflictMessage(code))
        } else {
            SyncException.ProtocolError(500, genericMoveFailureMessage(lastMoveCode))
        }
    }
}
