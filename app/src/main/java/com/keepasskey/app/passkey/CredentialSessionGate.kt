package com.keepasskey.app.passkey

import android.content.Intent
import com.keepasskey.database.exception.KdbxSessionChangedException
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.session.SessionCredentialTicket
import javax.inject.Inject

/** 单个凭据窗口持有的入口绑定；等待解锁、用户验证和后台保存均沿用同一目标。 */
class CredentialSessionGate @Inject constructor(private val session: DatabaseSession) {
    private var requestId: String? = null
    private var context: CredentialEntryRequestContext? = null
    private var ticket: SessionCredentialTicket? = null

    internal fun bind(intent: Intent, type: CredentialEntryRequestType, allowUnlock: Boolean = false): Boolean {
        val identity = CredentialPendingIntents.requestIdFromIntent(intent) ?: return false
        return bind(identity, type, allowUnlock)
    }

    internal fun bind(identity: String, type: CredentialEntryRequestType, allowUnlock: Boolean = false): Boolean {
        val candidate = CredentialPendingIntents.requestContext(identity, type) ?: return false
        val target = candidate.databaseId?.takeIf { it.isNotBlank() } ?: return false
        val original = candidate.sessionTicket
        if (original == null && !allowUnlock) return false
        if (original != null && (original.pathIdentifier != target || !session.isCredentialTicketCurrent(original))) return false
        requestId = identity
        context = candidate
        ticket = original
        return true
    }

    internal fun matchesCaller(origin: String, packageName: String): Boolean {
        val bound = context ?: return false
        return bound.expectedOrigin == origin && bound.expectedPackage == packageName
    }

    internal fun matchesEntry(entryId: String): Boolean = context?.entryId == entryId

    private fun requireCurrent(): SessionCredentialTicket {
        val bound = context ?: throw KdbxSessionChangedException()
        if (CredentialPendingIntents.requestContext(requestId.orEmpty(), bound.requestType) !== bound) {
            throw KdbxSessionChangedException()
        }
        // 锁定时创建的入口只允许在原库解锁后绑定一次；此后同路径重开也必须拒绝。
        val accepted = ticket ?: session.captureCredentialRequest().ticket
            ?.takeIf { it.pathIdentifier == bound.databaseId }
            ?.also { ticket = it }
            ?: throw KdbxSessionChangedException()
        if (!session.isCredentialTicketCurrent(accepted)) throw KdbxSessionChangedException()
        return accepted
    }

    /** 无 CM 绑定时用于传统 Autofill；CM 调用者必须先成功 bind。 */
    internal suspend fun <T> withRequest(block: suspend () -> T): T {
        if (context == null) return block()
        val accepted = requireCurrent()
        return session.withCredentialRequest(session.captureCredentialRequest(accepted)) { block() }
    }

    /** 只在窄锁内交付本地结果，不能在 block 中再次访问数据库或等待验证。 */
    internal suspend fun complete(block: () -> Unit): Boolean {
        if (context == null) { block(); return true }
        val accepted = requireCurrent()
        var delivered = false
        session.useCredentialTicket(accepted) {
            requireCurrent()
            block()
            delivered = true
        }
        return delivered
    }
}
