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
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first

/**
 * Passkey 条目协调器（TASK-21 拆分自 RealVaultRepository）。
 * 职责单一：按 RP-ID/凭据 ID 检索 Passkey 条目、新建 Passkey 条目落库、
 * 签名计数器（signCount）就地修补（并顺带把 v1 旧 schema 条目就地迁移到 KPEX，ISSUE-P3-213）。
 * 落盘统一经 [persistSession] 回传仓库出口。
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

    /**
     * 新建或在**既有条目**上原地替换 Passkey 数据（整改：同站点重复注册曾产生重复条目）。
     *
     * 复用条件：**同 rpId（域匹配）+ 同用户名**的既有 Passkey 条目。命中时保留该条目的其它
     * 内容（标题 / 备注 / 密码 / 标签 / 历史 / 附件等），仅整体换新 Passkey schema 字段；
     * 未命中则按原口径新建条目。
     */
    suspend fun saveOrReplacePasskeyEntry(data: PasskeyData, boundPackage: String?): KdbxEntry {
        val existing = findReusablePasskeyEntry(data) ?: return saveNewPasskeyEntry(data, boundPackage)

        val preserved = existing.customFields.filterNot { PasskeyData.isPasskeyFieldKey(it.key) }
        var updated = existing.copy(
            customFields = preserved + data.toCustomFields(),
            times = existing.times.withModified()
        )
        if (existing.userName.isBlank()) {
            updated = updated.withField(KdbxConstants.Fields.USER_NAME, data.userName)
        }
        if (existing.title.isBlank()) {
            updated = updated.withField(KdbxConstants.Fields.TITLE, passkeyTitle(data))
        }
        if (existing.url.isBlank()) {
            updated = updated.withField(KdbxConstants.Fields.URL, passkeyUrl(data, boundPackage))
        }

        databaseSession.saveEntry(updated)
        val saved = persistSession()
        if (saved is KdbxResult.Failure) {
            debugLog.warn(TAG, "Passkey 条目替换成功但落盘失败: ${saved.error.javaClass.simpleName}")
        }
        return updated
    }

    /**
     * `excludeCredentials` 查重（单趟扫描）：返回 [candidates] 中**已存在于库内**的 credentialId 集合。
     *
     * WebAuthn 规范要求认证器拒绝创建排除列表内的凭据；命中即由调用方 fail-closed 拒绝注册，
     * 避免同一凭据被重复登记。
     */
    suspend fun findExistingCredentialIds(candidates: Set<String>): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val found = LinkedHashSet<String>()
        for (entry in allEntries()) {
            val passkey = PasskeyData.fromCustomFields(entry.customFields) ?: continue
            if (passkey.credentialId in candidates) found += passkey.credentialId
        }
        return found
    }

    /** 可复用的既有 Passkey 条目（同 rpId + 同用户名） */
    private suspend fun findReusablePasskeyEntry(data: PasskeyData): KdbxEntry? {
        val cleanTarget = DomainMatcher.extractDomain(data.relyingPartyId)
        return allEntries().firstOrNull { entry ->
            val passkey = PasskeyData.fromCustomFields(entry.customFields) ?: return@firstOrNull false
            passkey.userName == data.userName &&
                DomainMatcher.isDomainMatch(passkey.relyingPartyId, cleanTarget)
        }
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
        val title = passkeyTitle(data)
        val url = passkeyUrl(data, boundPackage)
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
            debugLog.warn(TAG, "Passkey 条目创建成功但落盘失败: ${saved.error.javaClass.simpleName}")
        }
        return newEntry
    }

    /**
     * 就地修补签名计数器（CTAP2 signCount 防克隆校验依赖其单调递增）。
     *
     * ISSUE-P3-10 子项 2（受控事务/原子语义）：「读取库内现值 → 计算目标值 → 替换条目」
     * 整体收口到会话 Mutex 内的**单次**受控变换中，消除原实现「先取 databaseFlow 快照、
     * 再 saveEntry（各自独立加锁）」之间被并发断言插入导致的丢失更新。
     *
     * ISSUE-P3-157：承载该变换的入口由 [DatabaseSession.updateDatabaseMeta]（任意整库变换，
     * 擦除退化为整棵新树的 O(全库) 身份集合）改为 [DatabaseSession.updateEntryById]——
     * 按 id 定位、**路径复制**（只重建从根到命中位置的分组链，未命中分支按引用复用），
     * 且替换关系回报使定点擦除的候选只含被替换的那一条旧条目。**原子性不变**：
     * 现值读取、目标值计算与落树同处一个临界区，不得拆到锁外。
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
     * 「读取库内现值 → 计算目标值 → 替换条目」（并顺带完成 v1 → KPEX 就地迁移），
     * 并回传实际落库值。
     *
     * 回传值**从已提交条目读回**（而非旁路保存本地算出的值）：两者本就同源，
     * 读回可确保「返回给调用方的计数器」严格等于库内已提交状态。
     *
     * @param requested 调用方期望值（已钳制到合法区间）；null 表示「以库内现值为基准纯递增」。
     * @return 实际落库值；条目不存在 / UUID 非法时返回 null（本次无任何写入、无落盘调用）。
     */
    private suspend fun applySignCountPatch(entryId: String, requested: Int?): Int? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val patched = databaseSession.updateEntryById(targetUuid) { entry ->
            val monotonicFloor = PasskeyData.nextSignCount(PasskeyData.readSignCount(entry.customFields))
            val value = if (requested == null) monotonicFloor else maxOf(requested, monotonicFloor)
            entryWithSignCount(entry, value)
        } ?: return null

        val applied = PasskeyData.readSignCount(patched.customFields)
        val saved = persistSession()
        if (saved is KdbxResult.Failure) {
            debugLog.warn(TAG, "签名计数器已更新但落盘失败: ${saved.error.javaClass.simpleName}")
        }
        return applied
    }

    /**
     * 就地写回计数器与修改时间（copy-on-write；其余字段与既有实例按引用复用，避免误伤擦除逻辑）。
     *
     * ISSUE-P3-213：命中「仍是 v1 旧 schema」的条目时，**在同一受控变换内**先整条迁移到 KPEX
     * （见 [migrateLegacySchemaIfNeeded]），再写入计数器——两次改动同属一次原子替换，
     * 不存在「迁移成功但计数器没写」或反之的中间态。
     */
    private fun entryWithSignCount(entry: KdbxEntry, value: Int): KdbxEntry {
        val migrated = migrateLegacySchemaIfNeeded(entry)
        return migrated.copy(
            customFields = migrated.customFields.withSignCount(value),
            times = migrated.times.withModified()
        )
    }

    /**
     * v1 旧 schema → KPEX 的**写路径就地迁移**（ISSUE-P3-213）。
     *
     * 为何要做：v1 条目（`Passkey.*` 旧键 + hex/Base64 私钥）对 KeePassXC / KeePassDX 不可读，
     * 此前只有「用户重新注册该站点（原地替换）」或重新导入才会换新 schema——常态使用该凭据的用户
     * 永远等不到那次换新。此处借「断言必然修补计数器」这一既有写路径，让老库随使用自然收敛。
     *
     * 迁移内容：保留全部非 passkey 字段（标题 / 备注 / 标签 / 附件等，按引用复用），
     * 丢弃全部 v1 旧键与旧扩展键，写入 [PasskeyData.toCustomFields] 的 KPEX 规范字段；
     * 私钥经 [PasskeyCryptoEngine.legacyPrivateKeyTextToPemChars] 重包为 **PKCS#8 PEM**
     * （受保护属性写入 `KPEX_PASSKEY_PRIVATE_KEY_PEM`）。
     *
     * **失败即放弃**：私钥重包失败（形态非法 / 算法不匹配 / 标量越界）时原样返回条目，
     * 只留痕不迁移——迁移是收敛动作，绝不允许它把一条仍可断言的历史凭据改成不可用。
     */
    private fun migrateLegacySchemaIfNeeded(entry: KdbxEntry): KdbxEntry {
        if (!PasskeyData.needsKpexMigration(entry.customFields)) return entry
        val data = PasskeyData.fromCustomFields(entry.customFields) ?: return entry
        val pemChars = data.usePrivateKeyBytes { raw ->
            PasskeyCryptoEngine.legacyPrivateKeyTextToPemChars(raw, data.algorithmId)
        }
        if (pemChars == null) {
            debugLog.warn(TAG, "v1 旧条目私钥无法重包为 PKCS#8 PEM，保持旧 schema 不迁移")
            return entry
        }
        val pemKey = try {
            ProtectedString(pemChars, isProtected = true)
        } finally {
            pemChars.fill('0')
        }
        val preserved = entry.customFields.filterNot { PasskeyData.isPasskeyFieldKey(it.key) }
        return entry.copy(customFields = preserved + data.copy(privateKey = pemKey).toCustomFields())
    }

    /** 新条目标题（`用户名@rpId`） */
    private fun passkeyTitle(data: PasskeyData): String = "${data.userName}@${data.relyingPartyId}"

    /** 条目 URL：普通应用注册记 `android://<包名>`（严格包名边界匹配），浏览器注册记 `https://<rpId>` */
    private fun passkeyUrl(data: PasskeyData, boundPackage: String?): String =
        if (boundPackage.isNullOrBlank()) {
            "https://${data.relyingPartyId}"
        } else {
            com.keepasskey.app.autofill.AutofillPackageNames.boundUrl(boundPackage)
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
