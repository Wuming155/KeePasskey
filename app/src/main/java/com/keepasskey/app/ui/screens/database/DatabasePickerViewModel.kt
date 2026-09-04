package com.keepasskey.app.ui.screens.database

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DatabasePickerEvent {
    data class DatabaseSelected(val id: String) : DatabasePickerEvent
}

@HiltViewModel
class DatabasePickerViewModel @Inject constructor(
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val userMessageFlow = MutableStateFlow<String?>(null)
    private val showCreateDialogFlow = MutableStateFlow(false)

    private val _events = MutableSharedFlow<DatabasePickerEvent>()
    val events: SharedFlow<DatabasePickerEvent> = _events.asSharedFlow()

    val uiState: StateFlow<DatabasePickerUiState> = combine(
        vaultRepository.getDatabases(),
        userMessageFlow,
        showCreateDialogFlow
    ) { databases, userMessage, showCreateDialog ->
        DatabasePickerUiState(
            databases = databases,
            userMessage = userMessage,
            showCreateDialog = showCreateDialog
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DatabasePickerUiState()
    )

    fun selectDatabase(id: String) {
        viewModelScope.launch {
            vaultRepository.selectDatabase(id)
            _events.emit(DatabasePickerEvent.DatabaseSelected(id))
        }
    }

    fun openCreateDialog() {
        showCreateDialogFlow.value = true
    }

    fun closeCreateDialog() {
        showCreateDialogFlow.value = false
    }

    fun createDatabase(name: String, masterPassword: String, keyFile: Boolean, preset: String) {
        viewModelScope.launch {
            vaultRepository.createDatabase(name, masterPassword, keyFile, preset)
            showCreateDialogFlow.value = false
            userMessageFlow.value = "新密码库已成功创建"
        }
    }

    fun importExternalDatabase() {
        viewModelScope.launch {
            vaultRepository.importExternalDatabase("imported-vault.kdbx", "/storage/emulated/0/Download/imported-vault.kdbx")
            userMessageFlow.value = "已成功导入外部 KDBX 密码库"
        }
    }

    fun removeDatabase(id: String) {
        viewModelScope.launch {
            vaultRepository.removeDatabase(id)
            userMessageFlow.value = "已从切换列表中移除该密码库关联"
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }
}
