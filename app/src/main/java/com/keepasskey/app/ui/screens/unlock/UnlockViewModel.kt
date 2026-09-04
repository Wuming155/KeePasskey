package com.keepasskey.app.ui.screens.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 单向事件契约（ViewModel -> UI 一次性通知）
 */
sealed interface UnlockEvent {
    data object UnlockSuccess : UnlockEvent
    data class ShowMessage(val message: String) : UnlockEvent
}

/**
 * 解锁页状态容器 ViewModel，遵循谷歌官方 Recommended app architecture 规范
 */
@HiltViewModel
class UnlockViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(UnlockUiState())
    val uiState: StateFlow<UnlockUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UnlockEvent>()
    val events: SharedFlow<UnlockEvent> = _events.asSharedFlow()

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, errorMessage = null) }
    }

    fun onTogglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun onToggleKeyFile() {
        _uiState.update { it.copy(hasKeyFile = !it.hasKeyFile) }
    }

    fun unlock() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            _events.emit(UnlockEvent.UnlockSuccess)
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun unlockWithBiometric() {
        viewModelScope.launch {
            _events.emit(UnlockEvent.UnlockSuccess)
        }
    }
}
