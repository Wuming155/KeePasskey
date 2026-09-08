package com.keepasskey.app.data.repository

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first

/**
 * Passkey 条目协调器（TASK-21 拆分自 RealVaultRepository）。
 * 职责单一：按 RP-ID/凭据 ID 检索 Passkey 条目、新建 Passkey 条目落库、
 * 签名计数器（signCount）就地修补。落盘统一经 [persistSession] 回传仓库出口。
 */
internal class PasskeyEntryCoordinator(
    private val databaseSession: DatabaseSession,
    private val debugLog: DebugLogBuffer,
    private val persistSession: suspend () -> KdbxResult<Unit>
) {

    private suspend fun allEntries(): List<KdbxEntry> {
        val db = databaseSession.databaseFlow.first() ?: return emptyList()
        return db.rootGroup.allEntries()
    }

    /** 按 RP-ID 匹配候选条目：Passkey rpId 域匹配优先，条目 URL 域匹配兜底 */
    suspend fun findEntriesForRpId(rpId: String): List<KdbxEntry> {
        val cleanTarget = DomainMatcher.extractDomain(rpId)
        return allEntries().filter { entry ->
            val passkey = PasskeyData.fromCustomFields(entry.customFields)
            val passkeyMatch = passkey != null && DomainMatcher.isDomainMatch(passkey.relyingPartyId, cleanTarget)
            val urlMatch = entry.url.isNotBlank() && DomainMatcher.isDomainMatch(entry.url, cleanTarget)
            passkeyMatch || urlMatch
        }
    }

    suspend fun findPasskeyByCredentialId(credentialId: String): KdbxEntry? {
        return allEntries().firstOrNull { entry ->
            val passkey = PasskeyData.fromCustomFields(entry.customFields)
            passkey?.credentialId == credentialId
        }
    }

    /** 新建 Passkey 条目并立即落盘；写库成功但序列化失败时如实留痕日志 */
    suspend fun saveNewPasskeyEntry(data: PasskeyData, boundPackage: String?): KdbxEntry {
        val title = "${data.userName}@${data.relyingPartyId}"
        val url = if (boundPackage.isNullOrBlank()) "https://${data.relyingPartyId}" else "android://$boundPackage"
        val fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
            KdbxConstants.Fields.USER_NAME to ProtectedString(data.userName, isProtected = false),
            KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false)
        )
        val newEntry = KdbxEntry(
            id = KdbxUuid.random(),
            parentGroupId = null,
            fields = fields,
            customFields = data.toCustomFields()
        )
        databaseSession.saveEntry(newEntry)
        val saved = persistSession()
        if (saved is KdbxResult.Failure) {
            debugLog.warn(TAG, "Passkey 条目创建成功但落盘失败: ${saved.message}")
        }
        return newEntry
    }

    /** 就地修补签名计数器（CTAP2 signCount 防克隆校验依赖其单调递增） */
    suspend fun patchPasskeySignCount(entryId: String, newCount: Int) {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return
        val currentDb = databaseSession.databaseFlow.first() ?: return
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return

        var found = false
        val updatedCustomFields = entry.customFields.map { cf ->
            if (cf.key == PasskeyData.FIELD_SIGN_COUNT) {
                found = true
                KdbxCustomField(cf.key, ProtectedString(newCount.toString(), isProtected = false))
            } else {
                cf
            }
        }.toMutableList()

        if (!found) {
            updatedCustomFields.add(KdbxCustomField(PasskeyData.FIELD_SIGN_COUNT, ProtectedString(newCount.toString(), isProtected = false)))
        }

        val updatedEntry = entry.copy(
            customFields = updatedCustomFields,
            times = entry.times.withModified()
        )
        databaseSession.saveEntry(updatedEntry)
        persistSession()
    }

    companion object {
        private const val TAG = "RealVaultRepository"
    }
}
