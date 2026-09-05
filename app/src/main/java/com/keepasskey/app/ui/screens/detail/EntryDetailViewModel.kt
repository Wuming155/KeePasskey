package com.keepasskey.app.ui.screens.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private val clipboardSecurityManager: com.keepasskey.app.security.ClipboardSecurityManager? = null
) : ViewModel() {

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

    /** combine 中间聚合体（避开 5 流以上的元组嵌套） */
    private data class DetailCore(
        val entry: UiVaultEntry?,
        val isPasswordVisible: Boolean,
        val revealedPassword: String?,
        val revisionPasswords: Map<String, String>,
        val isFavorite: Boolean
    )

    /** combine 中间聚合体：可见性 / 已揭示字段明文 / 用户消息 / TOTP 实时态 */
    private data class DetailExtras(
        val protectedVisibility: Map<String, Boolean>,
        val revealedProtectedFields: Map<String, String>,
        val userMessage: UiMessage?,
        val totpRemainingSeconds: Int? = null,
        val liveTotpCode: String? = null
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
                isReadOnly = vaultRepository.isSessionReadOnly(),
                passwordCopyMessage = buildPasswordCopyMessage(settings.clipboardTimeoutSeconds)
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
            while (isActive) {
                delay(TOTP_TICK_MS)
                val snapshot = uiState.value.entry?.takeIf { it.totpCode != null }
                if (snapshot != null) {
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
            val value = vaultRepository.getEntryProtectedField(entry.id, field.key).orEmpty()
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
            val value = vaultRepository.getEntryProtectedField(entryId, fieldKey)
            if (value != null) {
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
                userMessageFlow.value = UiMessage(R.string.detail_history_rolled_back)
                return@launch
            }
            val revisionPassword = vaultRepository.getEntryRevisionPassword(entryId, revision.id)
            val updated = snapshot.entry.copy(
                groupId = current.groupId,
                updatedAt = "刚刚 (从历史版本回滚)"
            )
            val result = vaultRepository.saveEntry(
                updated,
                passwordChars = revisionPassword?.toCharArray(),
                totpSecret = snapshot.totpSecret
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
     */
    fun exportAttachment(attachment: UiAttachment, targetUri: Uri) {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = vaultRepository.getAttachmentData(entryId, attachment.fileName)
                if (bytes == null) {
                    userMessageFlow.value = UiMessage(R.string.detail_attachment_export_failed)
                    return@launch
                }
                val resolver = appContext?.contentResolver ?: run {
                    userMessageFlow.value = UiMessage(R.string.detail_attachment_export_failed)
                    return@launch
                }
                resolver.openOutputStream(targetUri)?.use { os ->
                    os.write(bytes)
                    os.flush()
                } ?: run {
                    userMessageFlow.value = UiMessage(R.string.detail_attachment_export_failed)
                    return@launch
                }
                userMessageFlow.value = UiMessage(R.string.detail_attachment_export_toast, listOf(attachment.fileName))
            } catch (e: Exception) {
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
            val password = vaultRepository.getEntryPassword(entryId).orEmpty()
            clipboardSecurityManager?.copySensitiveText(title, password)
            userMessageFlow.value = uiState.value.passwordCopyMessage
        }
    }

    fun copyUsername(title: String, username: String) {
        clipboardSecurityManager?.copyPlainText(title, username)
        userMessageFlow.value = UiMessage(R.string.detail_username_copied_short)
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
    }
}
