package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first

/**
 * 条目敏感值读取协调器（ISSUE-P3-31 批次 B 自 `RealVaultRepository` 拆出，纯结构性改动）。
 *
 * 职责单一：条目的密码 / 历史修订密码 / 修订快照 / 受保护自定义字段 / TOTP
 * （计算与配置原文）/ 附件数据的**按需解密读取**。
 *
 * 擦除契约（ISSUE-P2-15）保持不变：
 * - [readErasableString] 中转的 CharArray 副本在用毕立即清零；
 * - 所有 `CharArray` 通道返回**独占副本**，清零责任按借用契约移交调用方；
 * - TOTP 计算路径持有的 Base32 种子字节在 `finally` 中擦除。
 */
internal class VaultEntrySecretReader(
    private val databaseSession: DatabaseSession,
    private val entryMapper: VaultEntryMapper
) {

    /**
     * ISSUE-P2-15 兼容读取通道：把受保护值读成 String 并保证中间 CharArray 副本即时清零。
     *
     * 返回值本身仍是不可擦除的 String（UI 投影模型与待下线 String 接口的既有约束），
     * 但相对直接调用 `ProtectedString.readString()`，本通道不再让明文副本静默等待 GC。
     * 新代码应优先使用对应的 CharArray 借用通道。
     */
    internal fun readErasableString(value: ProtectedString?): String? {
        val chars = value?.readChars() ?: return null
        return try {
            String(chars)
        } finally {
            chars.fill('0')
        }
    }

    /** ISSUE-P2-15：返回受保护值的 CharArray 独占副本（或 null），清零责任随借用契约移交调用方 */
    internal fun readErasableChars(value: ProtectedString?): CharArray? = value?.readChars()

    suspend fun getEntryPassword(entryId: String): String? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
        // ISSUE-P2-15：不再直接 readString()，经 CharArray 独占副本中转并即时清零
        return readErasableString(entry?.password)
    }

    suspend fun getEntryPasswordChars(entryId: String): CharArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
        // readChars() 返回独占副本（内部中间量已清零），清零责任随契约移交调用方
        return entry?.password?.readChars()
    }

    suspend fun getEntryRevisionPassword(entryId: String, revisionId: String): String? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val revisionUuid = parseKdbxUuidOrNull(revisionId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
        // ISSUE-P2-15：不再直接 readString()，经 CharArray 独占副本中转并即时清零
        return readErasableString(entry?.history?.firstOrNull { it.id == revisionUuid }?.password)
    }

    suspend fun getEntryRevisionPasswordChars(entryId: String, revisionId: String): CharArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val revisionUuid = parseKdbxUuidOrNull(revisionId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
        // M2 整改：回滚路径全程 CharArray（readChars 返回独占副本，内部中间量已清零）
        return entry?.history?.firstOrNull { it.id == revisionUuid }?.password?.readChars()
    }

    suspend fun getEntryRevisionSnapshot(entryId: String, revisionId: String): EntryRevisionSnapshot? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val revisionUuid = parseKdbxUuidOrNull(revisionId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        val revision = entry.history.firstOrNull { it.id == revisionUuid } ?: return null
        // 断点8 整改：整修订快照投影 + 受保护字段解密回填（仅驻留回滚会话），
        // 使回滚保存时 title/url/自定义字段/TOTP/密码全字段真实还原
        val projection = entryMapper.mapKdbxEntryToUi(revision, currentDb)
        // ISSUE-P2-15：受保护字段经 CharArray 独占副本中转（用毕清零）；UiCustomField.value 仍为
        // String（UI 投影模型已知约束），此处物化的 String 属投影边界、不可擦，见 ISSUE-P2-15 备注
        val decryptedFields = projection.customFields.map { cf ->
            if (cf.isProtected) {
                cf.copy(
                    value = readErasableString(
                        revision.customFields.firstOrNull { it.key == cf.key }?.value
                    ).orEmpty()
                )
            } else {
                cf
            }
        }
        // ISSUE-P2-15：TOTP 原文以 CharArray 独占副本返回，不再物化不可擦 String
        val totpRawChars = readErasableChars(revision.fields[KdbxConstants.Fields.OTP])
            ?: readErasableChars(
                revision.customFields.firstOrNull {
                    it.key.equals(KdbxConstants.Fields.OTP, ignoreCase = true) ||
                        it.key.startsWith(VaultEntryMapper.TOTP_CUSTOM_FIELD_PREFIX, ignoreCase = true)
                }?.value
            )
            ?: CharArray(0)
        return EntryRevisionSnapshot(
            entry = projection.copy(customFields = decryptedFields),
            totpSecretChars = totpRawChars
        )
    }

    suspend fun getEntryProtectedFieldChars(entryId: String, fieldKey: String): CharArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        // TASK-10：编辑态 CharArray 化——readChars 返回独占副本，调用方按借用语义用毕清零
        return entry.customFields.firstOrNull { it.key == fieldKey }?.value?.readChars()
    }

    suspend fun calculateEntryTotp(entryId: String): EntryTotpSnapshot? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        // 种子仅在数据层内瞬时解析并参与计算，绝不随结果外泄
        val config = entryMapper.parseTotpConfig(entry) ?: return null
        return try {
            val code = entryMapper.computeTotpCode(config) ?: return null
            EntryTotpSnapshot(
                code = code,
                periodSeconds = config.period,
                digits = config.digits,
                algorithm = config.algorithm
            )
        } finally {
            // ISSUE-P2-12：解析配置持有的 Base32 种子字节用毕即擦（成功/失败路径一致）
            config.secret.fill(0)
        }
    }

    suspend fun getEntryTotpSecretChars(entryId: String): CharArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        // 断点4 整改：与 parseTotpConfig 同源读取（otp 字段优先，回退 TOTP 开头的自定义字段）。
        // TASK-10：返回配置原文独占 CharArray 副本（otpauth:// URI 或 Base32 种子）供编辑页回填，
        // 调用方按借用语义用毕清零
        return entry.fields[KdbxConstants.Fields.OTP]?.readChars()
            ?: entry.customFields.firstOrNull {
                it.key.equals(KdbxConstants.Fields.OTP, ignoreCase = true) ||
                    it.key.startsWith(VaultEntryMapper.TOTP_CUSTOM_FIELD_PREFIX, ignoreCase = true)
            }?.value?.readChars()
    }

    suspend fun getAttachmentData(entryId: String, fileName: String): ByteArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        val binaryPool = currentDb.binaries.map { it.data }
        val attachment = entry.attachments.firstOrNull { it.name == fileName } ?: return null
        val bytes = attachment.resolveData(binaryPool)
        return if (bytes.isEmpty()) null else bytes.copyOf()
    }
}
