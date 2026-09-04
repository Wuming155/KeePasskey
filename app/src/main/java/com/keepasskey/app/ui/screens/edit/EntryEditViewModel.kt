package com.keepasskey.app.ui.screens.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiVaultEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

/**
 * 编辑页单次事件
 */
sealed interface EntryEditEvent {
    data object SaveSuccess : EntryEditEvent
}

/**
 * 凭据添加与编辑状态容器 ViewModel
 */
@HiltViewModel
class EntryEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EntryEditUiState())
    val uiState: StateFlow<EntryEditUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EntryEditEvent>()
    val events: SharedFlow<EntryEditEvent> = _events.asSharedFlow()

    init {
        val entryId: String? = savedStateHandle["entryId"]
        val groupId: String? = savedStateHandle["groupId"]
        if (entryId != null) {
            loadEntry(entryId)
        } else if (groupId != null) {
            _uiState.update { it.copy(groupId = groupId) }
        }

        viewModelScope.launch {
            vaultRepository.getGroups().collect { groups ->
                _uiState.update { it.copy(availableGroups = groups) }
            }
        }
    }

    fun loadEntry(id: String) {
        viewModelScope.launch {
            val entry = vaultRepository.getEntry(id).firstOrNull()
            if (entry != null) {
                _uiState.update {
                    it.copy(
                        entryId = entry.id,
                        groupId = entry.groupId,
                        title = entry.title,
                        username = entry.username,
                        password = entry.passwordPlain,
                        url = entry.url,
                        notes = entry.notes,
                        isPasskey = entry.isPasskey,
                        selectedCategory = entry.category
                    )
                }
            }
        }
    }

    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title) }
    fun onUsernameChange(username: String) = _uiState.update { it.copy(username = username) }
    fun onPasswordChange(password: String) = _uiState.update { it.copy(password = password) }
    fun onUrlChange(url: String) = _uiState.update { it.copy(url = url) }
    fun onNotesChange(notes: String) = _uiState.update { it.copy(notes = notes) }
    fun onTogglePasskey() = _uiState.update { it.copy(isPasskey = !it.isPasskey) }
    fun onTotpSecretChange(secret: String) = _uiState.update { it.copy(totpSecret = secret) }
    fun onCategoryChange(category: EntryCategory) = _uiState.update { it.copy(selectedCategory = category) }
    fun onTogglePasswordVisibility() = _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    fun onToggleGenerator() = _uiState.update { it.copy(showGenerator = !it.showGenerator) }

    fun onPassLengthChange(length: Float) {
        _uiState.update { it.copy(passLength = length) }
        generatePassword()
    }

    fun onToggleUpper() {
        _uiState.update { it.copy(useUpper = !it.useUpper) }
        generatePassword()
    }

    fun onToggleLower() {
        _uiState.update { it.copy(useLower = !it.useLower) }
        generatePassword()
    }

    fun onToggleDigits() {
        _uiState.update { it.copy(useDigits = !it.useDigits) }
        generatePassword()
    }

    fun onToggleSymbols() {
        _uiState.update { it.copy(useSymbols = !it.useSymbols) }
        generatePassword()
    }

    fun generatePassword() {
        val state = _uiState.value
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digits = "23456789"
        val symbols = "!@#\$%^&*()_+-=[]{}|;:,.<>?"

        var pool = ""
        if (state.useUpper) pool += upper
        if (state.useLower) pool += lower
        if (state.useDigits) pool += digits
        if (state.useSymbols) pool += symbols
        if (pool.isEmpty()) pool = lower

        val newPassword = (1..state.passLength.toInt())
            .map { pool[Random.nextInt(pool.length)] }
            .joinToString("")

        _uiState.update { it.copy(password = newPassword) }
    }

    fun onGroupChange(groupId: String?) = _uiState.update { it.copy(groupId = groupId) }

    fun saveEntry() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.update { it.copy(userMessage = "请输入凭据标题") }
            return
        }

        viewModelScope.launch {
            val entryId = state.entryId ?: UUID.randomUUID().toString()
            val entry = UiVaultEntry(
                id = entryId,
                title = state.title.trim(),
                username = state.username.trim(),
                passwordPlain = state.password,
                url = state.url.trim(),
                notes = state.notes.trim(),
                isPasskey = state.isPasskey,
                category = state.selectedCategory,
                updatedAt = "刚刚",
                groupId = state.groupId
            )
            vaultRepository.saveEntry(entry)
            _events.emit(EntryEditEvent.SaveSuccess)
        }
    }

    fun showMessage(msg: String) {
        _uiState.update { it.copy(userMessage = msg) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
