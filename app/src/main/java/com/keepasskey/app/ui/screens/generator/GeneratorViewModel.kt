package com.keepasskey.app.ui.screens.generator

import androidx.lifecycle.ViewModel
import com.keepasskey.app.R
import com.keepasskey.app.security.ClipboardSecurityManager
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.security.ProtectedString
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

        /** 内存历史最大保留条数（超出即淘汰并显式清零） */
        private const val MAX_HISTORY_SIZE = 10
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

    fun selectHistoryPassword(password: ProtectedString) {
        // ISSUE-P2-12：熵值计算走字符数组通道，不把种子物化为额外 String
        val entropy = password.useChars { PasswordGenerationEngine.calculateEntropy(it).toInt() }
        val strength = evaluateStrengthLabel(entropy)
        _uiState.update { current ->
            // 被替换的当前值若未被历史引用则显式擦除（历史项仍是同一实例，不能误清）
            val previous = current.currentPassword
            if (current.history.none { it === previous }) previous.clear()
            current.copy(
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
    fun copyGeneratedPassword(secret: ProtectedString) {
        // ISSUE-P2-16：路径改为 ProtectedString → CharArray（useChars 自动清零）→ 受保护
        // 剪贴板 CharArray 通道，应用侧不再物化不可擦 String；跨进程写入系统服务属框架边界。
        // 自动擦除超时策略仍由 ClipboardSecurityManager 统一负责。
        secret.useChars { chars ->
            clipboardSecurityManager.copySensitiveChars(GENERATED_PASSWORD_CLIP_LABEL, chars)
        }
        _uiState.update { it.copy(userMessage = UiMessage(R.string.generator_password_copied)) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    private fun generateNewPassword() {
        val currentState = _uiState.value
        // ISSUE-P2-16：引擎返回 CharArray 独占副本（生成瞬间不再物化不可擦 String）。
        // 熵值计算与 ProtectedString 密封复用同一副本，密封完成（内部已加密/拷贝）后立即清零。
        val newPasswordChars = when (currentState.mode) {
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

        var entropy = 0
        val newSecret = try {
            entropy = PasswordGenerationEngine.calculateEntropy(newPasswordChars).toInt()
            ProtectedString(newPasswordChars, isProtected = true)
        } finally {
            newPasswordChars.fill('0')
        }
        val strength = evaluateStrengthLabel(entropy)

        _uiState.update { current ->
            val previous = current.currentPassword
            val unchanged = previous.length > 0 && previous == newSecret
            val historyWithPrevious = if (previous.length > 0 && !unchanged) {
                listOf(previous) + current.history
            } else {
                current.history
            }
            val keptHistory = historyWithPrevious.take(MAX_HISTORY_SIZE)
            // 淘汰项与未被引用的旧当前值显式清零
            historyWithPrevious.drop(MAX_HISTORY_SIZE).forEach { it.clear() }
            if (keptHistory.none { it === previous }) previous.clear()
            current.copy(
                currentPassword = newSecret,
                entropyBits = entropy,
                strengthLabel = strength,
                history = keptHistory
            )
        }
    }

    override fun onCleared() {
        // ISSUE-P2-12：ViewModel 销毁时显式擦除受控容器内的全部生成结果
        val current = _uiState.value
        current.currentPassword.clear()
        current.history.forEach { it.clear() }
        super.onCleared()
    }

    private fun evaluateStrengthLabel(entropyBits: Int): UiMessage = when {
        entropyBits < 40 -> UiMessage(R.string.generator_strength_weak, listOf(entropyBits))
        entropyBits < 64 -> UiMessage(R.string.generator_strength_medium, listOf(entropyBits))
        entropyBits < 96 -> UiMessage(R.string.generator_strength_strong, listOf(entropyBits))
        else -> UiMessage(R.string.generator_strength_extreme, listOf(entropyBits))
    }
}
