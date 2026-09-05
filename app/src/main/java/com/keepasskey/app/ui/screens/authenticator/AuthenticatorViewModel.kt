package com.keepasskey.app.ui.screens.authenticator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.otp.OtpEngine
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
    private val timerSecondsFlow = MutableStateFlow(OtpEngine.getRemainingSeconds())
    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)

    init {
        // 每秒自增刷新 TOTP 剩余秒数倒计时
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            while (isActive) {
                delay(1000)
                timerSecondsFlow.value = OtpEngine.getRemainingSeconds()
            }
        }
    }

    val uiState: StateFlow<AuthenticatorUiState> = combine(
        vaultRepository.getEntries(),
        searchQueryFlow,
        timerSecondsFlow,
        userMessageFlow
    ) { entries, query, _, message ->
        // 过滤包含真实 TOTP 密钥或有效验证码的条目
        val totpEntries = entries.filter { !it.totpSecret.isNullOrBlank() || it.totpCode != null }

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
            val secret = entry.totpSecret
            val period = entry.totpPeriod
            val digits = entry.totpDigits
            val algo = when (entry.totpAlgorithm.uppercase()) {
                "SHA256" -> OtpEngine.HashAlgorithm.SHA256
                "SHA512" -> OtpEngine.HashAlgorithm.SHA512
                else -> OtpEngine.HashAlgorithm.SHA1
            }

            val remaining = OtpEngine.getRemainingSeconds(periodSeconds = period)
            val raw = if (!secret.isNullOrBlank()) {
                try {
                    OtpEngine.calculateTotp(
                        secretKeyBase32 = secret,
                        periodSeconds = period,
                        digits = digits,
                        algorithm = algo
                    )
                } catch (_: Exception) {
                    entry.totpCode ?: "000000"
                }
            } else {
                entry.totpCode ?: "000000"
            }

            val formatted = when (raw.length) {
                6 -> "${raw.substring(0, 3)} ${raw.substring(3)}"
                8 -> "${raw.substring(0, 4)} ${raw.substring(4)}"
                else -> raw
            }

            TotpCardItem(
                entryId = entry.id,
                title = entry.title,
                account = entry.username,
                codeFormatted = formatted,
                codeRaw = raw,
                remainingSeconds = remaining,
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
}
