package com.keepasskey.database.session

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/**
 * 会话凭据请求的传播入口（`ISSUE-P2-640` / `ISSUE-P2-641` 批次收口，自 [DatabaseSession] 拆出）。
 *
 * 请求只承载**会话与凭据代次**（[SessionCredentialTicket]），不持有密码或数据库树；真正改树与
 * 写盘仍在各自临界区内复核票据（`requireCurrentSessionRequest`）。三种入口的分工必须区分清楚：
 * - [withCredentialRequest]：**新受理点**——上下文里已有别的请求时一律拒绝（fail-closed）；
 * - [withCredentialRequestScope]：**续用已受理请求**——批量作业在同一次受理内被再次调度时使用；
 * - [currentRequest]：读取当前上下文已受理的请求，供批量作业取回原请求（而非重新捕获新票据）。
 *
 * 为什么续用判据取「同一票据代次」而不是「同一实例」：批量路径（导入逐条保存 → 每次保存各自
 * 捕获一个请求对象）会在同一次受理内产生多个实例，票据相同；按实例判定会把正常续用误判成
 * 「嵌套覆盖」并让整批落库失败（实测 `uiState=Failed(PERSIST)`）。
 */
suspend fun <T> DatabaseSession.withCredentialRequest(
    request: SessionCredentialRequest,
    block: suspend () -> T
): T {
    val enclosing = coroutineContext[SessionCredentialRequest]
    check(enclosing == null || enclosing === request) { "禁止嵌套覆盖已受理凭据请求" }
    return withContext(request) { block() }
}

/**
 * 当前协程上下文已受理的凭据请求（未受理时为 null）。
 *
 * 供批量作业在续用同一次受理时取回原请求：重新 `captureCredentialRequest()` 会拿到**此刻**的
 * 票据，与受理时刻的票据不同，`requireCurrentSessionRequest` 便判为「会话已变」。
 */
suspend fun DatabaseSession.currentRequest(): SessionCredentialRequest? =
    coroutineContext[SessionCredentialRequest]

/**
 * 普通用户写操作的**受理点**：在第一处挂起前固定票据，使读取候选、改树与落盘共用同一
 * [SessionCredentialRequest]；已在同一次受理的上下文中（导入批量、凭据流程嵌套调用）则
 * 沿用外层请求实例，不再重新捕获。
 *
 * 重新捕获会把「受理后会话已换」悄悄改写为「此刻的新会话」，改树与写盘便落到后来活动库；
 * 本入口只捕获一次，后续 `requireCurrentSessionRequest` 按实例身份复核。无请求上下文的
 * 内部入口仍由调用方自行保证票据，见 `SessionRequestGuard.kt`。
 */
suspend fun <T> DatabaseSession.withAcceptedRequest(
    block: suspend (SessionCredentialRequest) -> T
): T {
    val request = coroutineContext[SessionCredentialRequest] ?: captureCredentialRequest()
    return withCredentialRequestScope(request) { block(request) }
}

/**
 * 在 [request] 的票据范围内执行 [block]，**允许**调用方已在同一上下文受理同一票据的请求。
 *
 * 语义仍是 fail-closed：上下文里存在**另一个**票据代次的请求时照常拒绝。
 */
suspend fun <T> DatabaseSession.withCredentialRequestScope(
    request: SessionCredentialRequest,
    block: suspend () -> T
): T {
    val enclosing = coroutineContext[SessionCredentialRequest]
    if (enclosing != null && enclosing.ticket === request.ticket) return block()
    check(enclosing == null) { "禁止嵌套覆盖已受理凭据请求" }
    return withContext(request) { block() }
}
