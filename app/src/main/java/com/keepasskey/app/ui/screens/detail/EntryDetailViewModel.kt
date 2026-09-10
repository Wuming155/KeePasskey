package com.keepasskey.app.ui.screens.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.screens.settings.ExportArtifactKind
import com.keepasskey.app.ui.screens.settings.ExportAuditRecorder
import com.keepasskey.app.ui.screens.settings.ExportConfirmationPolicy
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.util.tickerFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 凭据详情状态容器 ViewModel。
 * M1 整改：密码明文不随条目投影下发，仅在用户显式查看 / 复制 / 对比时经仓库按需单条解密。
 */
@HiltViewModel
class EntryDetailViewModel @Inject constructor(
    // 允许为 null 仅用于单测注入；生产 DI 注入 @ApplicationContext
    @ApplicationContext private val appContext: Context?,
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val clipboardSecurityManager: com.keepasskey.app.security.ClipboardSecurityManager? = null,
    // TASK-44：自动填充黑名单仓库（详情页「为本应用禁用自动填充」入口的写入方）
    private val autofillBlocklistStore: com.keepasskey.app.data.repository.AutofillBlocklistStore,
    // TASK-21：非 Compose 层文案资源解析通道（生产 DI 注入真实现；单测注入假实现）
    private val stringsProvider: StringsProvider? = null,
    // ISSUE-P2-10 (ZT-15)：导出审计记录通道（生产 DI 注入；单测可缺省）
    private val debugLog: DebugLogBuffer? = null
) : ViewModel() {

    // P3-23：文案解析通道（优先 stringsProvider，其次经 appContext 转发，均缺省时回退空串实现）
    private val strings: StringsProvider = stringsProvider
        ?: appContext?.let { ctx -> StringsProvider { id, args -> ctx.getString(id, *args) } }
        ?: StringsProvider { _, _ -> "" }

    // ISSUE-P2-10 (ZT-15)：明文附件导出审计（复用进程内日志缓冲，仅记类型与脱敏目标标识）
    private val exportAuditRecorder: ExportAuditRecorder? = debugLog?.let { ExportAuditRecorder(it) }

    private val entryIdFlow = MutableStateFlow<String?>(savedStateHandle.get<String>("entryId"))
    private val isPasswordVisibleFlow = MutableStateFlow(false)
    // 按需解密出的当前密码明文（仅在查看期间驻留，隐藏即清空）
    private val revealedPasswordFlow = MutableStateFlow<String?>(null)
    // 按需解密出的历史修订密码（对比弹窗打开期间驻留），键为修订 id
    private val revealedRevisionPasswordsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val isFavoriteFlow = MutableStateFlow(false)
    private val protectedVisibilityFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    // F2 整改：受保护自定义字段按需解密出的明文（仅查看期间驻留，收起即清空），键为字段 id
    private val revealedProtectedFieldsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)

    // 断点6 整改：详情页 TOTP 每秒倒计时（原为投影一次性值，进度环静止）
    private val totpRemainingSecondsFlow = MutableStateFlow(0)
    // 断点6 整改：周期翻转时经仓库按需重算的实时验证码（null=沿用投影值）
    private val liveTotpCodeFlow = MutableStateFlow<String?>(null)
    // TASK-32 整改：按需解密估算的真实密码熵（bit）。null=无密码或尚未计算完成；
    // 明文仅在计算期间以 CharArray 副本瞬时存在，用毕立即清零，绝不驻留
    private val passwordStrengthBitsFlow = MutableStateFlow<Int?>(null)

    /** combine 中间聚合体（避开 5 流以上的元组嵌套） */
    private data class DetailCore(
        val entry: UiVaultEntry?,
        val isPasswordVisible: Boolean,
        val revealedPassword: String?,
        val revisionPasswords: Map<String, String>,
        val isFavorite: Boolean
    )

    /** combine 中间聚合体：可见性 / 已揭示字段明文 / 用户消息 / TOTP 实时态 / 密码熵 */
    private data class DetailExtras(
        val protectedVisibility: Map<String, Boolean>,
        val revealedProtectedFields: Map<String, String>,
        val userMessage: UiMessage?,
        val totpRemainingSeconds: Int? = null,
        val liveTotpCode: String? = null,
        val strengthBits: Int? = null
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<EntryDetailUiState> = combine(
        entryIdFlow.flatMapLatest { id ->
            if (id != null) vaultRepository.getEntry(id) else flowOf(null)
        },
        isPasswordVisibleFlow,
        revealedPasswordFlow,
        revealedRevisionPasswordsFlow,
        isFavoriteFlow
    ) { entry, isPassVisible, revealed, revPasswords, isFav ->
        DetailCore(entry, isPassVisible, revealed, revPasswords, isFav)
    }
        .combine(
            combine(
                protectedVisibilityFlow,
                revealedProtectedFieldsFlow,
                userMessageFlow
            ) { visMap, revealedFields, message ->
                DetailExtras(visMap, revealedFields, message)
            }.combine(combine(totpRemainingSecondsFlow, liveTotpCodeFlow) { r, c -> r to c }) { extras, totp ->
                extras.copy(totpRemainingSeconds = totp.first, liveTotpCode = totp.second)
            }.combine(passwordStrengthBitsFlow) { extras, strength ->
                extras.copy(strengthBits = strength)
            }
        ) { core, extras ->
            core to extras
        }
        .combine(settingsRepository.getSettings()) { (core, extras), settings ->
            EntryDetailUiState(
                entry = core.entry,
                isPasswordVisible = core.isPasswordVisible,
                revealedPassword = core.revealedPassword,
                revealedRevisionPasswords = core.revisionPasswords,
                isFavorite = core.isFavorite,
                protectedFieldsVisibility = extras.protectedVisibility,
                revealedProtectedFields = extras.revealedProtectedFields,
                userMessage = extras.userMessage,
                totpRemainingSeconds = extras.totpRemainingSeconds,
                liveTotpCode = extras.liveTotpCode,
                passwordStrengthBits = extras.strengthBits,
                isReadOnly = vaultRepository.isSessionReadOnly(),
                passwordCopyMessage = buildPasswordCopyMessage(settings.clipboardTimeoutSeconds)
            )
        }
        // TASK-44：黑名单状态叠加——条目绑定的应用包名 + 该包名当前是否被屏蔽
        .combine(autofillBlocklistStore.blockedPackages) { state, blockedPackages ->
            val boundPackage = state.entry?.url
                ?.let { DomainMatcher.extractAndroidBoundPackage(it) }
            state.copy(
                autofillBoundPackage = boundPackage,
                isAutofillBlockedForApp = boundPackage != null && blockedPackages.contains(boundPackage)
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EntryDetailUiState(isLoading = true)
        )

    init {
        // 断点6 整改：每秒驱动 TOTP 倒计时；周期翻转（剩余秒数不降反升）时重算实时验证码
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var previous = -1
            tickerFlow(TOTP_TICK_MS).collect {
                val snapshot = uiState.value.entry?.takeIf { it.totpCode != null } ?: return@collect
                val fresh = vaultRepository.calculateEntryTotp(snapshot.id)
                val period = fresh?.periodSeconds ?: snapshot.totpPeriod
                val remaining = if (fresh != null) {
                    val nowSec = (System.currentTimeMillis() / 1000L).toInt()
                    val r = period - (nowSec % period)
                    if (r == 0) period else r
                } else {
                    (snapshot.totpRemainingSeconds - 1).coerceAtLeast(0)
                }
                totpRemainingSecondsFlow.value = remaining
                if (previous in 1..remaining) {
                    // 剩余秒数回跳到满值 → 新周期开始，刷新验证码
                    liveTotpCodeFlow.value = fresh?.code
                } else if (previous == -1 && fresh != null) {
                    liveTotpCodeFlow.value = fresh.code
                }
                previous = remaining
            }
        }
    }

    fun setEntryId(id: String?) {
        // M1 整改：切换条目时立即擦除上一条目按需解密出的全部明文（密码/修订密码/受保护字段），
        // 杜绝「查看 A 条目密码后切到 B，A 的明文仍驻留 ViewModel 状态」的跨条目残留
        if (id == entryIdFlow.value) return
        entryIdFlow.value = id
        clearAllRevealedSecrets()
    }

    /**
     * Screen 离开组合（返回导航 / 目的地销毁）时调用：擦除全部按需解密明文。
     * 对比 KeePassDX「解密即用即弃」语义——明文仅允许在显式查看期间驻留。
     */
    fun onScreenDisposed() {
        clearAllRevealedSecrets()
    }

    private fun clearAllRevealedSecrets() {
        revealedPasswordFlow.value = null
        revealedRevisionPasswordsFlow.value = emptyMap()
        revealedProtectedFieldsFlow.value = emptyMap()
        isPasswordVisibleFlow.value = false
        protectedVisibilityFlow.value = emptyMap()
    }

    /**
     * 切换密码可见性。展开时按需解密当前条目密码，收起时立即置空驻留明文。
     */
    fun togglePasswordVisibility() {
        val becomingVisible = !isPasswordVisibleFlow.value
        isPasswordVisibleFlow.value = becomingVisible
        if (!becomingVisible) {
            revealedPasswordFlow.value = null
            return
        }
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            revealedPasswordFlow.value = vaultRepository.getEntryPassword(entryId)
        }
    }

    fun toggleFavorite() {
        isFavoriteFlow.update { !it }
    }

    /**
     * 克隆当前条目（TASK-16）：全字段保真复制 + 新 UUID + 清历史，落库后
     * 详情页就地切换至克隆体。失败如实上浮（H3 语义）。
     */
    fun duplicateEntry() {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            when (val result = vaultRepository.duplicateEntry(entryId)) {
                is com.keepasskey.core.result.KdbxResult.Success -> {
                    userMessageFlow.value = UiMessage(R.string.detail_duplicate_success)
                    setEntryId(result.data)
                }
                is com.keepasskey.core.result.KdbxResult.Failure ->
                    userMessageFlow.value = UiMessage(R.string.edit_save_failed, listOf(result.message))
            }
        }
    }

    /**
     * TASK-44：切换「为本应用禁用自动填充」——写入/移出自动填充黑名单。
     *
     * 仅当条目 URL 携带 `android://<包名>` 绑定（即凭据确有明确归属应用）时可用；
     * 未绑定应用的条目（如纯 Web 凭据）本入口不呈现，调用亦为 no-op。
     * 结果经 [userMessageFlow] 如实告知用户（屏蔽 / 恢复），不做乐观谎报。
     */
    fun toggleAutofillBlockForApp() {
        val packageName = uiState.value.autofillBoundPackage ?: return
        val nowBlocked = if (autofillBlocklistStore.isBlocked(packageName)) {
            autofillBlocklistStore.remove(packageName)
            false
        } else {
            autofillBlocklistStore.add(packageName)
            true
        }
        userMessageFlow.value = if (nowBlocked) {
            UiMessage(R.string.detail_autofill_blocked, listOf(packageName))
        } else {
            UiMessage(R.string.detail_autofill_unblocked, listOf(packageName))
        }
    }

    /**
     * 切换受保护自定义字段可见性（F2 整改）。
     * 展开时经仓库按需单条解密该字段明文，收起时立即从驻留状态中移除。
     */
    fun toggleCustomFieldVisibility(fieldId: String) {
        val becomingVisible = protectedVisibilityFlow.value[fieldId] != true
        protectedVisibilityFlow.update { current -> current + (fieldId to becomingVisible) }
        if (!becomingVisible) {
            revealedProtectedFieldsFlow.update { it - fieldId }
            return
        }
        val entry = uiState.value.entry ?: return
        val field = entry.customFields.firstOrNull { it.id == fieldId } ?: return
        viewModelScope.launch {
            // TASK-10：仓库读取改走 CharArray 独占副本；展示用 String 为 UI 显示边界
            // （与 getEntryPassword 同一边界语义），副本即时清零
            val chars = vaultRepository.getEntryProtectedFieldChars(entry.id, field.key)
            val value = if (chars != null) {
                val revealed = String(chars)
                chars.fill('0')
                revealed
            } else ""
            revealedProtectedFieldsFlow.update { it + (fieldId to value) }
        }
    }

    /**
     * 复制受保护自定义字段（F2 整改）：按需解密后写入受保护剪贴板，
     * 不再依赖条目投影中的明文（投影层受保护字段恒为空）。
     */
    fun copyCustomField(fieldId: String, fieldKey: String) {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            // TASK-10：仓库读取改走 CharArray 独占副本；剪贴板写入是 String 边界，
            // 副本即时清零
            val chars = vaultRepository.getEntryProtectedFieldChars(entryId, fieldKey)
            if (chars != null) {
                val value = String(chars)
                chars.fill('0')
                clipboardSecurityManager?.copySensitiveText(fieldKey, value)
                userMessageFlow.value = UiMessage(R.string.detail_field_copied, listOf(fieldKey))
            }
        }
    }

    /**
     * 回滚到历史修订（断点8 整改）。
     * 原实现仅恢复 username/notes/password 三字段，title/url/自定义字段/TOTP 全部丢失；
     * 现改为取整修订快照（含解密后的受保护字段与 TOTP 配置）全字段回滚，
     * 保存时由 HistoryManager 把回滚前的当前版本归档为最新历史。
     */
    fun rollbackToRevision(revision: UiEntryRevision) {
        val current = uiState.value.entry ?: return
        val entryId = current.id
        viewModelScope.launch {
            val snapshot = vaultRepository.getEntryRevisionSnapshot(entryId, revision.id)
            if (snapshot == null) {
                // TASK-31 整改：历史快照缺失（已被修剪/清理）时不得谎报「已回滚」，如实暴露失败
                userMessageFlow.value = UiMessage(R.string.detail_history_rollback_failed)
                return@launch
            }
            // M2 整改（加解密审查 2026-09）：回滚路径全程 CharArray，不再经 String 中转；
            // 副本按 saveEntry 擦除契约由仓库用毕清零
            val revisionPasswordChars = vaultRepository.getEntryRevisionPasswordChars(entryId, revision.id)
            val updated = snapshot.entry.copy(
                groupId = current.groupId,
                updatedAt = strings.get(R.string.detail_rollback_updated_at)
            )
            val result = vaultRepository.saveEntry(
                updated,
                passwordChars = revisionPasswordChars,
                totpSecretChars = snapshot.totpSecret.toCharArray()
            )
            userMessageFlow.value = if (result is com.keepasskey.core.result.KdbxResult.Success) {
                UiMessage(R.string.detail_history_rolled_back)
            } else {
                UiMessage(R.string.edit_save_failed, listOf((result as com.keepasskey.core.result.KdbxResult.Failure).message))
            }
        }
    }

    /**
     * 打开历史修订对比弹窗前按需解密：当前密码 + 目标修订密码。
     */
    fun prepareRevisionDiff(revisionId: String) {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            val currentPw = revealedPasswordFlow.value ?: vaultRepository.getEntryPassword(entryId)
            val revisionPw = vaultRepository.getEntryRevisionPassword(entryId, revisionId)
            revealedPasswordFlow.value = currentPw
            revealedRevisionPasswordsFlow.update { it + (revisionId to revisionPw.orEmpty()) }
        }
    }

    fun clearRevisionDiff() {
        // M1 整改：关闭对比弹窗时必须连修订密码映射一并清空
        // （原实现只清当前密码，revealedRevisionPasswords 跨弹窗累积驻留明文）
        revealedPasswordFlow.value = null
        revealedRevisionPasswordsFlow.value = emptyMap()
    }

    /**
     * 断点3 整改：真实附件导出——按需解析附件字节并写入 SAF 目标 Uri。
     * [targetUri] 由 Screen 层 CreateDocument 选择器产生；此前该方法仅发 Toast。
     *
     * ISSUE-P2-10 (ZT-15)：附件是解密后的明文，属高风险出域。调用方必须先经确认弹窗
     * 取得用户显式授权并传 [confirmed] = true；缺省或缺失确认时 fail-closed——不解析、
     * 不写出任何字节，仅提示用户（判定走可单测的 [ExportConfirmationPolicy]）。
     */
    fun exportAttachment(attachment: UiAttachment, targetUri: Uri, confirmed: Boolean = false) {
        val entryId = entryIdFlow.value ?: return
        val allowed = ExportConfirmationPolicy.allows(
            risk = ExportConfirmationPolicy.riskOf(ExportArtifactKind.ATTACHMENT),
            confirmed = confirmed
        )
        if (!allowed) {
            // fail-closed：确认缺失即不导出
            userMessageFlow.value = UiMessage(R.string.detail_attachment_export_warn_title)
            return
        }
        val rawTarget = targetUri.toString()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = vaultRepository.getAttachmentData(entryId, attachment.fileName)
                if (bytes == null) {
                    exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = false)
                    userMessageFlow.value = UiMessage(R.string.detail_attachment_export_failed)
                    return@launch
                }
                val resolver = appContext?.contentResolver
                if (resolver == null) {
                    exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = false)
                    userMessageFlow.value = UiMessage(R.string.detail_attachment_export_failed)
                    return@launch
                }
                val stream = resolver.openOutputStream(targetUri)
                if (stream == null) {
                    exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = false)
                    userMessageFlow.value = UiMessage(R.string.detail_attachment_export_failed)
                    return@launch
                }
                stream.use { os ->
                    os.write(bytes)
                    os.flush()
                }
                exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = true)
                userMessageFlow.value = UiMessage(R.string.detail_attachment_export_toast, listOf(attachment.fileName))
            } catch (e: Exception) {
                // 只留痕异常类型，不落异常消息或附件名（防御性，避免敏感内容回流日志缓冲）
                exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = false)
                debugLog?.warn(TAG, "附件导出失败: ${e.javaClass.simpleName}")
                userMessageFlow.value = UiMessage(R.string.detail_attachment_export_failed)
            }
        }
    }

    fun showMessage(message: UiMessage) {
        userMessageFlow.value = message
    }

    /**
     * 复制密码：按需解密后写入受保护剪贴板（M1 整改：不再从条目投影取明文）。
     */
    fun copyPassword(title: String) {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            // TASK-17：复制前解析 {REF:...} 引用（密码可能指向其他条目的字段）
            val raw = vaultRepository.getEntryPassword(entryId).orEmpty()
            val password = vaultRepository.resolveFieldReferences(entryId, raw) ?: raw
            clipboardSecurityManager?.copySensitiveText(title, password)
            userMessageFlow.value = uiState.value.passwordCopyMessage
        }
    }

    fun copyUsername(title: String, username: String) {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            // TASK-17：用户名可能为 {REF:U@...} 引用，复制前解析
            val resolved = vaultRepository.resolveFieldReferences(entryId, username) ?: username
            clipboardSecurityManager?.copyPlainText(title, resolved)
            userMessageFlow.value = UiMessage(R.string.detail_username_copied_short)
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }

    /**
     * 依据剪贴板自动清空时长生成对应的资源化提示消息
     */
    private fun buildPasswordCopyMessage(timeoutSeconds: Int): UiMessage = when {
        timeoutSeconds >= SECONDS_PER_MINUTE * 2 ->
            UiMessage(R.string.detail_password_copied_timeout_minutes, listOf(timeoutSeconds / SECONDS_PER_MINUTE))
        timeoutSeconds > 0 ->
            UiMessage(R.string.detail_password_copied_timeout_seconds, listOf(timeoutSeconds))
        else ->
            UiMessage(R.string.detail_password_copied_no_clear)
    }

    companion object {
        private const val SECONDS_PER_MINUTE = 60
        private const val TOTP_TICK_MS = 1000L
        private const val TAG = "EntryDetailViewModel"
    }
}
