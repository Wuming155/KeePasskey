package com.keepasskey.app.ui.screens.unlock

import androidx.lifecycle.ViewModel
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 单向事件契约（ViewModel -> UI 一次性通知）
 */
sealed interface UnlockEvent {
    data object UnlockSuccess : UnlockEvent
}

/**
 * 解锁页状态容器 ViewModel，遵循谷歌官方 Recommended app architecture 规范
 */
@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UnlockUiState())
    val uiState: StateFlow<UnlockUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UnlockEvent>()
    val events: SharedFlow<UnlockEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            vaultRepository.getDatabases().collect { databases ->
                val active = databases.firstOrNull { it.isActive } ?: databases.firstOrNull()
                if (active != null) {
                    _uiState.update {
                        it.copy(
                            databaseName = active.name,
                            databaseStatus = if (active.isRemote) "云端同步 • " + active.syncType else "本地存储 • " + active.path
                        )
                    }
                }
            }
        }
        // 解锁方式默认行为：默认要求输入完整主密码；若已输入过密码（存在快捷解锁缓存）
        // 且开启生物认证，则优先使用生物认证解锁；若未设置生物解锁，则优先使用快速解锁
        viewModelScope.launch {
            val settings = settingsRepository.getSettings().first()
            _uiState.update { state ->
                state.copy(
                    isBiometricEnabled = settings.biometricEnabled,
                    unlockMode = if (state.isQuickUnlockAvailable) UnlockMode.QUICK_UNLOCK else UnlockMode.STANDARD
                )
            }
        }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, errorMessage = null) }
    }

    fun onQuickUnlockPinChange(pin: String) {
        _uiState.update { it.copy(quickUnlockPin = pin, errorMessage = null) }
        // 4 位 PIN 自动触发快速校验
        if (pin.length == 4) {
            unlockWithQuickUnlock()
        }
    }

    fun onTogglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun onToggleKeyFile() {
        _uiState.update { it.copy(hasKeyFile = !it.hasKeyFile) }
    }

    fun switchUnlockMode(mode: UnlockMode) {
        _uiState.update { it.copy(unlockMode = mode, errorMessage = null) }
    }

    fun unlock() {
        viewModelScope.launch {
            if (_uiState.value.password.isEmpty()) {
                _uiState.update { it.copy(errorMessage = UiMessage(R.string.unlock_error_empty_password)) }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            delay(500)
            _uiState.update { it.copy(isLoading = false) }
            _events.emit(UnlockEvent.UnlockSuccess)
        }
    }

    fun unlockWithQuickUnlock() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            delay(300)
            _uiState.update { it.copy(isLoading = false) }
            _events.emit(UnlockEvent.UnlockSuccess)
        }
    }

    companion object {
        // 模拟生物识别弹窗验证耗时（毫秒）
        private const val BIOMETRIC_PROMPT_DELAY_MS = 600L
    }

    fun unlockWithBiometric() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // 阶段 1 假数据：模拟系统生物识别弹窗的验证耗时
            delay(BIOMETRIC_PROMPT_DELAY_MS)
            _uiState.update { it.copy(isLoading = false) }
            _events.emit(UnlockEvent.UnlockSuccess)
        }
    }
}
