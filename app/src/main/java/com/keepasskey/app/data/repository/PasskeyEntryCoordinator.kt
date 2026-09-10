package com.keepasskey.app.data.repository

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
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

    /**
     * 就地修补签名计数器（CTAP2 signCount 防克隆校验依赖其单调递增）。
     *
     * ISSUE-P3-10 子项 2（受控事务/原子语义）：读-改-写整体收口到
     * [DatabaseSession.updateDatabaseMeta]——「读取库内现值 → 计算目标值 → 替换条目」
     * 发生在会话 Mutex 内的**单次**受控变换中，消除原实现「先取 databaseFlow 快照、
     * 再 saveEntry（各自独立加锁）」之间被并发断言插入导致的丢失更新。
     *
     * 取值约束：入参经 [PasskeyData.clampSignCount] 钳制到合法区间，并以
     * `库内现值 + 1`（[PasskeyData.nextSignCount]，饱和不回退）为下界取较大者，
     * 因此任何入参都不可能写入负值、超上界值，也不会让已推进的计数器回退。
     *
     * ISSUE-P3-27 子项 2：本入口保留原签名（仓库接口 [VaultRepository.patchPasskeySignCount] 的
     * 既有调用点以表达式体覆写，返回类型不可变），落库逻辑与 [incrementPasskeySignCount]
     * **完全同源**（同一私有原子核心），只是不向调用方回传落库值。
     * 需要把计数器写进断言响应（AuthenticatorData）的调用方必须改用
     * [incrementPasskeySignCount]，否则会以锁外快照自行计算而向 RP 交出重复值。
     */
    suspend fun patchPasskeySignCount(entryId: String, newCount: Int) {
        applySignCountPatch(entryId, PasskeyData.clampSignCount(newCount))
    }

    /**
     * 原子递增签名计数器并返回**本次实际落库的计数器值**（ISSUE-P3-27 子项 2）。
     *
     * 语义等价于 [PasskeyData.nextSignCount]（库内现值 + 1，上界饱和绝不回绕），且与落库同属
     * 会话 Mutex 内的**单次**受控变换，因此返回值就是「已提交」的计数器：并发断言各自调用
     * 本方法即可取得互不相同的值（回归用例「32 路并发递增…」）。
     *
     * 为何必须回传落库值：WebAuthn 断言响应中的 signCount 必须与库内计数器一致且单调
     * （RP 侧防克隆校验）。若调用方改用「进入断言前读到的快照 + 1」自行计算，则并发断言
     * （或「已签名成功、落盘前进程中断」后的重试）会向 RP 交出**重复**的 signCount，
     * 而库内计数器仍在推进——签名值与库内值就此错位。故断言路径的唯一正确取值来源是本方法。
     *
     * @return 本次实际落库的计数器值；条目不存在 / UUID 非法时返回 null，且不写入、不落盘
     *   （调用方此时不得签发断言）。
     */
    suspend fun incrementPasskeySignCount(entryId: String): Int? = applySignCountPatch(entryId, null)

    /**
     * 签名计数器原子读-改-写核心：在会话 Mutex 内的单次受控变换中完成
     * 「读取库内现值 → 计算目标值 → 替换条目」，并回传实际落库值。
     *
     * @param requested 调用方期望值（已钳制到合法区间）；null 表示「以库内现值为基准纯递增」。
     * @return 实际落库值；条目不存在时返回 null（本次无任何写入、无落盘调用）。
     */
    private suspend fun applySignCountPatch(entryId: String, requested: Int?): Int? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        var applied: Int? = null

        databaseSession.updateDatabaseMeta { db ->
            val entry = db.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
            if (entry == null) {
                db
            } else {
                val monotonicFloor = PasskeyData.nextSignCount(PasskeyData.readSignCount(entry.customFields))
                val value = if (requested == null) monotonicFloor else maxOf(requested, monotonicFloor)
                applied = value
                db.copy(rootGroup = replaceEntry(db.rootGroup, entry.id, entryWithSignCount(entry, value)))
            }
        }

        if (applied != null) {
            val saved = persistSession()
            if (saved is KdbxResult.Failure) {
                debugLog.warn(TAG, "签名计数器已更新但落盘失败: ${saved.message}")
            }
        }
        return applied
    }

    /** 就地写回计数器与修改时间（copy-on-write；其余字段与既有实例按引用复用，避免误伤擦除逻辑）。 */
    private fun entryWithSignCount(entry: KdbxEntry, value: Int): KdbxEntry = entry.copy(
        customFields = entry.customFields.withSignCount(value),
        times = entry.times.withModified()
    )

    /**
     * 在分组树中以 [updated] 替换 id 相同的条目（copy-on-write，未命中分支不重建）。
     *
     * 其余自定义字段与标准字段按引用复用，使 [DatabaseSession] 的身份擦除
     * （`clearSupersededSensitiveData`）不会误伤新树仍存活的密文实例。
     */
    private fun replaceEntry(group: KdbxGroup, entryId: KdbxUuid, updated: KdbxEntry): KdbxGroup {
        val newEntries = group.entries.map { if (it.id == entryId) updated else it }
        val newSubgroups = group.subgroups.map { replaceEntry(it, entryId, updated) }
        return group.copy(entries = newEntries, subgroups = newSubgroups)
    }

    /** 以受保护字段形态写入计数器：命中则原位替换，缺失则追加 */
    private fun List<KdbxCustomField>.withSignCount(value: Int): List<KdbxCustomField> {
        val encoded = ProtectedString(value.toString(), isProtected = false)
        if (none { it.key == PasskeyData.FIELD_SIGN_COUNT }) {
            return this + KdbxCustomField(PasskeyData.FIELD_SIGN_COUNT, encoded)
        }
        return map { cf ->
            if (cf.key == PasskeyData.FIELD_SIGN_COUNT) KdbxCustomField(cf.key, encoded) else cf
        }
    }

    companion object {
        private const val TAG = "RealVaultRepository"
    }
}
