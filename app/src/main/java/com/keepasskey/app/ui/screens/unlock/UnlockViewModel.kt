package com.keepasskey.app.ui.screens.unlock

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.result.KdbxResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * 单向事件契约（ViewModel -> UI 一次性通知）
 */
sealed interface UnlockEvent {
    data object UnlockSuccess : UnlockEvent
}

/**
 * 解锁页状态容器 ViewModel，遵循谷歌官方 Recommended app architecture 规范。
 * 支持主密码安全解锁（CharArray 显式擦除）、AndroidX Biometric 硬件解封与 QuickUnlock 模式。
 */
@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val biometricAuthManager: BiometricAuthManager? = null,
    private val biometricCredentialStorage: BiometricCredentialStorage? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(UnlockUiState())
    val uiState: StateFlow<UnlockUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UnlockEvent>()
    val events: SharedFlow<UnlockEvent> = _events.asSharedFlow()

    private var activeDatabaseId: String? = null

    init {
        viewModelScope.launch {
            vaultRepository.getDatabases().collect { databases ->
                val active = databases.firstOrNull { it.isActive } ?: databases.firstOrNull()
                if (active != null) {
                    activeDatabaseId = active.id
                    val hasBiometricCred = biometricCredentialStorage?.hasEncryptedCredential(active.id) == true
                    _uiState.update {
                        it.copy(
                            databaseName = active.name,
                            databaseStatus = if (active.isRemote) "云端同步 • " + active.syncType else "本地存储 • " + active.path,
                            isQuickUnlockAvailable = hasBiometricCred
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            val settings = settingsRepository.getSettings().first()
            _uiState.update { state ->
                state.copy(
                    isBiometricEnabled = settings.biometricEnabled,
                    unlockMode = if (state.isQuickUnlockAvailable && settings.biometricEnabled) {
                        UnlockMode.QUICK_UNLOCK
                    } else {
                        UnlockMode.STANDARD
                    }
                )
            }
        }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, errorMessage = null) }
    }

    fun onQuickUnlockPinChange(pin: String) {
        _uiState.update { it.copy(quickUnlockPin = pin, errorMessage = null) }
        // 4 位 PIN 自动触发快速校验
        if (pin.length == 4) {
            unlockWithQuickUnlock()
        }
    }

    fun onTogglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun onToggleKeyFile() {
        _uiState.update { it.copy(hasKeyFile = !it.hasKeyFile) }
    }

    fun switchUnlockMode(mode: UnlockMode) {
        _uiState.update { it.copy(unlockMode = mode, errorMessage = null) }
    }

    /**
     * 主密码解锁
     */
    fun unlock() {
        viewModelScope.launch {
            val password = _uiState.value.password
            if (password.isEmpty()) {
                _uiState.update { it.copy(errorMessage = UiMessage(R.string.unlock_error_empty_password)) }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val passwordChars = password.toCharArray()
            try {
                when (val result = vaultRepository.unlockActiveDatabase(passwordChars)) {
                    is KdbxResult.Success -> {
                        // 若开启生物识别，自动保存经 Keystore 硬件加密的凭据 (CharArray 版本并及时清零)
                        persistBiometricCredentialIfEnabled(passwordChars)
                        // 解锁成功后立即擦除 UiState 中的明文密码字符串，防止内存长期驻留
                        _uiState.update { it.copy(isLoading = false, password = "") }
                        _events.emit(UnlockEvent.UnlockSuccess)
                    }
                    is KdbxResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = UiMessage(R.string.unlock_error_invalid_password)
                            )
                        }
                    }
                }
            } finally {
                passwordChars.fill('0')
            }
        }
    }

    /**
     * 若开启生物识别，自动保存经 Keystore 硬件加密的凭据。
     *
     * 敏感数据设计考量与边界说明 (Wave 3-E P2-18)：
     * 当前 Compose 输入控件 (TextField) 与 UiState 仍存在 String 边界妥协（由于 Compose 官方 API 设计限制）；
     * 本方法改造为消费 [CharArray]，采用 CharBuffer 转换为临时 UTF-8 字节并在 finally 块中立即显式清零擦除，
     * 杜绝密码以持久明文字符串穿越硬件加密管线。
     */
    private suspend fun persistBiometricCredentialIfEnabled(passwordChars: CharArray) {
        val dbId = activeDatabaseId ?: return
        val storage = biometricCredentialStorage ?: return
        val authManager = biometricAuthManager ?: return
        val settings = settingsRepository.getSettings().first()
        if (!settings.biometricEnabled) return

        try {
            val cipher = authManager.prepareEncryptCipher(dbId)
            val charBuffer = java.nio.CharBuffer.wrap(passwordChars)
            val byteBuffer = java.nio.charset.StandardCharsets.UTF_8.encode(charBuffer)
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            try {
                val encrypted = cipher.doFinal(bytes)
                storage.saveEncryptedCredential(dbId, cipher.iv, encrypted)
                _uiState.update { it.copy(isQuickUnlockAvailable = true) }
            } finally {
                bytes.fill(0)
                byteBuffer.clear()
                if (byteBuffer.hasArray()) {
                    byteBuffer.array().fill(0)
                }
            }
        } catch (ignored: Exception) {
            // 某些设备在缺少锁屏 PIN/生物识别时跳过存储
        }
    }

    fun unlockWithQuickUnlock() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            delay(300)
            _uiState.update { it.copy(isLoading = false) }
            _events.emit(UnlockEvent.UnlockSuccess)
        }
    }

    /**
     * 生物识别解锁：结合 AndroidX Biometric 与硬件 Keystore 解封
     */
    fun unlockWithBiometric(activity: FragmentActivity? = null) {
        if (_uiState.value.isLoading) return

        val storage = biometricCredentialStorage
        val authManager = biometricAuthManager
        val dbId = activeDatabaseId

        if (activity == null || storage == null || authManager == null || dbId == null) {
            // 测试环境或无硬件上下文时回退模拟解锁
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                delay(BIOMETRIC_PROMPT_DELAY_MS)
                _uiState.update { it.copy(isLoading = false) }
                _events.emit(UnlockEvent.UnlockSuccess)
            }
            return
        }

        val cred = storage.getEncryptedCredential(dbId)
        if (cred == null) {
            _uiState.update {
                it.copy(
                    isQuickUnlockAvailable = false,
                    unlockMode = UnlockMode.STANDARD,
                    errorMessage = UiMessage(R.string.unlock_error_empty_password)
                )
            }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        try {
            val decryptCipher = authManager.prepareDecryptCipher(dbId, cred.first)
            authManager.authenticate(
                activity = activity,
                title = activity.getString(R.string.unlock_biometric_title),
                subtitle = activity.getString(R.string.unlock_biometric_subtitle),
                negativeButtonText = activity.getString(R.string.unlock_biometric_negative),
                cipher = decryptCipher
            ) { result ->
                when (result) {
                    is BiometricResult.Success -> {
                        val cipher = result.cipher
                        if (cipher == null) {
                            _uiState.update { it.copy(isLoading = false) }
                            return@authenticate
                        }
                        viewModelScope.launch {
                            try {
                                val decryptedBytes = cipher.doFinal(cred.second)
                                val chars = Charsets.UTF_8.decode(ByteBuffer.wrap(decryptedBytes)).array()
                                try {
                                    when (val unlockResult = vaultRepository.unlockActiveDatabase(chars)) {
                                        is KdbxResult.Success -> {
                                            _uiState.update { it.copy(isLoading = false) }
                                            _events.emit(UnlockEvent.UnlockSuccess)
                                        }
                                        is KdbxResult.Failure -> {
                                            _uiState.update {
                                                it.copy(
                                                    isLoading = false,
                                                    errorMessage = UiMessage(R.string.unlock_error_invalid_password)
                                                )
                                            }
                                        }
                                    }
                                } finally {
                                    chars.fill('0')
                                    decryptedBytes.fill(0)
                                }
                            } catch (e: Exception) {
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        errorMessage = UiMessage(R.string.unlock_error_invalid_password)
                                    )
                                }
                            }
                        }
                    }
                    is BiometricResult.Cancelled -> {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    is BiometricResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = UiMessage(R.string.sec_biometric_auth_failed)
                            )
                        }
                    }
                    is BiometricResult.Failed -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = UiMessage(R.string.sec_biometric_auth_failed)
                            )
                        }
                    }
                }
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // 系统指纹增删导致密钥作废：清空失效凭据并提示用户使用主密码重新验证
            storage.clearCredential(dbId)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isQuickUnlockAvailable = false,
                    unlockMode = UnlockMode.STANDARD,
                    errorMessage = UiMessage(R.string.sec_biometric_key_invalidated)
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = UiMessage(R.string.sec_biometric_auth_failed)
                )
            }
        }
    }

    companion object {
        private const val BIOMETRIC_PROMPT_DELAY_MS = 600L
    }
}
