package com.keepasskey.app.ui.screens.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.keepasskey.app.R
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
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
                // M1 整改：密码明文不随条目投影下发，编辑时按需单条解密
                val password = vaultRepository.getEntryPassword(entry.id).orEmpty()
                // F2 整改：受保护自定义字段同理按需单条解密——明文仅驻留当前编辑条目的状态中，
                // 保存时随 customFields 显式提交（仓库层对空值受保护字段回填既有值作兜底）
                val editableFields = entry.customFields.map { cf ->
                    if (cf.isProtected) {
                        cf.copy(value = vaultRepository.getEntryProtectedField(entry.id, cf.key).orEmpty())
                    } else {
                        cf
                    }
                }
                _uiState.update {
                    it.copy(
                        entryId = entry.id,
                        groupId = entry.groupId,
                        iconName = entry.iconName,
                        title = entry.title,
                        username = entry.username,
                        password = password,
                        url = entry.url,
                        notes = entry.notes,
                        isPasskey = entry.isPasskey,
                        customFields = editableFields,
                        attachments = entry.attachments,
                        isDirty = false
                    )
                }
            }
        }
    }

    fun onIconChange(icon: String) = _uiState.update { it.copy(iconName = icon, isDirty = true) }
    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title, isDirty = true) }
    fun onUsernameChange(username: String) = _uiState.update { it.copy(username = username, isDirty = true) }
    fun onPasswordChange(password: String) = _uiState.update { it.copy(password = password, isDirty = true) }
    fun onUrlChange(url: String) = _uiState.update { it.copy(url = url, isDirty = true) }
    fun onNotesChange(notes: String) = _uiState.update { it.copy(notes = notes, isDirty = true) }

    fun onTogglePasskey() = _uiState.update {
        it.copy(
            isPasskey = !it.isPasskey,
            isDirty = true
        )
    }

    fun onTotpSecretChange(secret: String) = _uiState.update { it.copy(totpSecret = secret, isDirty = true) }

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

        _uiState.update { it.copy(password = newPassword, isDirty = true) }
    }

    fun onGroupChange(groupId: String?) = _uiState.update { it.copy(groupId = groupId, isDirty = true) }

    fun addCustomField() {
        val newField = UiCustomField(
            id = "field_${System.currentTimeMillis()}",
            key = "",
            value = "",
            isProtected = false
        )
        _uiState.update { it.copy(customFields = it.customFields + newField, isDirty = true) }
    }

    fun updateCustomField(id: String, key: String, value: String, isProtected: Boolean) {
        _uiState.update { state ->
            val updated = state.customFields.map { f ->
                if (f.id == id) f.copy(key = key, value = value, isProtected = isProtected) else f
            }
            state.copy(customFields = updated, isDirty = true)
        }
    }

    fun removeCustomField(id: String) {
        _uiState.update { state ->
            state.copy(customFields = state.customFields.filter { it.id != id }, isDirty = true)
        }
    }

    fun addAttachment(fileName: String, fileSizeFormatted: String) {
        val newAtt = UiAttachment(
            id = "att_${System.currentTimeMillis()}",
            fileName = fileName,
            fileSizeFormatted = fileSizeFormatted,
            mimeType = "application/octet-stream",
            addedAt = "刚刚"
        )
        _uiState.update { it.copy(attachments = it.attachments + newAtt, isDirty = true) }
    }

    fun removeAttachment(id: String) {
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filter { it.id != id }, isDirty = true)
        }
    }

    fun saveEntry() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.update { it.copy(userMessage = UiMessage(R.string.edit_title_required)) }
            return
        }

        viewModelScope.launch {
            val entryId = state.entryId ?: UUID.randomUUID().toString()
            val entry = UiVaultEntry(
                id = entryId,
                title = state.title.trim(),
                username = state.username.trim(),
                url = state.url.trim(),
                notes = state.notes.trim(),
                isPasskey = state.isPasskey,
                category = if (state.isPasskey) EntryCategory.PASSKEY else EntryCategory.LOGIN,
                updatedAt = "刚刚",
                groupId = state.groupId,
                iconName = state.iconName,
                customFields = state.customFields.filter { it.key.isNotBlank() },
                attachments = state.attachments
            )
            // M1 整改：密码以独立参数显式提交，不再随条目投影携带
            vaultRepository.saveEntry(entry, passwordChars = state.password.toCharArray())
            _events.emit(EntryEditEvent.SaveSuccess)
        }
    }

    fun showMessage(msg: UiMessage) {
        _uiState.update { it.copy(userMessage = msg) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
