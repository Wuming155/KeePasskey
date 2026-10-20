package com.keepasskey.database.session

import com.keepasskey.database.exception.KdbxSessionChangedException
import kotlinx.coroutines.currentCoroutineContext

/** 只能在实际改树/写盘的会话 mutex 内调用；不另取锁，避免嵌套重入。 */
internal suspend fun requireCurrentSessionRequest(current: SessionCredentialTicket?) {
    val request = currentCoroutineContext()[SessionCredentialRequest] ?: return
    if (request.ticket == null || request.ticket !== current) throw KdbxSessionChangedException()
}
