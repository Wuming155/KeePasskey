package com.keepasskey.app.ui.screens.detail

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
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 凭据详情状态容器 ViewModel。
 * M1 整改：密码明文不随条目投影下发，仅在用户显式查看 / 复制 / 对比时经仓库按需单条解密。
 */
@HiltViewModel
class EntryDetailViewModel @Inject constructor(
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
    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)

    /** combine 中间聚合体（避开 5 流以上的元组嵌套） */
    private data class DetailCore(
        val entry: UiVaultEntry?,
        val isPasswordVisible: Boolean,
        val revealedPassword: String?,
        val revisionPasswords: Map<String, String>,
        val isFavorite: Boolean
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
        .combine(protectedVisibilityFlow) { core, visMap ->
            core to visMap
        }
        .combine(userMessageFlow) { (core, visMap), message ->
            Triple(core, visMap, message)
        }
        .combine(settingsRepository.getSettings()) { (core, visMap, message), settings ->
            EntryDetailUiState(
                entry = core.entry,
                isPasswordVisible = core.isPasswordVisible,
                revealedPassword = core.revealedPassword,
                revealedRevisionPasswords = core.revisionPasswords,
                isFavorite = core.isFavorite,
                protectedFieldsVisibility = visMap,
                userMessage = message,
                passwordCopyMessage = buildPasswordCopyMessage(settings.clipboardTimeoutSeconds)
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EntryDetailUiState(isLoading = true)
        )

    fun setEntryId(id: String?) {
        entryIdFlow.value = id
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

    fun toggleCustomFieldVisibility(fieldId: String) {
        protectedVisibilityFlow.update { current ->
            val currentVal = current[fieldId] ?: false
            current + (fieldId to !currentVal)
        }
    }

    /**
     * 回滚到历史修订。密码从修订快照按需解密后随保存显式提交。
     */
    fun rollbackToRevision(revision: UiEntryRevision) {
        val current = uiState.value.entry ?: return
        val entryId = current.id
        viewModelScope.launch {
            val revisionPassword = vaultRepository.getEntryRevisionPassword(entryId, revision.id)
            val updated = current.copy(
                username = revision.username,
                notes = if (revision.notes.isNotBlank()) revision.notes else current.notes,
                updatedAt = "刚刚 (从历史版本回滚)"
            )
            vaultRepository.saveEntry(
                updated,
                passwordChars = revisionPassword?.toCharArray()
            )
            userMessageFlow.value = UiMessage(R.string.detail_history_rolled_back)
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
        revealedPasswordFlow.value = null
    }

    fun exportAttachment(attachment: UiAttachment) {
        userMessageFlow.value = UiMessage(R.string.detail_attachment_export_toast, listOf(attachment.fileName))
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
    }
}
