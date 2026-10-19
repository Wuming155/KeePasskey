package com.keepasskey.database.session

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 非秘密会话票据；同路径重开也产生新身份，普通内容修改不改变凭据代次。 */
class SessionCredentialTicket internal constructor(
    val pathIdentifier: String,
    val credentialGeneration: Long = 0,
    internal val sessionIdentity: Any = Any()
) {
    internal fun next() = SessionCredentialTicket(pathIdentifier, credentialGeneration + 1, sessionIdentity)
}

/** 排队写入时捕获的会话已失效，不再属于当前已解锁会话。 */
class SessionCredentialTicketMismatchException : IllegalStateException("受理后的会话已改变")

/** 每次受理独享请求；只有该请求实际提交才登记 [committedTicket]，不能借用另一任务的提交事实。 */
class SessionCredentialRequest internal constructor(internal val expected: SessionCredentialTicket?) :
    AbstractCoroutineContextElement(Key) {
    private val consumed = AtomicBoolean(false)
    val ticket: SessionCredentialTicket? get() = expected
    internal fun consumeOnce(): Boolean = consumed.compareAndSet(false, true)
    @Volatile
    var committedTicket: SessionCredentialTicket? = null
        private set
    @Volatile
    var writeStatus: SessionWriteReceipt.Status = SessionWriteReceipt.Status.NOT_STARTED
        private set

    internal fun recordCommitted(ticket: SessionCredentialTicket?) { committedTicket = ticket }
    internal fun recordWriteStatus(status: SessionWriteReceipt.Status) { writeStatus = status }
    internal companion object Key : CoroutineContext.Key<SessionCredentialRequest>
}
