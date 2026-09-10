package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
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
 * 擦除契约**不随本拆分改变**：
 * - `saveEntry` 的入参副本清零仍由仓库在 `finally` 中统一执行（本类不重复清零，避免二次擦除混淆所有权）；
 * - `saveAutofillCredential` 的入参清零保留在本类内（其调用链是单入口且无中间投影）。
 */
internal class VaultEntryWriteCoordinator(
    private val strings: StringsProvider,
    private val databaseSession: DatabaseSession,
    private val entryMapper: VaultEntryMapper,
    private val persistSession: suspend () -> KdbxResult<Unit>
) {

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
            // 既有条目做合并更新：保留既有元数据与属性，更新提交字段，接入 HistoryManager
            // M1 整改：密码仅在显式提交（passwordChars 非空）时更新，否则保留既有密码
            val mergedFields = existing.fields.toMutableMap().apply {
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
                        put(KdbxConstants.Fields.OTP, ProtectedString(trimmedTotp, isProtected = false))
                    }
                    trimmedTotp.fill('0')
                }
            }

            val uiCustomList = entry.customFields.map { cf ->
                // TASK-10：受保护自定义字段编辑态明文以 CharArray 显式提交（键为字段编辑 id），
                // 仅用户显式编辑过的字段出现在 protectedFieldChars 中。
                // 擦除语义：submittedChars 归 saveEntry 的 finally 契约擦除；回填路径的
                // readChars 独占副本在密封后立即擦除
                val submittedChars = if (cf.isProtected) protectedFieldChars[cf.id] else null
                // F2 整改：UI 投影中受保护字段的明文恒为空（按需解密），未编辑的受保护字段
                // 回写时「空值」视为未修改，回填既有条目的真实值——防止详情页回滚等携带掩码
                // 投影的保存路径清空受保护字段
                val existingField = existing.customFields.firstOrNull { it.key == cf.key }
                when {
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
            val uiKeys = uiCustomList.map { it.key }.toSet()
            // 保留既有条目中未在 UI 覆盖的系统字段（例如 Passkey 属性等）
            val preservedCustom = existing.customFields.filter { ef -> ef.key !in uiKeys }
            val mergedCustomFields = uiCustomList + preservedCustom

            val targetParentId = entry.groupId?.let { parseKdbxUuidOrNull(it) } ?: existing.parentGroupId
            val isParentChanged = targetParentId != existing.parentGroupId

            // 断点1-2 整改：附件全链路——UI 侧新附件（data 非空）直接随条目提交，
            // 已落库附件（data 为空）按名称匹配既有引用保留 refIndex；
            // UI 中被移除的附件不再出现在列表里，即自然从条目上删除（二进制池在保存时去重重建）
            val mergedAttachments = entry.attachments.map { ui ->
                if (ui.data != null) {
                    KdbxAttachment(name = ui.fileName, data = ui.data, isProtected = false)
                } else {
                    existing.attachments.firstOrNull { it.name == ui.fileName }
                        ?: KdbxAttachment(name = ui.fileName, data = byteArrayOf())
                }
            }

            // 断点7 整改：图标落盘——把 UI 图标名映射回 KDBX 标准 iconId
            val newIconId = entryMapper.mapIconNameToId(entry.iconName, fallbackId = existing.iconId)

            // KP2A 能力补齐：tags / overrideUrl / AutoType 序列
            val mergedAutoType = entryMapper.mergeAutoType(existing.autoType, entry.autoTypeSequence)

            val pendingNewEntry = existing.copy(
                parentGroupId = targetParentId,
                fields = mergedFields,
                customFields = mergedCustomFields,
                attachments = mergedAttachments,
                iconId = newIconId,
                // TASK-15：自定义图标引用以 UI 选择为准（null=清除引用，回退标准图标）
                customIconId = entry.customIconId?.let { parseKdbxUuidOrNull(it) },
                tags = entry.tags,
                overrideUrl = entry.overrideUrl?.takeIf { it.isNotBlank() },
                autoType = mergedAutoType
            )

            // P3-4 整改：历史修剪遵从库级 Meta 配置（historyMaxItems / historyMaxSize），
            // 缺失时回退官方默认值，不再写死 10 条上限
            val finalEntry = HistoryManager.recordHistorySnapshot(
                currentEntry = existing,
                newEntry = pendingNewEntry,
                maxHistoryItems = db?.historyMaxItems ?: HistoryManager.DEFAULT_MAX_HISTORY_ITEMS,
                maxHistorySize = db?.historyMaxSize ?: HistoryManager.DEFAULT_MAX_HISTORY_SIZE
            )

            if (isParentChanged) {
                databaseSession.deleteEntry(existing.id)
            }
            databaseSession.saveEntry(finalEntry)
        } else {
            // 新建条目
            val kdbxEntry = entryMapper.mapUiEntryToKdbx(entry, passwordChars, totpSecretChars, protectedFieldChars)
            databaseSession.saveEntry(kdbxEntry)
        }
        return persistSession()
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
            val domain = webDomain?.takeIf { it.isNotBlank() }
            val allEntries = databaseSession.databaseFlow.first()?.rootGroup?.allEntries() ?: emptyList()

            val matchedEntry = allEntries.firstOrNull { entry ->
                val matchDomain = domain != null && entry.url.isNotBlank() && DomainMatcher.isDomainMatch(entry.url, domain)
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
                val titleDomain = domain ?: packageName
                val title = if (username.isNotBlank()) "$username@$titleDomain" else titleDomain
                val url = if (domain != null) "https://$domain" else "android://$packageName"
                val fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
                    KdbxConstants.Fields.USER_NAME to ProtectedString(username, isProtected = false),
                    KdbxConstants.Fields.PASSWORD to pwdProtected,
                    KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false),
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
}
