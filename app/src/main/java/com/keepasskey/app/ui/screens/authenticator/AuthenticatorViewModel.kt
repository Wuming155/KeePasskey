package com.keepasskey.app.ui.screens.authenticator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthenticatorViewModel @Inject constructor(
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")
    private val timerSecondsFlow = MutableStateFlow(calculateCurrentRemainingSeconds())
    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)

    init {
        // 每秒自增刷新 TOTP 剩余秒数倒计时
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                timerSecondsFlow.value = calculateCurrentRemainingSeconds()
            }
        }
    }

    val uiState: StateFlow<AuthenticatorUiState> = combine(
        vaultRepository.getEntries(),
        searchQueryFlow,
        timerSecondsFlow,
        userMessageFlow
    ) { entries, query, remainingSeconds, message ->
        // 过滤包含 TOTP 验证码的条目
        val totpEntries = entries.filter { entry ->
            entry.totpCode != null || entry.title.contains("GitHub", ignoreCase = true) ||
                    entry.title.contains("Google", ignoreCase = true) || entry.title.contains("AWS", ignoreCase = true)
        }

        val filtered = if (query.isBlank()) {
            totpEntries
        } else {
            totpEntries.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.username.contains(query, ignoreCase = true) ||
                        it.url.contains(query, ignoreCase = true)
            }
        }

        val items = filtered.map { entry ->
            val raw = entry.totpCode ?: generateMockCode(entry.id, remainingSeconds)
            val formatted = if (raw.length == 6) {
                "${raw.substring(0, 3)} ${raw.substring(3)}"
            } else raw

            TotpCardItem(
                entryId = entry.id,
                title = entry.title,
                account = entry.username,
                codeFormatted = formatted,
                codeRaw = raw,
                remainingSeconds = remainingSeconds,
                iconName = entry.iconName,
                url = entry.url
            )
        }

        AuthenticatorUiState(
            items = items,
            searchQuery = query,
            userMessage = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AuthenticatorUiState()
    )

    fun onSearchQueryChange(query: String) {
        searchQueryFlow.value = query
    }

    fun copyTotpCode(code: String) {
        userMessageFlow.value = UiMessage(R.string.auth_totp_copied, listOf(code))
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }

    private fun calculateCurrentRemainingSeconds(): Int {
        val nowSec = (System.currentTimeMillis() / 1000).toInt()
        val remainder = nowSec % 30
        return 30 - remainder
    }

    private fun generateMockCode(seed: String, remainingSeconds: Int): String {
        val hash = kotlin.math.abs(seed.hashCode() xor ((System.currentTimeMillis() / 30000).toInt()))
        return String.format("%06d", hash % 1000000)
    }
}
