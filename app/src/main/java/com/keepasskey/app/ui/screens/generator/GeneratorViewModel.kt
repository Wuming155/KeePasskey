package com.keepasskey.app.ui.screens.generator

import androidx.lifecycle.ViewModel
import com.keepasskey.app.R
import com.keepasskey.app.security.ClipboardSecurityManager
import com.keepasskey.app.ui.model.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class GeneratorViewModel @Inject constructor(
    private val clipboardSecurityManager: ClipboardSecurityManager
) : ViewModel() {

    companion object {
        /** 写入剪贴板时展示给系统的标签（不参与任何安全判定，仅作提示） */
        private const val GENERATED_PASSWORD_CLIP_LABEL = "Generated Password"
    }

    private val _uiState = MutableStateFlow(GeneratorUiState())
    val uiState: StateFlow<GeneratorUiState> = _uiState.asStateFlow()

    init {
        generateNewPassword()
    }

    fun setMode(mode: GeneratorMode) {
        _uiState.update { it.copy(mode = mode) }
        generateNewPassword()
    }

    fun regenerate() {
        generateNewPassword()
    }

    fun setRandomLength(length: Int) {
        _uiState.update { it.copy(randomLength = length) }
        generateNewPassword()
    }

    fun setUseUpper(enabled: Boolean) {
        _uiState.update { it.copy(useUpper = enabled) }
        generateNewPassword()
    }

    fun setUseLower(enabled: Boolean) {
        _uiState.update { it.copy(useLower = enabled) }
        generateNewPassword()
    }

    fun setUseDigits(enabled: Boolean) {
        _uiState.update { it.copy(useDigits = enabled) }
        generateNewPassword()
    }

    fun setUseSymbols(enabled: Boolean) {
        _uiState.update { it.copy(useSymbols = enabled) }
        generateNewPassword()
    }

    fun setExcludeAmbiguous(enabled: Boolean) {
        _uiState.update { it.copy(excludeAmbiguous = enabled) }
        generateNewPassword()
    }

    fun setWordCount(count: Int) {
        _uiState.update { it.copy(wordCount = count) }
        generateNewPassword()
    }

    fun setSeparator(separator: String) {
        _uiState.update { it.copy(separator = separator) }
        generateNewPassword()
    }

    fun setCapitalizeWords(enabled: Boolean) {
        _uiState.update { it.copy(capitalizeWords = enabled) }
        generateNewPassword()
    }

    fun setIncludeNumberInPassphrase(enabled: Boolean) {
        _uiState.update { it.copy(includeNumberInPassphrase = enabled) }
        generateNewPassword()
    }

    fun setMaskPattern(pattern: String) {
        _uiState.update { it.copy(maskPattern = pattern) }
        generateNewPassword()
    }

    fun selectHistoryPassword(password: String) {
        val entropy = PasswordGenerationEngine.calculateEntropy(password).toInt()
        val strength = evaluateStrengthLabel(entropy)
        _uiState.update {
            it.copy(
                currentPassword = password,
                entropyBits = entropy,
                strengthLabel = strength
            )
        }
    }

    /**
     * 复制生成的密码至受保护剪贴板。
     *
     * P0 整改：此前复制逻辑直接写在 GeneratorScreen 的 Composable 内部并直连 ClipboardManager，
     * 绕过了 [ClipboardSecurityManager] 的定时擦除链路——新生成的明文密码会永久滞留剪贴板，
     * 且无视用户在「剪贴板自动清空」设置中配置的超时策略。
     * 现统一走受保护复制：注入官方 `ClipDescription.EXTRA_IS_SENSITIVE` 敏感标记 +
     * 按用户配置超时自动物理清空。
     */
    fun copyGeneratedPassword(password: String) {
        clipboardSecurityManager.copySensitiveText(GENERATED_PASSWORD_CLIP_LABEL, password)
        _uiState.update { it.copy(userMessage = UiMessage(R.string.generator_password_copied)) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    private fun generateNewPassword() {
        val currentState = _uiState.value
        val newPassword = when (currentState.mode) {
            GeneratorMode.RANDOM -> {
                PasswordGenerationEngine.generateRandomPassword(
                    length = currentState.randomLength,
                    useUpper = currentState.useUpper,
                    useLower = currentState.useLower,
                    useDigits = currentState.useDigits,
                    useSymbols = currentState.useSymbols,
                    excludeAmbiguous = currentState.excludeAmbiguous
                )
            }
            GeneratorMode.PASSPHRASE -> {
                PasswordGenerationEngine.generatePassphrase(
                    wordCount = currentState.wordCount,
                    separator = currentState.separator,
                    capitalize = currentState.capitalizeWords,
                    includeNumber = currentState.includeNumberInPassphrase
                )
            }
            GeneratorMode.MASK -> {
                PasswordGenerationEngine.generateMaskedPassword(
                    mask = currentState.maskPattern
                )
            }
        }

        val entropy = PasswordGenerationEngine.calculateEntropy(newPassword).toInt()
        val strength = evaluateStrengthLabel(entropy)

        _uiState.update { current ->
            val updatedHistory = if (current.currentPassword.isNotBlank() && current.currentPassword != newPassword) {
                (listOf(current.currentPassword) + current.history).take(10)
            } else {
                current.history
            }
            current.copy(
                currentPassword = newPassword,
                entropyBits = entropy,
                strengthLabel = strength,
                history = updatedHistory
            )
        }
    }

    private fun evaluateStrengthLabel(entropyBits: Int): UiMessage = when {
        entropyBits < 40 -> UiMessage(R.string.generator_strength_weak, listOf(entropyBits))
        entropyBits < 64 -> UiMessage(R.string.generator_strength_medium, listOf(entropyBits))
        entropyBits < 96 -> UiMessage(R.string.generator_strength_strong, listOf(entropyBits))
        else -> UiMessage(R.string.generator_strength_extreme, listOf(entropyBits))
    }
}
