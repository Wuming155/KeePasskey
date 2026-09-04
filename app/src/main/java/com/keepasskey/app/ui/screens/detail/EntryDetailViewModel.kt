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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 凭据详情状态容器 ViewModel
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
    private val isFavoriteFlow = MutableStateFlow(false)
    private val protectedVisibilityFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<EntryDetailUiState> = entryIdFlow
        .flatMapLatest { id ->
            if (id != null) vaultRepository.getEntry(id) else kotlinx.coroutines.flow.flowOf(null)
        }
        .combine(isPasswordVisibleFlow) { entry, isPassVisible ->
            Pair(entry, isPassVisible)
        }
        .combine(isFavoriteFlow) { (entry, isPassVisible), isFav ->
            Triple(entry, isPassVisible, isFav)
        }
        .combine(protectedVisibilityFlow) { (entry, isPassVisible, isFav), visMap ->
            Tuple4(entry, isPassVisible, isFav, visMap)
        }
        .combine(userMessageFlow) { (entry, isPassVisible, isFav, visMap), message ->
            EntryDetailUiState(
                entry = entry,
                isPasswordVisible = isPassVisible,
                isFavorite = isFav,
                protectedFieldsVisibility = visMap,
                userMessage = message
            )
        }
        .combine(settingsRepository.getSettings()) { state, settings ->
            state.copy(
                passwordCopyMessage = buildPasswordCopyMessage(settings.clipboardTimeoutSeconds)
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EntryDetailUiState(isLoading = true)
        )

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    fun setEntryId(id: String?) {
        entryIdFlow.value = id
    }

    fun togglePasswordVisibility() {
        isPasswordVisibleFlow.update { !it }
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

    fun rollbackToRevision(revision: UiEntryRevision) {
        val current = uiState.value.entry ?: return
        viewModelScope.launch {
            val updated = current.copy(
                username = revision.username,
                passwordPlain = revision.passwordPlain,
                notes = if (revision.notes.isNotBlank()) revision.notes else current.notes,
                updatedAt = "刚刚 (从历史版本回滚)"
            )
            vaultRepository.saveEntry(updated)
            userMessageFlow.value = UiMessage(R.string.detail_history_rolled_back)
        }
    }

    fun exportAttachment(attachment: UiAttachment) {
        userMessageFlow.value = UiMessage(R.string.detail_attachment_export_toast, listOf(attachment.fileName))
    }

    fun showMessage(message: UiMessage) {
        userMessageFlow.value = message
    }

    fun copyPassword(title: String, password: String) {
        clipboardSecurityManager?.copySensitiveText(title, password)
        userMessageFlow.value = uiState.value.passwordCopyMessage
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
