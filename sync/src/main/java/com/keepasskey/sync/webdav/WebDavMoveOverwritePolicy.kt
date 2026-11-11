package com.keepasskey.sync.webdav

import com.keepasskey.core.log.AppLog
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException

/**
 * WebDAV MOVE 覆盖已有目标的 409/423 处理策略（ISSUE-P2-381 引入，ENG-20261028-20 收口）。
 *
 * 背景：部分服务器对 MOVE 覆盖已有目标报 409/423（仅靠 `Overwrite:T` 不可靠；
 * keepass2android `WebDavStorage.java:235` 注释 + 提交 `9c8ee243` 实证）。
 *
 * 默认语义（**不以删除正式目标作为覆盖兜底**）：
 * - 409/423 → 先重探目标元数据做裁决（[refusalGuard]）：
 *   远端 ETag 已漂移 ⇒ 调用方抛 ConflictError（他端并发写入，交上层合并）；
 *   重探失败 ⇒ 如实上抛探测异常（fail-closed）；
 *   远端未漂移 ⇒ 服务器确实不支持安全覆盖，抛 [protocolErrorFor] 构造的可处理协议错误，
 *   **远端旧文件原样保留**；
 * - 412 → ConflictError（ETag 预条件失败），与 409/423 分开报错；
 * - 其它状态码 → ProtocolError（通用 MOVE 失败）。
 *
 * 兼容写入例外：用户显式开启时由 [canDeleteBeforeMove] 判定准入，`WebDavUploadAtomic`
 * 执行「DELETE 目标 + `Overwrite: F` MOVE」；仍受重探 ETag 约束，且 `Overwrite: F` 拒绝
 * 覆盖他端在 DELETE 与 MOVE 之间重建的目标。旧「DELETE 目标 + 无预条件重试」兜底
 * 在默认态已移除：HTTP/WebDAV 没有原子的「覆盖」原语，
 * 删除成功而随后的 MOVE 失败、取消或进程死亡会让正式远端路径缺失，两步之间他端还可重建，
 * 无预条件重试又会静默覆盖该重建结果。宁可如实失败并保留旧文件。
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

    /** MOVE 是否属于「服务器拒绝覆盖已有目标」形态（409/423） */
    fun isOverwriteConflict(httpCode: Int): Boolean =
        httpCode == HTTP_CONFLICT || httpCode == HTTP_LOCKED

    /** MOVE 是否应转 ConflictError（预条件失败，不可用覆盖重试解决） */
    fun isPreconditionFailure(httpCode: Int): Boolean =
        httpCode == HTTP_PRECONDITION_FAILED

    /**
     * 409/423 拒绝覆盖后的裁决（ENG-20261028-20）——409/423 本身无法区分
     * 「服务器对 MOVE 覆盖的能力性拒绝」与「远端内容已被他端并发修改」，须以重探结果裁决：
     * - [currentMeta] 为 null（重探**失败**，含 404 / 5xx / 不可解析）→ [OverwriteRefusalGuard.ProbeFailed]：
     *   调用方如实上抛探测异常（fail-closed，ISSUE-P2-501 同口径——探测失败 ≠「成功但无 ETag」）；
     * - 远端 ETag 非空且 ≠ 非空 [expectedEtag] → [OverwriteRefusalGuard.Drifted]（携当前远端 ETag）：
     *   他端已写入新版本，调用方抛 ConflictError；
     * - ETag 相同、缺失（无 ETag 服务器）或本就无预期基线（[expectedEtag] 空白）→
     *   [OverwriteRefusalGuard.RemoteUnchanged]：远端未变而服务器仍拒绝覆盖 ⇒
     *   不支持安全覆盖，调用方返回协议错误并保留远端旧文件。
     */
    fun refusalGuard(currentMeta: RemoteFileMetadata?, expectedEtag: String?): OverwriteRefusalGuard =
        when {
            currentMeta == null -> OverwriteRefusalGuard.ProbeFailed
            isOverwriteDrift(currentRemoteEtag = currentMeta.etag, expectedEtag = expectedEtag) ->
                OverwriteRefusalGuard.Drifted(currentMeta.etag)
            else -> OverwriteRefusalGuard.RemoteUnchanged
        }

    /**
     * 远端 ETag 与预期基线**均非空且不相等** ⇒ 他端并发改写。
     * 预期基线空白时不存在「可失效的锁」（本就是无预条件的覆盖上传），不判漂移；
     * 比较双方均为 [com.keepasskey.sync.model.cleanEtag] 规范形态（弱标记保留、剥引号）。
     */
    fun isOverwriteDrift(currentRemoteEtag: String?, expectedEtag: String?): Boolean =
        !expectedEtag.isNullOrBlank() &&
            !currentRemoteEtag.isNullOrBlank() &&
            currentRemoteEtag != expectedEtag

    /**
     * 兼容写入（DELETE 正式目标 + `Overwrite: F` MOVE）的准入口径。
     *
     * 除用户显式开启外，还要求**双方 ETag 均可核验且一致**：无预期基线（本就是无预条件
     * 覆盖）或无 ETag 服务器 ⇒ 没有可失效的锁，无法证明删掉的正是本端基线版本，
     * fail-closed 不删除。重探本身仍由 [refusalGuard] 先判漂移 / 探测失败。
     */
    fun canDeleteBeforeMove(
        enabled: Boolean,
        expectedEtag: String?,
        currentRemoteEtag: String?
    ): Boolean = enabled &&
        !expectedEtag.isNullOrBlank() &&
        !currentRemoteEtag.isNullOrBlank() &&
        currentRemoteEtag == expectedEtag

    /** 覆盖被拒且远端未变时的报错文案：按「旧文件是否已被兼容路径删除」如实表述 */
    fun overwriteRefusedMessage(httpCode: Int, remoteDeleted: Boolean = false): String =
        if (remoteDeleted) {
            "WebDAV 原子写入 MOVE 失败：服务器拒绝覆盖已有目标 (HTTP $httpCode)，" +
                "云端旧文件已按兼容策略删除，本次临时文件已保留；请重新同步以完成写入"
        } else {
            "WebDAV 原子写入 MOVE 失败：服务器拒绝覆盖已有目标 (HTTP $httpCode)，" +
                "远端旧文件未删除、临时文件已清理；请确认服务器允许 MOVE 覆盖已有目标 " +
                "(Overwrite: T) 或开启兼容写入开关后重试"
        }

    /** 非预条件、非覆盖冲突时的通用 MOVE 失败文案 */
    fun genericMoveFailureMessage(httpCode: Int): String =
        "WebDAV 原子写入 MOVE 失败 (HTTP $httpCode)，已清理临时文件"

    /** 412 ConflictError 文案（与覆盖冲突文案区分） */
    fun preconditionFailureMessage(): String =
        "WebDAV 原子写入 MOVE 失败：远端已被其他人修改 (HTTP 412)"

    /** 覆盖被拒后重探发现远端已漂移的 ConflictError 文案（携实际 409/423 状态码） */
    fun overwriteDriftMessage(httpCode: Int): String =
        "WebDAV 原子写入 MOVE 失败：远端已被其他人修改 (HTTP $httpCode)"

    /**
     * 记录一次覆盖被拒（不含路径 / 密码等任何用户数据）。
     *
     * 走 `AppLog.w` 的带异常重载：纯文本 `AppLog.i` 只进 logcat，用户导出的诊断缓冲
     * 看不到这条决定性事件；异常重载经诊断桥写入类型与栈帧，且不外泄异常 message。
     */
    fun logOverwriteRefusal(httpCode: Int, remoteDeleted: Boolean, error: Throwable) {
        val message = if (remoteDeleted) {
            "MOVE 覆盖已有目标返回 HTTP $httpCode，兼容写入未完成：云端旧文件已删除，临时文件保留待重试"
        } else {
            "MOVE 覆盖已有目标返回 HTTP $httpCode，服务器不支持安全覆盖：保留远端旧文件，仅清理临时文件"
        }
        AppLog.w("WebDavSyncProvider", message, error)
    }

    /** 按最终失败形态构造 ProtocolError；[remoteDeleted] 决定文案不得谎称旧文件仍在 */
    fun protocolErrorFor(
        overwriteRefusedCode: Int?,
        lastMoveCode: Int,
        remoteDeleted: Boolean = false
    ): SyncException.ProtocolError {
        val code = overwriteRefusedCode
        return if (code != null) {
            SyncException.ProtocolError(code, overwriteRefusedMessage(code, remoteDeleted))
        } else {
            SyncException.ProtocolError(500, genericMoveFailureMessage(lastMoveCode))
        }
    }
}

/**
 * 409/423 拒绝覆盖后、以重探元数据作出的裁决（纯策略，纯 JVM 可断言，ENG-20261028-20）。
 */
internal sealed interface OverwriteRefusalGuard {

    /** 远端未被并发改写而服务器仍拒绝覆盖：不支持安全覆盖 ⇒ 协议错误 + 保留远端旧文件 */
    data object RemoteUnchanged : OverwriteRefusalGuard

    /** 远端 ETag 已漂移（他端并发写入新版本）：转 ConflictError，交上层合并 */
    data class Drifted(val currentRemoteEtag: String) : OverwriteRefusalGuard

    /** 重探失败：与「成功但无 ETag」两回事（ISSUE-P2-501 分型），fail-closed 如实上抛 */
    data object ProbeFailed : OverwriteRefusalGuard
}
