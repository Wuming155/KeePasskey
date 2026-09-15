package com.keepasskey.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.StringsProvider
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * TASK-21 拆分：设置页「导出 / 模板安装 / 调试日志导出」动作控制器。
 * 导出三件套走仓库真实序列化 + SAF 落盘，模板安装真实建组落库，
 * 调试日志导出经脱敏后写 SAF 目标 Uri；反馈文案经 [StringsProvider] 资源解析（P3-23）。
 */
internal class SettingsExportController(
    private val vaultRepository: VaultRepository,
    private val debugLogBuffer: DebugLogBuffer,
    private val appContext: Context?,
    private val strings: StringsProvider,
    private val scope: CoroutineScope
) {

    /** ISSUE-P2-10 (ZT-15)：导出审计记录器（复用 DebugLogBuffer，仅写类型与脱敏目标标识） */
    private val exportAuditRecorder = ExportAuditRecorder(debugLogBuffer)

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    // ========== 断点整改：调试日志导出（SAF CreateDocument 真实落盘） ==========
    private val debugExportFeedbackFlow = MutableStateFlow<UiMessage?>(null)

    /** SAF 另存为结果反馈（成功/失败），由 Screen 层消费后清除 */
    val debugExportFeedback: StateFlow<UiMessage?> = debugExportFeedbackFlow.asStateFlow()

    /**
     * 断点整改：真实导出调试日志——内容经脱敏（移除网址与账号字段）后写入 SAF 目标 Uri。
     * [targetUri] 由 Screen 层 CreateDocument 选择器产生；此前导出仅弹 Snackbar，从未落盘。
     */
    fun exportDebugLogs(targetUri: Uri) {
        val resolver = appContext?.contentResolver
        if (resolver == null) {
            debugExportFeedbackFlow.value = UiMessage(R.string.debug_export_failed)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val sanitizedText = debugLogBuffer.exportSanitizedText()
                val written = resolver.openOutputStream(targetUri)?.use { os ->
                    os.write(sanitizedText.toByteArray(Charsets.UTF_8))
                    os.flush()
                    true
                } ?: false
                debugExportFeedbackFlow.value =
                    if (written) UiMessage(R.string.debug_export_done)
                    else UiMessage(R.string.debug_export_failed)
            } catch (e: Exception) {
                // 只留痕异常类型，不落异常消息（防御性，避免潜在敏感内容回流日志缓冲）
                debugLogBuffer.warn(TAG, "调试日志导出失败: ${e.javaClass.simpleName}")
                debugExportFeedbackFlow.value = UiMessage(R.string.debug_export_failed)
            }
        }
    }

    fun clearDebugExportFeedback() {
        debugExportFeedbackFlow.value = null
    }

    // ========== TASK-13 整改：密码库设置页导出/模板动作真实化 ==========

    private val exportFeedbackFlow = MutableStateFlow<UiMessage?>(null)

    /** 导出/模板动作结果反馈（成功/失败），由 Screen 层消费后清除 */
    val exportFeedback: StateFlow<UiMessage?> = exportFeedbackFlow.asStateFlow()

    fun clearExportFeedback() {
        exportFeedbackFlow.value = null
    }

    /**
     * 导出当前数据库为 KDBX 完整副本（DatabaseSession.exportToBytes 产出的加密 KDBX）
     * 并写入 SAF 目标 Uri——这是 ISSUE-P2-10 明示的默认导出路径。
     */
    fun exportKdbxTo(targetUri: Uri) {
        scope.launch(Dispatchers.IO) {
            exportFeedbackFlow.value = exportAndWrite(
                targetUri, ExportArtifactKind.ENCRYPTED_KDBX, R.string.dbset_export_kdbx_done
            ) { vaultRepository.exportKdbxBytes() }
        }
    }

    /**
     * 导出当前数据库为 KeePass 2.x 兼容明文 XML 并写入 SAF 目标 Uri。
     *
     * ISSUE-P2-10 (ZT-15) / ISSUE-P3-110：明文路径属「高级选项」，**必须**携带
     * [ExportTicket]——该令牌只能由 [ExportConfirmationPolicy.confirm] 在用户显式二次确认后签发，
     * 故此签名本身即「未确认不可调用」的编译期闸门；令牌与制品类型不匹配时 fail-closed 拒绝。
     */
    fun exportVaultXmlTo(targetUri: Uri, ticket: ExportTicket) {
        exportPlaintextTo(targetUri, ExportArtifactKind.PLAINTEXT_XML, ticket, R.string.dbset_export_xml_done) {
            vaultRepository.exportVaultXmlBytes()
        }
    }

    /**
     * ISSUE-P3-73：导出当前数据库为通用明文 CSV 并写入 SAF 目标 Uri。
     *
     * 与明文 XML 同属「高级选项」：令牌要求见 [exportVaultXmlTo]（ISSUE-P3-110）。
     */
    fun exportVaultCsvTo(targetUri: Uri, ticket: ExportTicket) {
        exportPlaintextTo(targetUri, ExportArtifactKind.PLAINTEXT_CSV, ticket, R.string.dbset_export_csv_done) {
            vaultRepository.exportVaultCsvBytes()
        }
    }

    /**
     * 明文制品导出的统一入口（ISSUE-P3-110）：**先校验令牌，再序列化**。
     *
     * 校验失败（令牌缺失或与制品不匹配，例如拿免确认的加密导出令牌套明文导出）时
     * **不调用** `bytesProvider`——即不产生任何明文字节，并按失败路径写审计 + 清理空目标文档。
     */
    private fun exportPlaintextTo(
        targetUri: Uri,
        artifactKind: ExportArtifactKind,
        ticket: ExportTicket,
        successMessageRes: Int,
        bytesProvider: suspend () -> com.keepasskey.core.result.KdbxResult<ByteArray>
    ) {
        scope.launch(Dispatchers.IO) {
            if (!ExportConfirmationPolicy.ticketMatches(ticket, artifactKind)) {
                exportFeedbackFlow.value = rejectTicket(artifactKind, targetUri)
                return@launch
            }
            exportFeedbackFlow.value = exportAndWrite(targetUri, artifactKind, successMessageRes, bytesProvider)
        }
    }

    /** 令牌校验失败收尾：失败审计 + 清理 SAF 已创建的空目标文档 + 面向用户的失败提示 */
    private fun rejectTicket(artifactKind: ExportArtifactKind, targetUri: Uri): UiMessage {
        exportAuditRecorder.record(artifactKind, targetUri.toString(), success = false)
        SafDocumentCleanup.deleteCreatedDocument(appContext, targetUri)
        return UiMessage(R.string.settings_action_failed, listOf(strings.get(R.string.export_confirmation_missing)))
    }

    /**
     * 导出会话绑定的密钥文件并写入 SAF 目标 Uri（ISSUE-P3-128）。
     *
     * `riskOf(KEY_FILE)` 早已把密钥文件归入 `PLAINTEXT` 风险等级（泄漏即可配合密文开库），
     * 故本入口与明文 XML / CSV 同口径：**必须**携带 [ExportConfirmationPolicy.confirm] 签发的令牌，
     * 未确认不可调用、令牌类型不符一律 fail-closed。
     */
    fun exportKeyFileTo(targetUri: Uri, ticket: ExportTicket) {
        exportPlaintextTo(targetUri, ExportArtifactKind.KEY_FILE, ticket, R.string.dbset_keyfile_exported) {
            vaultRepository.exportKeyFileBytes()
        }
    }

    /** 安装条目模板库（真实创建「模板」分组与 5 个模板条目） */
    fun installEntryTemplates() {
        scope.launch {
            val result = vaultRepository.installEntryTemplates()
            exportFeedbackFlow.value = if (result.isSuccess) {
                UiMessage(R.string.dbset_templates_installed)
            } else {
                UiMessage(
                    R.string.settings_action_failed,
                    listOf((result as com.keepasskey.core.result.KdbxResult.Failure).message)
                )
            }
        }
    }

    /** 序列化 → SAF 写盘的公共管线；任一环节失败都映射为可理解的失败反馈，并写脱敏审计条目 */
    private suspend fun exportAndWrite(
        targetUri: Uri,
        artifactKind: ExportArtifactKind,
        successMessageRes: Int,
        bytesProvider: suspend () -> com.keepasskey.core.result.KdbxResult<ByteArray>
    ): UiMessage {
        val rawTarget = targetUri.toString()
        val result = bytesProvider()
        if (!result.isSuccess) {
            val failure = result as com.keepasskey.core.result.KdbxResult.Failure
            exportAuditRecorder.record(artifactKind, rawTarget, success = false)
            // ISSUE-P2-20：序列化已失败，SAF 目标必然仍是空文档——清理不留 0 字节残留
            SafDocumentCleanup.deleteCreatedDocument(appContext, targetUri)
            return UiMessage(R.string.settings_action_failed, listOf(failure.message))
        }
        val bytes = result.getOrNull()
        val resolver = appContext?.contentResolver
        // ISSUE-P3-86（审计 F-02，MEDIUM）：整库序列化缓冲用毕必须清零——明文 XML / CSV 尤甚
        // （该数组是**整库全部字段值**的明文副本）。清零置于 finally，覆盖「写盘成功 / 写盘失败 /
        // 解析器抛异常」三态，且**晚于** `os.write(bytes)`（写前清零会导出全零内容）。
        val written = try {
            if (bytes != null && resolver != null) {
                try {
                    resolver.openOutputStream(targetUri)?.use { os ->
                        os.write(bytes)
                        os.flush()
                        true
                    } ?: false
                } catch (e: Exception) {
                    debugLogBuffer.warn(TAG, "SAF 导出写盘失败: ${e.javaClass.simpleName}")
                    false
                }
            } else {
                false
            }
        } finally {
            bytes?.fill(0)
        }
        // ISSUE-P2-10 (ZT-15)：审计留痕——只记时间（缓冲统一加戳）、导出类型与目标脱敏标识
        exportAuditRecorder.record(artifactKind, rawTarget, success = written)
        return if (written) {
            UiMessage(successMessageRes)
        } else {
            // ISSUE-P2-20：写盘失败（含会话熔断/流不可得/异常），清理空或残缺目标文档，
            // 不向用户目录静默遗留 0 字节产物
            SafDocumentCleanup.deleteCreatedDocument(appContext, targetUri)
            UiMessage(R.string.settings_action_failed, listOf(strings.get(R.string.export_saf_write_failed)))
        }
    }
}

/**
 * ISSUE-P2-10 (ZT-15)：导出制品类型（审计分类与风险分级共用）。
 *
 * ISSUE-P3-110：随 [ExportTicket] 一并公开——确认令牌需作为 Compose 回调的参数类型
 * （公开 composable 不得暴露 module-internal 类型），而令牌的**不可伪造性**由
 * 「唯一实现类文件私有」承担，与本枚举的可见性无关。
 */
enum class ExportArtifactKind(val auditLabel: String) {
    /** 加密 KDBX：默认导出路径，受主密码保护 */
    ENCRYPTED_KDBX("加密 KDBX"),

    /** KeePass 2.x 兼容明文 XML：高级选项，必须先取得显式二次确认 */
    PLAINTEXT_XML("明文 XML"),

    /** ISSUE-P3-73：通用明文 CSV：高级选项，必须先取得显式二次确认 */
    PLAINTEXT_CSV("明文 CSV"),

    /** 会话绑定密钥文件：单独备份，泄漏即可配合密文开库 */
    KEY_FILE("密钥文件"),

    /** 条目附件的解密后明文：必须先取得显式二次确认 */
    ATTACHMENT("明文附件")
}

/**
 * 敏感制品导出的**确认令牌**（ISSUE-P3-110）。
 *
 * 令牌只能由 [ExportConfirmationPolicy.confirm] 签发：其唯一实现类 [IssuedExportTicket]
 * 是本文件的 `private class`（文件外**不可见**，比 `internal` 构造器更强——同模块其它文件
 * 也无法自行构造令牌）。「控制器层要求令牌」于是等价于「调用方必须真的走过确认决策」，
 * 而非「某处约定要记得先问用户」。
 *
 * 令牌**绑定制品类型**：拿「免确认的加密导出令牌」去套明文导出会在控制器侧被拒（fail-closed）。
 */
sealed interface ExportTicket {

    /** 本令牌授权的导出制品类型 */
    val artifactKind: ExportArtifactKind
}

/** [ExportTicket] 的唯一实现（文件私有 ⇒ 只能经 [ExportConfirmationPolicy.confirm] 获得） */
private class IssuedExportTicket(
    override val artifactKind: ExportArtifactKind
) : ExportTicket

/**
 * ISSUE-P2-10 (ZT-15)：明文导出二次确认决策内核（纯 Kotlin，可 JVM 单测）。
 *
 * 加密 KDBX 属默认安全路径，无需确认；明文 XML / 明文附件必须先取得显式确认，
 * 缺失确认一律 fail-closed（不导出）。
 *
 * ISSUE-P3-110：确认结果不再只是布尔判断，而是**签发 [ExportTicket]**——
 * 令牌下沉到导出控制器层，`SettingsExportController` 的明文导出入口以「必须传令牌」表达该约束。
 */
internal object ExportConfirmationPolicy {

    /** 导出风险分级 */
    enum class Risk {
        /** 加密制品：受主密码保护，可直接导出 */
        ENCRYPTED,

        /** 明文制品：出域前必须显式授权 */
        PLAINTEXT
    }

    /** 决策结果 */
    enum class Decision {
        /** 放行导出 */
        PROCEED,

        /** 缺少显式确认：fail-closed，不得写出任何字节 */
        REQUIRE_CONFIRMATION
    }

    /**
     * 依据风险等级与用户确认状态给出决策：
     * 加密导出恒放行；明文导出仅在 [confirmed] 为 true 时放行。
     */
    fun decide(risk: Risk, confirmed: Boolean): Decision =
        if (risk == Risk.PLAINTEXT && !confirmed) {
            Decision.REQUIRE_CONFIRMATION
        } else {
            Decision.PROCEED
        }

    /** 是否允许导出（[decide] 的布尔便捷形式） */
    fun allows(risk: Risk, confirmed: Boolean): Boolean =
        decide(risk, confirmed) == Decision.PROCEED

    /** 由制品类型推导风险等级：仅加密 KDBX 免确认，其余均为明文/敏感制品 */
    fun riskOf(kind: ExportArtifactKind): Risk = when (kind) {
        ExportArtifactKind.ENCRYPTED_KDBX -> Risk.ENCRYPTED
        ExportArtifactKind.PLAINTEXT_XML,
        ExportArtifactKind.PLAINTEXT_CSV,
        ExportArtifactKind.KEY_FILE,
        ExportArtifactKind.ATTACHMENT -> Risk.PLAINTEXT
    }

    /**
     * 按制品类型签发确认令牌（ISSUE-P3-110）。
     *
     * @param kind 本次要导出的制品类型
     * @param userConfirmed 用户是否已完成显式二次确认（明文制品必填 true）
     * @return 放行时返回绑定 [kind] 的令牌；未确认且属明文制品时返回 **null**（fail-closed）
     */
    fun confirm(kind: ExportArtifactKind, userConfirmed: Boolean): ExportTicket? =
        if (allows(riskOf(kind), userConfirmed)) IssuedExportTicket(kind) else null

    /**
     * 控制器侧令牌校验（ISSUE-P3-110）：令牌须存在且与本次导出制品**类型一致**。
     *
     * 缺失或类型不符即拒绝——后者封堵「用免确认制品（加密 KDBX）的令牌套明文导出」的提权路径。
     */
    fun ticketMatches(ticket: ExportTicket?, kind: ExportArtifactKind): Boolean =
        ticket?.artifactKind == kind
}

/**
 * ISSUE-P2-10 (ZT-15)：导出目标脱敏（纯 Kotlin，可 JVM 单测）。
 *
 * 审计条目严禁出现完整路径、文件名或任何明文内容：仅保留 scheme 与 authority
 * （存储提供方标识，便于判断落点类型），其余部分折叠为定长短摘要，
 * 既可用于同一目的事件关联，又不泄露用户目录/文件名。
 *
 * **短摘要的有效熵（ISSUE-P3-89 修正后）**：取 SHA-256 的**前 4 字节**全 8 bit，
 * 合计 **32 bit**（8 个 hex 字符）。修正前实现把每字节先截成低 4 bit，使 8 个字符中
 * 4 个恒为 `'0'`、有效熵仅 ≤16 bit——用途不变（关联同一目标、区分不同目标），
 * **非**抗碰撞用途，故不扩为完整 64 hex（审计缓冲每行长度受限）。
 */
internal object ExportAuditSanitizer {

    private const val DIGEST_HEX_LENGTH = 8
    private const val UNKNOWN_TARGET = "<unknown>"
    private const val SCHEME_SEPARATOR = "://"
    private const val MARKER_SEPARATOR = "#"
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F

    /** 无符号字节掩码：取字节的**全部 8 bit**（ISSUE-P3-89，替代原先的低 4 bit 截断） */
    private const val BYTE_MASK = 0xFF

    /** 生成形如 content://provider.authority#1a2b3c4d 的脱敏标识 */
    fun targetMarker(rawTarget: String): String {
        val trimmed = rawTarget.trim()
        if (trimmed.isEmpty()) return UNKNOWN_TARGET
        val scheme = trimmed.substringBefore(SCHEME_SEPARATOR, "")
        val authority = trimmed.substringAfter(SCHEME_SEPARATOR, "")
            .substringBefore('/')
            .substringBefore('?')
        val prefix = if (scheme.isEmpty()) "" else "$scheme$SCHEME_SEPARATOR"
        return "$prefix$authority$MARKER_SEPARATOR${shortDigest(trimmed)}"
    }

    private fun shortDigest(raw: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(DIGEST_HEX_LENGTH)
        for (byte in bytes) {
            // ISSUE-P3-89（审计 F-07）：每字节须贡献 **8 bit**（高/低半字节各 4 bit）。
            // 原实现先 `and NIBBLE_MASK` 再 `ushr NIBBLE_BITS` ⇒ 高半字节恒为 0，
            // 8 个 hex 字符中有 4 个恒为 '0'，有效熵被削到 ≤16 bit，与本方法的
            // 「同一目标稳定、不同目标可区分」用途不符（第四轮更正：原记 32 bit 系算错）。
            val value = byte.toInt() and BYTE_MASK
            builder.append(HEX_DIGITS[value ushr NIBBLE_BITS]).append(HEX_DIGITS[value and NIBBLE_MASK])
            if (builder.length >= DIGEST_HEX_LENGTH) break
        }
        return builder.toString()
    }
}

/**
 * ISSUE-P2-10 (ZT-15)：导出审计落痕（复用 [DebugLogBuffer]，遵循日志脱敏铁律）。
 *
 * 仅记录时间（由缓冲统一加时间戳）、导出类型与目标脱敏标识；绝不记录文件名、路径、
 * 字段值或任何明文内容。导出成功与失败都会留痕，便于事后回溯数据出域行为。
 *
 * ISSUE-P3-03 (43f)：改用 [DebugLogBuffer.audit] 审计通道——审计留痕是治理要求，
 * 不能被用户侧「诊断日志」开关静默关闭（普通诊断事件才受该开关约束）。
 */
internal class ExportAuditRecorder(private val debugLog: DebugLogBuffer) {

    fun record(kind: ExportArtifactKind, rawTarget: String, success: Boolean) {
        val resultLabel = if (success) RESULT_SUCCESS else RESULT_FAILURE
        val marker = ExportAuditSanitizer.targetMarker(rawTarget)
        debugLog.audit(AUDIT_TAG, "导出审计: 类型=${kind.auditLabel}, 目标=$marker, 结果=$resultLabel")
    }

    private companion object {
        const val AUDIT_TAG = "ExportAudit"
        const val RESULT_SUCCESS = "成功"
        const val RESULT_FAILURE = "失败"
    }
}
