package com.keepasskey.app.ui.screens.database

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.result.KdbxResult
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

    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)
    private val showCreateDialogFlow = MutableStateFlow(false)
    private val showOpenSourceDialogFlow = MutableStateFlow(false)

    private val _events = MutableSharedFlow<DatabasePickerEvent>()
    val events: SharedFlow<DatabasePickerEvent> = _events.asSharedFlow()

    val uiState: StateFlow<DatabasePickerUiState> = combine(
        vaultRepository.getDatabases(),
        userMessageFlow,
        combine(showCreateDialogFlow, showOpenSourceDialogFlow) { c, o -> Pair(c, o) }
    ) { databases, userMessage, (showCreateDialog, showOpenSourceDialog) ->
        DatabasePickerUiState(
            databases = databases,
            userMessage = userMessage,
            showCreateDialog = showCreateDialog,
            showOpenSourceDialog = showOpenSourceDialog
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

    fun openOpenSourceDialog() {
        showOpenSourceDialogFlow.value = true
    }

    fun closeOpenSourceDialog() {
        showOpenSourceDialogFlow.value = false
    }

    fun createDatabase(name: String, masterPassword: CharArray, keyFile: Boolean, preset: String) {
        viewModelScope.launch {
            // H2 整改：主密码全程 CharArray——复制私有副本并在 finally 擦除；
            // 入参数组为调用方（弹窗）所有，由其自身生命周期管理擦除
            val pwd = masterPassword.copyOf()
            try {
                // H3 整改：创建失败（写盘失败等）不再谎报创建成功
                val result = vaultRepository.createDatabase(name, pwd, keyFile, preset)
                if (result is KdbxResult.Success) {
                    val fileName = if (name.endsWith(".kdbx", ignoreCase = true)) name else "$name.kdbx"
                    showCreateDialogFlow.value = false
                    userMessageFlow.value = UiMessage(R.string.db_picker_msg_created)
                    _events.emit(DatabasePickerEvent.DatabaseSelected(fileName))
                } else {
                    userMessageFlow.value = UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message))
                }
            } finally {
                pwd.fill('0')
            }
        }
    }

    fun importDatabaseFromSource(source: OpenVaultSourceType, name: String, path: String) {
        viewModelScope.launch {
            val result = vaultRepository.importExternalDatabase(name, path, syncType = source.label)
            if (result is KdbxResult.Success) {
                val fileName = if (name.endsWith(".kdbx", ignoreCase = true)) name else "$name.kdbx"
                showOpenSourceDialogFlow.value = false
                userMessageFlow.value = UiMessage(R.string.db_picker_msg_opened)
                _events.emit(DatabasePickerEvent.DatabaseSelected(fileName))
            } else {
                userMessageFlow.value = UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message))
            }
        }
    }

    fun removeDatabase(id: String) {
        viewModelScope.launch {
            val result = vaultRepository.removeDatabase(id)
            if (result is KdbxResult.Success) {
                userMessageFlow.value = UiMessage(R.string.db_picker_msg_removed)
            } else {
                userMessageFlow.value = UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message))
            }
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }
}
