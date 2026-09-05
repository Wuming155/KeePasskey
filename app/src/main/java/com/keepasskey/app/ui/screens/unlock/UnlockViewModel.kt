package com.keepasskey.app.ui.screens.unlock

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.QuickUnlockPinStore
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.result.KdbxResult
import dagger.hilt.android.lifecycle.HiltViewModel
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
    // 依赖在类型上允许为 null 仅用于单测注入空实现；生产 DI 恒注入真实实例
    private val biometricAuthManager: BiometricAuthManager?,
    private val biometricCredentialStorage: BiometricCredentialStorage?,
    private val quickUnlockPinStore: QuickUnlockPinStore?,
    private val debugLog: DebugLogBuffer
) : ViewModel() {

    private val _uiState = MutableStateFlow(UnlockUiState())
    val uiState: StateFlow<UnlockUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UnlockEvent>()
    val events: SharedFlow<UnlockEvent> = _events.asSharedFlow()

    private var activeDatabaseId: String? = null

    /**
     * QuickUnlock 首次登记时暂存的 PIN（仅内存驻留）。
     * 待用户以完整主密码解锁成功后，用它把主凭据封印进 [QuickUnlockPinStore]，随即清零。
     */
    private var pendingQuickUnlockPin: CharArray? = null

    /**
     * 主密码敏感态：仅以 CharArray 驻留 ViewModel 内部（绝不进入 UiState/StateFlow）。
     * 更换内容与解锁完成后立即显式清零。
     */
    private var passwordChars = CharArray(0)

    init {
        viewModelScope.launch {
            vaultRepository.getDatabases().collect { databases ->
                val active = databases.firstOrNull { it.isActive } ?: databases.firstOrNull()
                if (active != null) {
                    activeDatabaseId = active.id
                    val hasBiometricCred = biometricCredentialStorage?.hasEncryptedCredential(active.id) == true
                    val hasQuickUnlockCred = quickUnlockPinStore?.hasBoundCredential(active.id) == true
                    _uiState.update {
                        it.copy(
                            databaseName = active.name,
                            databaseStatus = if (active.isRemote) "云端同步 • " + active.syncType else "本地存储 • " + active.path,
                            isQuickUnlockAvailable = hasQuickUnlockCred || hasBiometricCred
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
                    unlockMode = if (state.isQuickUnlockAvailable) {
                        UnlockMode.QUICK_UNLOCK
                    } else {
                        UnlockMode.STANDARD
                    }
                )
            }
        }
    }

    /**
     * 主密码输入上行（来自 [com.keepasskey.app.ui.components.SecurePasswordField] 的 CharArray 桥接）。
     * 输入的数组仅在本次回调内有效，此处立即复制持有并清零上一份。
     */
    fun onPasswordChangeSecure(password: CharArray) {
        passwordChars.fill('0')
        passwordChars = password.copyOf()
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    fun onQuickUnlockPinChange(pin: String) {
        _uiState.update { it.copy(quickUnlockPin = pin, errorMessage = null, infoMessage = null) }
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

    /** H4-只读整改：切换「只读打开」——开启后本次会话写盘硬拒绝 */
    fun onToggleReadOnly() {
        _uiState.update { it.copy(openReadOnly = !it.openReadOnly) }
    }

    fun switchUnlockMode(mode: UnlockMode) {
        _uiState.update { it.copy(unlockMode = mode, errorMessage = null, infoMessage = null) }
    }

    /**
     * 主密码解锁
     */
    fun unlock() {
        viewModelScope.launch {
            if (passwordChars.isEmpty()) {
                _uiState.update { it.copy(errorMessage = UiMessage(R.string.unlock_error_empty_password)) }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                when (val result = vaultRepository.unlockActiveDatabase(passwordChars, readOnly = _uiState.value.openReadOnly)) {
                    is KdbxResult.Success -> {
                        debugLog.info(TAG, "主密码解锁成功")
                        // QuickUnlock 首次登记流程：以本次解锁的真实主密码封印 PIN 保护凭据
                        bindQuickUnlockCredentialIfPending(passwordChars)
                        // 若开启生物识别，自动保存经 Keystore 硬件加密的凭据 (CharArray 版本并及时清零)
                        persistBiometricCredentialIfEnabled(passwordChars)
                        // 解锁成功后立即擦除驻留的主密码字符数组
                        passwordChars.fill('0')
                        passwordChars = CharArray(0)
                        _uiState.update { it.copy(isLoading = false) }
                        _events.emit(UnlockEvent.UnlockSuccess)
                    }
                    is KdbxResult.Failure -> {
                        debugLog.warn(TAG, "主密码解锁失败（凭据不匹配）")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = UiMessage(R.string.unlock_error_invalid_password)
                            )
                        }
                    }
                }
            } finally {
                // 失败重试路径保留输入，成功路径已在上方清零；此处仅确保异常时亦清零
                if (_uiState.value.isLoading) {
                    passwordChars.fill('0')
                    passwordChars = CharArray(0)
                }
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
            val store = quickUnlockPinStore
            val dbId = activeDatabaseId
            if (store == null || dbId == null) {
                _uiState.update {
                    it.copy(errorMessage = UiMessage(R.string.unlock_error_quick_unavailable))
                }
                return@launch
            }

            val pin = _uiState.value.quickUnlockPin
            if (pin.length != QUICK_UNLOCK_PIN_LENGTH) {
                _uiState.update {
                    it.copy(errorMessage = UiMessage(R.string.unlock_error_pin_length))
                }
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }

            if (!store.hasBoundCredential(dbId)) {
                // 首次使用 QuickUnlock：仅登记 PIN 校验因子，须完成一次完整主密码解锁以封印凭据
                pendingQuickUnlockPin?.fill('0')
                pendingQuickUnlockPin = pin.toCharArray()
                store.enrollPin(dbId, pin.toCharArray())
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        unlockMode = UnlockMode.STANDARD,
                        quickUnlockPin = "",
                        infoMessage = UiMessage(R.string.unlock_quick_pin_enrolled_hint)
                    )
                }
                return@launch
            }

            // 已封印凭据：PIN 校验通过后解封主密码并用其真实解锁密码库
            // 观察 2 整改：熔断预检——连续失败触发指数退避锁定期间给出可读的剩余等待提示
            val remainingLockoutMs = store.getRemainingLockoutMs(dbId)
            if (remainingLockoutMs > 0) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        quickUnlockPin = "",
                        errorMessage = UiMessage(
                            R.string.unlock_error_pin_locked,
                            listOf((remainingLockoutMs / 1000).coerceAtLeast(1))
                        )
                    )
                }
                return@launch
            }

            val masterChars = store.unlockWithPin(dbId, pin.toCharArray())
            if (masterChars == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiMessage(R.string.unlock_error_pin_invalid)
                    )
                }
                return@launch
            }
            try {
                when (val result = vaultRepository.unlockActiveDatabase(masterChars)) {
                    is KdbxResult.Success -> {
                        debugLog.info(TAG, "QuickUnlock PIN 解锁成功")
                        _uiState.update { it.copy(isLoading = false, quickUnlockPin = "") }
                        _events.emit(UnlockEvent.UnlockSuccess)
                    }
                    is KdbxResult.Failure -> {
                        debugLog.warn(TAG, "主密码解锁失败（凭据不匹配）")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = UiMessage(R.string.unlock_error_invalid_password)
                            )
                        }
                    }
                }
            } finally {
                // 解封出的主密码在任何路径下用毕立即清零
                masterChars.fill('0')
            }
        }
    }

    /**
     * 完整主密码解锁成功后，若存在 QuickUnlock 登记流程暂存的 PIN，
     * 则将本次主密码封印至 [QuickUnlockPinStore]（Keystore AES-256-GCM 硬件保护），随即清零暂存 PIN。
     */
    private suspend fun bindQuickUnlockCredentialIfPending(masterPassword: CharArray) {
        val pending = pendingQuickUnlockPin ?: return
        val store = quickUnlockPinStore ?: return
        val dbId = activeDatabaseId ?: return
        try {
            store.bindCredential(dbId, masterPassword)
            _uiState.update { it.copy(isQuickUnlockAvailable = true) }
        } catch (ignored: Exception) {
            // 某些设备缺少 Keystore 硬件支持时跳过封印，QuickUnlock 保持不可用
        } finally {
            pending.fill('0')
            pendingQuickUnlockPin = null
        }
    }

    /**
     * 生物识别解锁：结合 AndroidX Biometric 与硬件 Keystore 解封。
     * 缺少宿主 Activity / 硬件依赖 / 活动数据库时一律 fail-closed（不假解锁、不发成功事件）。
     */
    fun unlockWithBiometric(activity: FragmentActivity? = null) {
        if (_uiState.value.isLoading) return

        val storage = biometricCredentialStorage
        val authManager = biometricAuthManager
        val dbId = activeDatabaseId

        if (activity == null || storage == null || authManager == null || dbId == null) {
            // fail-closed：无真实生物识别上下文时不得伪造解锁成功
            _uiState.update {
                it.copy(
                    isLoading = false,
                    unlockMode = UnlockMode.STANDARD,
                    errorMessage = UiMessage(R.string.sec_biometric_auth_failed)
                )
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
            // 系统指纹增删导致密钥作废：清空失效凭据与硬件密钥别名，提示用户使用主密码重新验证
            storage.clearCredential(dbId)
            authManager.deleteKeyForDatabase(dbId)
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
        private const val TAG = "Unlock"
        private const val QUICK_UNLOCK_PIN_LENGTH = 4
    }
}
