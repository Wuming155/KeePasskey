package com.keepasskey.app.ui.screens.generator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.security.ProtectedString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

internal data class GeneratorGeneratedResult(
    val secret: ProtectedString,
    val entropyBits: Int
)

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

    /** 受控生成入口和调度器供 JVM 竞态用例设置屏障；生产默认仍使用真实引擎与 Default。 */
    internal var generateResult: suspend (GeneratorUiState) -> GeneratorGeneratedResult = { state ->
        generatePasswordResult(state)
    }
    internal var generationDispatcher: CoroutineDispatcher = Dispatchers.Default

    /** 与清理/发布共用门闩，保证失效检查和状态提交之间不会插入锁定清理。 */
    private val generationGate = Any()
    private var generationEpoch = 0L

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
        // ISSUE-P3-360 AC②：滑杆调节只更新参数并实时重生成（预览不回退），中间值不入 history——
        // 此前拖一次滑杆即把每个中间态推进 history，一次拖动塞满 10 条
        generateNewPassword(recordInHistory = false)
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
        // ISSUE-P3-360 AC②：同 setRandomLength——词数滑杆同为滑杆调节，中间值不入 history
        generateNewPassword(recordInHistory = false)
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
        val requestEpoch = synchronized(generationGate) { ++generationEpoch }
        viewModelScope.launch {
            val entropy = withContext(Dispatchers.Default) {
                password.useChars { PasswordGenerationEngine.calculateEntropy(it).toInt() }
            }
            synchronized(generationGate) {
                if (requestEpoch == generationEpoch && !password.cleared) {
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
        // ISSUE-P2-287 AC②：已擦除实例（锁库后 UI 仍持有的旧引用）如实降级为提示，
        // 禁交由 ProtectedString 的 fail-fast 抛 IllegalStateException（此前点复制即崩）
        if (secret.cleared) {
            _uiState.update { it.copy(userMessage = UiMessage(R.string.generator_locked_regenerate)) }
            return
        }
        // ISSUE-P2-16：路径改为 ProtectedString → CharArray（useChars 自动清零）→ 受保护
        // 剪贴板 CharArray 通道，应用侧不再物化不可擦 String；跨进程写入系统服务属框架边界。
        // 自动擦除超时策略仍由 ClipboardSecurityManager 统一负责。
        secret.useChars { chars ->
            clipboardSecurityManager.copySensitiveChars(GENERATED_PASSWORD_CLIP_LABEL, chars)
        }
        // ISSUE-P3-360 AC③b：生成器复制同为敏感通道——消息附「Ns 后自动清空」倒计时
        // （秒数经 ClipboardSecurityChannel.scheduledClearSeconds 同源裁决；假通道缺省 null 即不附）
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    userMessage = UiMessage(
                        resId = R.string.generator_password_copied,
                        clipboardClearSeconds = clipboardSecurityManager.scheduledClearSeconds()
                    )
                )
            }
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    /**
     * @param recordInHistory 是否把被替换的当前值推进内存历史。
     *   ISSUE-P3-360 AC②：滑杆调节（长度 / 词数）传 `false`——仍实时重生成保持预览，
     *   但中间态不入 history；其余动作（模式切换 / 开关 / 重新生成等）保持既有入史语义。
     */
    private fun generateNewPassword(recordInHistory: Boolean = true) {
        val currentState = _uiState.value
        val requestEpoch = synchronized(generationGate) { ++generationEpoch }
        // ISSUE-P2-286：强度内核评估（CPU 热路径）与生成一并下沉 Dispatchers.Default（§3 规则 2）；
        // 熵读数收敛单一真相源：随机 / 掩码走 crypto 内核（calculateEntropy），
        // 口令短语走「词数 × log2(词表) + 变形位」模型（passphraseEntropyBits，AC②）
        viewModelScope.launch {
            val completedResult = AtomicReference<GeneratorGeneratedResult?>(null)
            var committed = false
            try {
                val result = withContext(generationDispatcher) {
                    generateResult(currentState).also(completedResult::set)
                }
                synchronized(generationGate) {
                    if (requestEpoch == generationEpoch) {
                        val newSecret = result.secret
                        val entropy = result.entropyBits
                        _uiState.update { current ->
                            val previous = current.currentPassword
                            val unchanged = previous.length > 0 && previous == newSecret
                            val historyWithPrevious = if (recordInHistory && previous.length > 0 && !unchanged) {
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
                        committed = true
                    }
                }
            } finally {
                if (!committed) completedResult.getAndSet(null)?.secret?.clear()
            }
        }
    }

    /** 生产生成实现：CharArray 在密封和熵计算后立即清零。 */
    private fun generatePasswordResult(currentState: GeneratorUiState): GeneratorGeneratedResult {
        val newPasswordChars = when (currentState.mode) {
            GeneratorMode.RANDOM -> PasswordGenerationEngine.generateRandomPassword(
                length = currentState.randomLength,
                useUpper = currentState.useUpper,
                useLower = currentState.useLower,
                useDigits = currentState.useDigits,
                useSymbols = currentState.useSymbols,
                excludeAmbiguous = currentState.excludeAmbiguous
            )
            GeneratorMode.PASSPHRASE -> PasswordGenerationEngine.generatePassphrase(
                wordCount = currentState.wordCount,
                separator = currentState.separator,
                capitalize = currentState.capitalizeWords,
                includeNumber = currentState.includeNumberInPassphrase
            )
            GeneratorMode.MASK -> PasswordGenerationEngine.generateMaskedPassword(
                mask = currentState.maskPattern
            )
        }
        try {
            val bits = when (currentState.mode) {
                GeneratorMode.PASSPHRASE -> PasswordGenerationEngine.passphraseEntropyBits(
                    wordCount = currentState.wordCount,
                    includeNumber = currentState.includeNumberInPassphrase
                )
                else -> PasswordGenerationEngine.calculateEntropy(newPasswordChars).toInt()
            }
            return GeneratorGeneratedResult(ProtectedString(newPasswordChars, isProtected = true), bits)
        } finally {
            newPasswordChars.fill('0')
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
        synchronized(generationGate) {
            generationEpoch++
            val current = _uiState.value
            current.currentPassword.clear()
            current.history.forEach { it.clear() }
            // ISSUE-P2-287 AC①：擦除与状态失效**原子完成**——交出全新空实例引用
            // （`ProtectedString.EMPTY` 共享单例，其 clear 为 no-op、读数恒为空），
            // 令 UI 的 `remember(currentPassword)` 键变化：旧明文不再随已物化的
            // remember 值留存屏上。
            _uiState.update {
                it.copy(
                    currentPassword = ProtectedString.EMPTY,
                    entropyBits = 0,
                    history = emptyList()
                )
            }
        }
    }
}
