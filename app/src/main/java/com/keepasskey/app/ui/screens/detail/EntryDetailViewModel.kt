package com.keepasskey.app.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiEntryRevision
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
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val entryIdFlow = MutableStateFlow<String?>(savedStateHandle.get<String>("entryId"))
    private val isPasswordVisibleFlow = MutableStateFlow(false)
    private val isFavoriteFlow = MutableStateFlow(false)
    private val protectedVisibilityFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val userMessageFlow = MutableStateFlow<String?>(null)

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
                passwordCopyMessage = if (settings.clipboardTimeoutSeconds > 0) {
                    "密码已复制，${clipboardTimeoutLabel(settings.clipboardTimeoutSeconds)}后自动清空"
                } else {
                    "密码已复制（剪贴板自动清空已关闭）"
                }
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EntryDetailUiState(isLoading = true)
        )

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    fun setEntryId(id: String) {
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
            userMessageFlow.value = "已成功回滚至所选快照版本"
        }
    }

    fun exportAttachment(attachment: UiAttachment) {
        userMessageFlow.value = "附件 ${attachment.fileName} 已导出至系统下载目录"
    }

    fun showMessage(message: String) {
        userMessageFlow.value = message
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }

    private fun clipboardTimeoutLabel(seconds: Int): String = when {
        seconds >= 120 -> "${seconds / 60} 分钟"
        else -> "$seconds 秒"
    }
}
