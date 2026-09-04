package com.keepasskey.app.ui.screens.conflict

import androidx.lifecycle.ViewModel
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConflictResolutionEvent {
    data object ResolveSuccess : ConflictResolutionEvent
}

@HiltViewModel
class ConflictResolutionViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(
        ConflictResolutionUiState(
            entries = listOf(
                ConflictedEntryItem(
                    id = "entry_conflict_1",
                    title = "GitHub Pro Account",
                    groupPath = "根目录 / 工作开发",
                    fields = listOf(
                        ConflictedField(
                            fieldName = "密码 (Password)",
                            localValue = "ghp_secureToken2026!#",
                            remoteValue = "ghp_oldToken2025_abc",
                            selectedChoice = FieldChoice.LOCAL,
                            isSensitive = true
                        ),
                        ConflictedField(
                            fieldName = "备注 (Notes)",
                            localValue = "已升级为组织两步验证密钥",
                            remoteValue = "待开启 2FA",
                            selectedChoice = FieldChoice.LOCAL
                        )
                    )
                ),
                ConflictedEntryItem(
                    id = "entry_conflict_2",
                    title = "AWS IAM Production",
                    groupPath = "根目录 / 基础架构",
                    fields = listOf(
                        ConflictedField(
                            fieldName = "用户名 (Username)",
                            localValue = "admin@keepasskey.io",
                            remoteValue = "devops_root",
                            selectedChoice = FieldChoice.LOCAL
                        )
                    )
                )
            )
        )
    )
    val uiState: StateFlow<ConflictResolutionUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ConflictResolutionEvent>()
    val events: SharedFlow<ConflictResolutionEvent> = _events.asSharedFlow()

    fun selectFieldChoice(entryId: String, fieldName: String, choice: FieldChoice) {
        _uiState.update { state ->
            val updated = state.entries.map { entry ->
                if (entry.id == entryId) {
                    val newFields = entry.fields.map { field ->
                        if (field.fieldName == fieldName) field.copy(selectedChoice = choice) else field
                    }
                    entry.copy(fields = newFields)
                } else entry
            }
            state.copy(entries = updated)
        }
    }

    fun selectAll(choice: FieldChoice) {
        _uiState.update { state ->
            val updated = state.entries.map { entry ->
                val newFields = entry.fields.map { it.copy(selectedChoice = choice) }
                entry.copy(fields = newFields)
            }
            state.copy(entries = updated)
        }
    }

    fun applyMerge() {
        viewModelScope.launch {
            _uiState.update { it.copy(isResolving = true) }
            delay(600) // 模拟合并写入与远程校验
            _uiState.update { it.copy(isResolving = false, userMessage = UiMessage(R.string.conflict_resolved_msg)) }
            _events.emit(ConflictResolutionEvent.ResolveSuccess)
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
