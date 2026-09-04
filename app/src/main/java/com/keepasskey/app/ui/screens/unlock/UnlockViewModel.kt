package com.keepasskey.app.ui.screens.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.repository.VaultRepository
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
    private val vaultRepository: VaultRepository
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
    }

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
            // 阶段 1 校验：主密码非空才进入派生流程
            if (_uiState.value.password.isEmpty()) {
                _uiState.update { it.copy(errorMessage = "请输入主密码以解锁") }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            // 模拟 Argon2id 密钥派生与 KDBX 完整性校验耗时（接入真实 crypto 模块后替换）
            delay(600)
            _uiState.update { it.copy(isLoading = false) }
            _events.emit(UnlockEvent.UnlockSuccess)
        }
    }

    fun unlockWithBiometric() {
        viewModelScope.launch {
            _events.emit(UnlockEvent.UnlockSuccess)
        }
    }
}
