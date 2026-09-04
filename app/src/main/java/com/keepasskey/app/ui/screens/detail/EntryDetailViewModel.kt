package com.keepasskey.app.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * 凭据详情状态容器 ViewModel
 */
@HiltViewModel
class EntryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val entryIdFlow = MutableStateFlow<String?>(savedStateHandle.get<String>("entryId"))
    private val isPasswordVisibleFlow = MutableStateFlow(false)
    private val isFavoriteFlow = MutableStateFlow(false)
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
        .combine(userMessageFlow) { (entry, isPassVisible, isFav), message ->
            EntryDetailUiState(
                entry = entry,
                isPasswordVisible = isPassVisible,
                isFavorite = isFav,
                userMessage = message
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EntryDetailUiState(isLoading = true)
        )

    fun setEntryId(id: String) {
        entryIdFlow.value = id
    }

    fun togglePasswordVisibility() {
        isPasswordVisibleFlow.update { !it }
    }

    fun toggleFavorite() {
        isFavoriteFlow.update { !it }
    }

    fun showMessage(message: String) {
        userMessageFlow.value = message
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }
}
