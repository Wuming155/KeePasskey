package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.autofill.AutofillPackageNames
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.history.HistoryManager
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.Arrays

/**
 * 条目写入协调器（ISSUE-P3-31 批次 B 自 `RealVaultRepository` 拆出，纯结构性改动）。
 *
 * 职责单一：条目的合并保存（含历史快照、受保护字段回填、附件重挂、图标与 AutoType 合并）、
 * 收藏标记写入，以及自动填充凭据的 upsert 保存。
 *
 * 擦除契约（ISSUE-P3-305 起**两条写路径均收口于本类**，仓库门面只作转发不二次擦除）：
 * - `saveEntry` 的入参副本清零在 [saveEntryWithEraseContract] 的 `finally` 中统一执行；
 * - `saveAutofillCredential` 的入参清零保留在本类内（其调用链是单入口且无中间投影）。
 */
internal class VaultEntryWriteCoordinator(
    private val strings: StringsProvider,
    private val databaseSession: DatabaseSession,
    private val entryMapper: VaultEntryMapper,
    private val persistSession: suspend () -> KdbxResult<Unit>
) {

    /**
     * 条目保存入口（含擦除契约）。
     *
     * 擦除契约（加解密审查 2026-09）：任何结果路径（成功 / 失败 / 异常）用毕清零传入副本，
     * 与 `saveAutofillCredential` / `FakeVaultRepository` 同一契约。
     * TASK-10：TOTP 种子与受保护自定义字段明文副本同样纳入擦除契约。
     *
     * ISSUE-P3-305：自 `RealVaultRepository.saveEntry` 逐行搬出——清零动作与保存主体同处一器，
     * 调用方（仓库门面）只转发，不再重复清零。
     */
    suspend fun saveEntryWithEraseContract(
        entry: UiVaultEntry,
        passwordChars: CharArray?,
        totpSecretChars: CharArray?,
        protectedFieldChars: Map<String, CharArray>
    ): KdbxResult<Unit> {
        try {
            return saveEntryInternal(entry, passwordChars, totpSecretChars, protectedFieldChars)
        } finally {
            passwordChars?.fill('0')
            totpSecretChars?.fill('0')
            protectedFieldChars.values.forEach { it.fill('0') }
        }
    }

    /** 条目保存主体：既有条目合并更新（含历史修订），未命中则新建 */
    suspend fun saveEntryInternal(
        entry: UiVaultEntry,
        passwordChars: CharArray?,
        totpSecretChars: CharArray?,
        protectedFieldChars: Map<String, CharArray>
    ): KdbxResult<Unit> {
        val db = databaseSession.databaseFlow.first()
        val targetUuid = parseKdbxUuidOrNull(entry.id)
        val existing = if (targetUuid != null && db != null) {
            db.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
        } else null

        if (existing != null) {
            saveMergedEntry(entry, existing, db, passwordChars, totpSecretChars, protectedFieldChars)
        } else {
            // 新建条目
            val kdbxEntry = entryMapper.mapUiEntryToKdbx(entry, passwordChars, totpSecretChars, protectedFieldChars)
            databaseSession.saveEntry(kdbxEntry)
        }
        return persistSession()
    }

    /** 既有条目：五段字段各自合并后走 HistoryManager 记录修订，父组变更时换址重挂 */
    private suspend fun saveMergedEntry(
        entry: UiVaultEntry,
        existing: KdbxEntry,
        db: KdbxDatabase?,
        passwordChars: CharArray?,
        totpSecretChars: CharArray?,
        protectedFieldChars: Map<String, CharArray>
    ) {
        val targetParentId = entry.groupId?.let { parseKdbxUuidOrNull(it) } ?: existing.parentGroupId
        val pendingNewEntry = existing.copy(
            parentGroupId = targetParentId,
            fields = mergeStandardFields(existing, entry, passwordChars, totpSecretChars),
            customFields = mergeCustomFields(existing, entry, protectedFieldChars),
            attachments = mergeAttachments(existing, entry),
            // 断点7 整改：图标落盘——把 UI 图标名映射回 KDBX 标准 iconId
            iconId = entryMapper.mapIconNameToId(entry.iconName, fallbackId = existing.iconId),
            // TASK-15：自定义图标引用以 UI 选择为准（null=清除引用，回退标准图标）
            customIconId = entry.customIconId?.let { parseKdbxUuidOrNull(it) },
            tags = entry.tags,
            overrideUrl = entry.overrideUrl?.takeIf { it.isNotBlank() },
            // KP2A 能力补齐：tags / overrideUrl / AutoType 序列
            autoType = entryMapper.mergeAutoType(existing.autoType, entry.autoTypeSequence),
            // ISSUE-P3-310：过期两态写入（null = 关闭过期，写 expires=false；非 null = 指定到期时刻）
            times = mergeEntryTimes(existing.times, entry.expiresAt)
        )

        // P3-4 整改：历史修剪遵从库级 Meta 配置（historyMaxItems / historyMaxSize），
        // 缺失时回退官方默认值，不再写死 10 条上限
        val finalEntry = HistoryManager.recordHistorySnapshot(
            currentEntry = existing,
            newEntry = pendingNewEntry,
            maxHistoryItems = db?.historyMaxItems ?: HistoryManager.DEFAULT_MAX_HISTORY_ITEMS,
            maxHistorySize = db?.historyMaxSize ?: HistoryManager.DEFAULT_MAX_HISTORY_SIZE
        )

        if (targetParentId != existing.parentGroupId) {
            databaseSession.deleteEntry(existing.id)
        }
        databaseSession.saveEntry(finalEntry)
    }

    /** ISSUE-P3-310：过期两态合并——只改 `expires` / `expiryTime`，其余时间属性原样保留。 */
    private fun mergeEntryTimes(existing: com.keepasskey.core.model.KdbxTimes, expiresAt: Instant?): com.keepasskey.core.model.KdbxTimes =
        when (expiresAt) {
            null -> existing.copy(expires = false)
            else -> existing.copy(expires = true, expiryTime = expiresAt)
        }

    /** 标准字段：保留既有映射，仅覆盖提交字段（M1：密码仅在显式提交时更新） */
    private fun mergeStandardFields(
        existing: KdbxEntry,
        entry: UiVaultEntry,
        passwordChars: CharArray?,
        totpSecretChars: CharArray?
    ): MutableMap<String, ProtectedString> = existing.fields.toMutableMap().apply {
        put(KdbxConstants.Fields.TITLE, ProtectedString(entry.title, isProtected = false))
        put(KdbxConstants.Fields.USER_NAME, ProtectedString(entry.username, isProtected = false))
        passwordChars?.let {
            put(KdbxConstants.Fields.PASSWORD, ProtectedString(it, isProtected = true))
        }
        put(KdbxConstants.Fields.URL, ProtectedString(entry.url, isProtected = false))
        put(KdbxConstants.Fields.NOTES, ProtectedString(entry.notes, isProtected = false))
        // 断点4 整改：TOTP 种子显式提交（null=未修改；空串=清除；非空=写标准 otp 字段）
        // TASK-10：TOTP 配置以 CharArray 显式提交（null=未修改保留既有；空数组=清除），
        // 明文仅在 ProtectedString 密封瞬间物化，中间副本即时擦除
        if (totpSecretChars != null) {
            val trimmedTotp = totpSecretChars.trimmedCopy()
            if (trimmedTotp.isEmpty()) {
                remove(KdbxConstants.Fields.OTP)
            } else {
                put(KdbxConstants.Fields.OTP, ProtectedString(trimmedTotp, isProtected = true))
            }
            trimmedTotp.fill('0')
        }
    }

    /** 自定义字段：UI 覆盖者按原序落地，未在 UI 出现的既有字段（如 Passkey 属性）原样保留 */
    private fun mergeCustomFields(
        existing: KdbxEntry,
        entry: UiVaultEntry,
        protectedFieldChars: Map<String, CharArray>
    ): List<KdbxCustomField> {
        val uiCustomList = entry.customFields.map { cf ->
            mergeCustomField(existing, cf, protectedFieldChars)
        }
        val uiKeys = uiCustomList.map { it.key }.toSet()
        val preservedCustom = existing.customFields.filter { ef -> ef.key !in uiKeys }
        return uiCustomList + preservedCustom
    }

    private fun mergeCustomField(
        existing: KdbxEntry,
        cf: UiCustomField,
        protectedFieldChars: Map<String, CharArray>
    ): KdbxCustomField {
        // TASK-10：受保护自定义字段编辑态明文以 CharArray 显式提交（键为字段编辑 id），
        // 仅用户显式编辑过的字段出现在 protectedFieldChars 中。
        // 擦除语义：submittedChars 归 saveEntry 的 finally 契约擦除；回填路径的
        // readChars 独占副本在密封后立即擦除
        val submittedChars = if (cf.isProtected) protectedFieldChars[cf.id] else null
        // F2 整改：UI 投影中受保护字段的明文恒为空（按需解密），未编辑的受保护字段
        // 回写时「空值」视为未修改，回填既有条目的真实值——防止详情页回滚等携带掩码
        // 投影的保存路径清空受保护字段
        val existingField = existing.customFields.firstOrNull { it.key == cf.key }
        return when {
            submittedChars != null ->
                KdbxCustomField(cf.key, ProtectedString(submittedChars, isProtected = true))
            cf.isProtected && cf.value.isEmpty() -> {
                val backfillChars = existingField?.value?.readChars()
                try {
                    KdbxCustomField(cf.key, ProtectedString(backfillChars ?: CharArray(0), isProtected = true))
                } finally {
                    backfillChars?.fill('0')
                }
            }
            else ->
                KdbxCustomField(cf.key, ProtectedString(cf.value, isProtected = cf.isProtected))
        }
    }

    /**
     * 断点1-2 整改：附件全链路——UI 侧新附件（data 非空）直接随条目提交，
     * 已落库附件（data 为空）按名称匹配既有引用保留 refIndex；
     * UI 中被移除的附件不再出现在列表里，即自然从条目上删除（二进制池在保存时去重重建）
     */
    private fun mergeAttachments(existing: KdbxEntry, entry: UiVaultEntry): List<KdbxAttachment> =
        entry.attachments.map { ui ->
            if (ui.data != null) {
                KdbxAttachment(name = ui.fileName, data = ui.data, isProtected = false)
            } else {
                existing.attachments.firstOrNull { it.name == ui.fileName }
                    ?: KdbxAttachment(name = ui.fileName, data = byteArrayOf())
            }
        }

    /**
     * 收藏状态写入：持久化至 KDBX 条目 customData（随库文件同步），
     * 直接改内存树并落盘——不经 HistoryManager，收藏切换不产生历史修订
     */
    suspend fun setEntryFavorite(
        entryId: String,
        favorite: Boolean
    ): KdbxResult<Unit> {
        // TASK-34 整改：收藏状态持久化至 KDBX 条目 customData（随库文件同步），
        // 直接改内存树并落盘——不经 HistoryManager，收藏切换不产生历史修订
        val uuid = parseKdbxUuidOrNull(entryId)
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_invalid_entry_id)),
                strings.get(R.string.repo_entry_not_found)
            )
        val db = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        val entry = db.rootGroup.allEntries().firstOrNull { it.id == uuid }
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_entry_not_found)),
                strings.get(R.string.repo_entry_not_found)
            )

        val updated = entry.copy(
            customData = if (favorite) {
                entry.customData + (RealVaultRepository.FAVORITE_CUSTOM_DATA_KEY to "true")
            } else {
                entry.customData - RealVaultRepository.FAVORITE_CUSTOM_DATA_KEY
            },
            times = entry.times.copy(lastModificationTime = Instant.now())
        )
        databaseSession.saveEntry(updated)
        return persistSession()
    }

    /**
     * ISSUE-P3-49：仅改写条目 `otp` 字段（HOTP 计数器推进用），**不产生历史修订**。
     *
     * 计数器推进属「动态口令取用」而非用户对条目的内容修订；若走 [saveEntryInternal]
     * 会为每次取码追加一条历史快照（无意义且污染历史）。本入口与 [setEntryFavorite]
     * 同一语义：直接改内存树并落盘。
     *
     * [otpChars] 为新的 OTP 配置原文（`otpauth://` URI 或 Base32 种子），**借用语义**——
     * 本方法用毕不清零，调用方负责。
     */
    suspend fun updateEntryOtpConfig(entryId: String, otpChars: CharArray): KdbxResult<Unit> {
        val uuid = parseKdbxUuidOrNull(entryId)
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_invalid_entry_id)),
                strings.get(R.string.repo_entry_not_found)
            )
        val db = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        val entry = db.rootGroup.allEntries().firstOrNull { it.id == uuid }
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_entry_not_found)),
                strings.get(R.string.repo_entry_not_found)
            )
        val updated = entry.copy(
            fields = entry.fields + (KdbxConstants.Fields.OTP to ProtectedString(otpChars, isProtected = true)),
            times = entry.times.copy(lastModificationTime = Instant.now())
        )
        databaseSession.saveEntry(updated)
        return persistSession()
    }

    /**
     * 自动填充凭据保存（upsert）：命中同域/同包条目则更新，否则新建。
     *
     * 双通道防重（2026-09 共存审查）：Autofill SaveInfo 与 Credential Manager
     * 保存双通道均收敛于本方法。当目标条目与新凭据内容完全一致（同用户名、同密码，
     * 经 ProtectedString HMAC 等值标签常时比较，不解密、不物化明文）时，保存为
     * 幂等操作：直接返回成功并跳过落库——防止用户在两个保存弹窗各确认一次导致
     * history 修订翻倍与同步脏标记污染。
     */
    suspend fun saveAutofillCredential(
        packageName: String,
        webDomain: String?,
        username: String,
        passwordChars: CharArray
    ): KdbxResult<Unit> {
        try {
            val binding = resolveCredentialUrlBinding(webDomain, packageName)
            val allEntries = databaseSession.databaseFlow.first()?.rootGroup?.allEntries() ?: emptyList()

            val matchedEntry = allEntries.firstOrNull { entry ->
                val matchDomain = binding.isWebBinding && entry.url.isNotBlank() &&
                        DomainMatcher.isDomainMatch(entry.url, binding.displayDomain)
                // L1 整改：包名匹配仅走 DomainMatcher 严格点号边界（含 android:// scheme 剥离），
                // 移除 title/notes.contains 启发式
                val matchPackage = entry.url.isNotBlank() && DomainMatcher.isPackageMatch(entry.url, packageName)
                (matchDomain || matchPackage) && (entry.userName == username || entry.userName.isEmpty())
            }

            val pwdProtected = ProtectedString(passwordChars, isProtected = true)
            if (matchedEntry != null) {
                val passwordUnchanged = matchedEntry.password == pwdProtected
                val usernameUnchanged = matchedEntry.userName.isNotEmpty() || username.isBlank()
                if (passwordUnchanged && usernameUnchanged) {
                    return KdbxResult.Success(Unit)
                }
                var updated = matchedEntry.withField(KdbxConstants.Fields.PASSWORD, pwdProtected)
                if (updated.userName.isEmpty() && username.isNotBlank()) {
                    updated = updated.withField(KdbxConstants.Fields.USER_NAME, username)
                }
                databaseSession.saveEntry(updated)
            } else {
                val title = if (username.isNotBlank()) "$username@${binding.displayDomain}" else binding.displayDomain
                val fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
                    KdbxConstants.Fields.USER_NAME to ProtectedString(username, isProtected = false),
                    KdbxConstants.Fields.PASSWORD to pwdProtected,
                    KdbxConstants.Fields.URL to ProtectedString(binding.entryUrl, isProtected = false),
                    KdbxConstants.Fields.NOTES to ProtectedString("Package: $packageName", isProtected = false)
                )
                val newEntry = KdbxEntry(
                    id = KdbxUuid.random(),
                    parentGroupId = null,
                    fields = fields
                )
                databaseSession.saveEntry(newEntry)
            }
            return persistSession()
        } finally {
            Arrays.fill(passwordChars, '0')
        }
    }

    companion object {

        /**
         * ISSUE-P2-78（威胁建模 T-10）：凭据保存的 **URL 绑定形态分流**（纯函数，JVM 可测）。
         *
         * CM 保存通道把调用方 **origin**（而非裸域名）作为 `webDomain` 下传：
         * 浏览器委派为 `https://…`，普通应用为 `android:apk-key-hash:…`。原实现无条件拼
         * `"https://$domain"` → 落库为 `https://https://host` / `https://android:apk-key-hash:…`，
         * 条目此后既不匹配 web 域也不匹配 `android://` 包名（完整性 / 可用性缺陷）。
         *
         * 分流规则（自动填充与 CM 双保存通道共用本收口，语义对齐）：
         * - 空白 / `android:apk-key-hash:` origin → `android://<调用包名>`（无 web 域可绑定）；
         * - `https://`（含 `http://`）web origin → **原样入库**（不再二次拼前缀）；
         * - 其余按自动填充既有形态视为裸域名 → `https://<裸域名>`。
         *
         * @property entryUrl 落库 URL 字段值
         * @property displayDomain 展示域名（标题用）与域匹配判定输入
         * @property isWebBinding 是否为 web 域绑定（false 时域匹配维度不参与新凭据去重）
         */
        internal data class CredentialUrlBinding(
            val entryUrl: String,
            val displayDomain: String,
            val isWebBinding: Boolean
        )

        internal fun resolveCredentialUrlBinding(webDomain: String?, packageName: String): CredentialUrlBinding {
            val pkg = packageName.trim()
            val raw = webDomain?.trim()?.trimEnd('/')
            return when {
                raw.isNullOrEmpty() ->
                    CredentialUrlBinding(AutofillPackageNames.boundUrl(pkg), pkg, isWebBinding = false)

                raw.startsWith(com.keepasskey.app.passkey.CallingOriginResolver.APK_KEY_HASH_PREFIX) ->
                    CredentialUrlBinding(AutofillPackageNames.boundUrl(pkg), pkg, isWebBinding = false)
                raw.startsWith("https://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true) ->
                    CredentialUrlBinding(raw, DomainMatcher.extractDomain(raw), isWebBinding = true)
                // 自动填充保存路径的既有形态：裸域名
                else -> CredentialUrlBinding("https://$raw", raw, isWebBinding = true)
            }
        }
    }
}
