package com.keepasskey.app.ui.screens.generator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.security.ProtectedString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class GeneratorViewModel @Inject constructor(
    private val clipboardSecurityManager: ClipboardSecurityChannel,
    // ISSUE-P2-65：会话锁定观察者注册点（null 仅用于纯 JVM 单测）；
    // 生成结果属明文，锁库 / 关库时必须立即擦除，不得仅依赖 ViewModel 销毁（`onCleared`）。
    private val databaseSession: com.keepasskey.database.session.DatabaseSession? = null
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
        // ISSUE-P2-286：强度内核为 CPU 热路径，评估段下沉 Dispatchers.Default（§3 规则 2）
        viewModelScope.launch {
            val entropy = withContext(Dispatchers.Default) {
                password.useChars { PasswordGenerationEngine.calculateEntropy(it).toInt() }
            }
            _uiState.update { current ->
                // 被替换的当前值若未被历史引用则显式擦除（历史项仍是同一实例，不能误清）
                val previous = current.currentPassword
                if (current.history.none { it === previous }) previous.clear()
                current.copy(
                    currentPassword = password,
                    entropyBits = entropy
                )
            }
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
        // ISSUE-P2-286：强度内核评估（CPU 热路径）与生成一并下沉 Dispatchers.Default（§3 规则 2）；
        // 熵读数收敛单一真相源：随机 / 掩码走 crypto 内核（calculateEntropy），
        // 口令短语走「词数 × log2(词表) + 变形位」模型（passphraseEntropyBits，AC②）
        viewModelScope.launch {
            val (newSecret, entropy) = withContext(Dispatchers.Default) {
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

                try {
                    val bits = when (currentState.mode) {
                        GeneratorMode.PASSPHRASE -> PasswordGenerationEngine.passphraseEntropyBits(
                            wordCount = currentState.wordCount,
                            includeNumber = currentState.includeNumberInPassphrase
                        )
                        else -> PasswordGenerationEngine.calculateEntropy(newPasswordChars).toInt()
                    }
                    ProtectedString(newPasswordChars, isProtected = true) to bits
                } finally {
                    newPasswordChars.fill('0')
                }
            }

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
                    history = keptHistory
                )
            }
        }
    }

    override fun onCleared() {
        // ISSUE-P2-12：ViewModel 销毁时显式擦除受控容器内的全部生成结果
        clearGeneratedSecrets()
        sessionLockGuard.unregister()
        super.onCleared()
    }

    /** ISSUE-P2-65：会话锁定 / 关闭时擦除全部生成结果（当前 + 历史）。 */
    private val sessionLockGuard = com.keepasskey.database.session.SessionLockGuard(databaseSession) {
        clearGeneratedSecrets()
    }

    init {
        // ISSUE-P2-65：注册会话锁定观察者（须在 [sessionLockGuard] 声明之后）
        sessionLockGuard.register()
    }

    private fun clearGeneratedSecrets() {
        val current = _uiState.value
        current.currentPassword.clear()
        current.history.forEach { it.clear() }
    }
}
