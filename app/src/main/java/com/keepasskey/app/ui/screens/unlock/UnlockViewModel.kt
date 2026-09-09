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
import com.keepasskey.app.security.UnlockPasskeyManager
import com.keepasskey.app.ui.model.StringsProvider
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * 单向事件契约（ViewModel -> UI 一次性通知）
 */
sealed interface UnlockEvent {
    data object UnlockSuccess : UnlockEvent
}

/**
 * 解锁页状态容器 ViewModel，遵循谷歌官方 Recommended app architecture 规范。
 * 支持主密码安全解锁（CharArray 显式擦除）与统一快速解锁
 * （强生物识别或设备锁屏凭据经硬件 Keystore 解封，Wave 12 起取代自研 PIN 体系）。
 */
@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    // 依赖在类型上允许为 null 仅用于单测注入空实现；生产 DI 恒注入真实实例
    private val biometricAuthManager: BiometricAuthManager?,
    private val biometricCredentialStorage: BiometricCredentialStorage?,
    private val debugLog: DebugLogBuffer,
    // TASK-21：非 Compose 层文案资源解析通道（生产 DI 注入真实现；单测注入假实现）
    private val stringsProvider: StringsProvider? = null,
    // TASK-18：设备绑定解锁通行密钥（nullable 仅用于单测注入；生产 DI 恒注入真实实例）
    private val unlockPasskeyManager: UnlockPasskeyManager? = null
) : ViewModel() {

    // P3-23：null 时回退空串实现（生产 Hilt 恒注入 StringsProviderModule 真实现）
    private val strings: StringsProvider = stringsProvider ?: StringsProvider { _, _ -> "" }

    private val _uiState = MutableStateFlow(UnlockUiState())
    val uiState: StateFlow<UnlockUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UnlockEvent>()
    val events: SharedFlow<UnlockEvent> = _events.asSharedFlow()

    private var activeDatabaseId: String? = null

    /**
     * 主密码敏感态：仅以 CharArray 驻留 ViewModel 内部（绝不进入 UiState/StateFlow）。
     * 更换内容与解锁完成后立即显式清零。
     */
    private var passwordChars = CharArray(0)

    /**
     * 密钥文件原始字节（修复虚假开关整改）：复合密钥「主密码 + 密钥文件」的第二因子。
     * 仅以 ByteArray 驻留 ViewModel 内部（绝不进入 UiState/StateFlow/String），
     * 取消选择 / 解锁成功 / ViewModel 销毁时显式清零；会话成功后会自行克隆缓存供保存使用。
     */
    private var keyFileData: ByteArray? = null

    init {
        viewModelScope.launch {
            vaultRepository.getDatabases().collect { databases ->
                val active = databases.firstOrNull { it.isActive } ?: databases.firstOrNull()
                if (active != null) {
                    activeDatabaseId = active.id
                    // Wave 12：快速解锁可用性 = 统一封印存储中存在本库凭据（生物识别/设备锁屏凭据共用）
                    val hasSealedCredential = biometricCredentialStorage?.hasEncryptedCredential(active.id) == true
                    _uiState.update {
                        it.copy(
                            databaseName = active.name,
                            databaseStatus = if (active.isRemote) {
                                strings.get(R.string.unlock_db_status_cloud, active.syncType)
                            } else {
                                strings.get(R.string.unlock_db_status_local, active.path)
                            },
                            isQuickUnlockAvailable = hasSealedCredential,
                            hasDatabase = true
                        )
                    }
                } else {
                    activeDatabaseId = null
                    _uiState.update {
                        it.copy(
                            databaseName = "",
                            databaseStatus = "",
                            isQuickUnlockAvailable = false,
                            hasDatabase = false
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
     * 从外部文件导入密码库（在空状态下快速打开已有库）
     */
    fun importExternalDatabase(name: String, path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = vaultRepository.importExternalDatabase(name, path)
            _uiState.update { it.copy(isLoading = false) }
            if (result is KdbxResult.Failure) {
                _uiState.update { it.copy(errorMessage = UiMessage(R.string.vault_op_failed, listOf(result.message))) }
            }
        }
    }

    /**
     * 主密码输入上行（来自 [com.keepasskey.app.ui.components.SecurePasswordField] 的 CharArray 桥接）。
     * 输入的数组仅在本次回调内有效，此处立即复制持有并清零上一份。
     */
    fun onPasswordChangeSecure(password: CharArray) {
        debugLog.info(TAG, "onPasswordChangeSecure: input length=${password.size}")
        passwordChars.fill('0')
        passwordChars = password.copyOf()
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    fun onTogglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    /**
     * 密钥文件选择结果上行（来自解锁页 SAF 选择器，修复虚假开关整改）。
     * 字节在本回调内即被复制持有，调用方（Screen）侧临时数组用毕自行清零。
     */
    fun onKeyFileSelected(data: ByteArray, fileName: String) {
        keyFileData?.fill(0)
        keyFileData = data.copyOf()
        _uiState.update { it.copy(hasKeyFile = true, keyFileName = fileName) }
    }

    /**
     * 取消密钥文件：擦除字节并复位开关状态
     */
    fun clearKeyFile() {
        keyFileData?.fill(0)
        keyFileData = null
        _uiState.update { it.copy(hasKeyFile = false, keyFileName = "") }
    }

    /**
     * 密钥文件读取失败（SAF 流打开/读取异常或超出大小上限）：
     * 显式反馈用户，绝不静默忽略（禁止静默失败纪律）
     */
    fun onKeyFileReadFailed() {
        keyFileData?.fill(0)
        keyFileData = null
        _uiState.update {
            it.copy(hasKeyFile = false, keyFileName = "", errorMessage = UiMessage(R.string.unlock_keyfile_read_failed))
        }
    }

    /** H4-只读整改：切换「只读打开」——开启后本次会话写盘硬拒绝 */
    fun onToggleReadOnly() {
        _uiState.update { it.copy(openReadOnly = !it.openReadOnly) }
    }

    fun switchUnlockMode(mode: UnlockMode) {
        _uiState.update { it.copy(unlockMode = mode, errorMessage = null, infoMessage = null) }
    }

    /**
     * 主密码解锁。
     *
     * @param activity 宿主 Activity。仅用于「首次登记生物识别凭据」时唤起 BiometricPrompt；
     *   为 null 时登记跳过（fail-closed），**不影响本次解锁**。
     */
    fun unlock(activity: FragmentActivity? = null) {
        viewModelScope.launch {
            // P1-10：已选择密钥文件时空密码合法（仅密钥文件解锁，对齐官方 KeePass
            // 解锁框对空密码不添加密码分量的语义）；密码与密钥文件均缺失才拦截
            if (passwordChars.isEmpty() && keyFileData == null) {
                _uiState.update { it.copy(errorMessage = UiMessage(R.string.unlock_error_empty_password)) }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                when (val result = vaultRepository.unlockActiveDatabase(
                    passwordChars,
                    keyFileData = keyFileData,
                    readOnly = _uiState.value.openReadOnly
                )) {
                    is KdbxResult.Success -> {
                        debugLog.info(TAG, "主密码解锁成功")
                        // 快速解锁凭据登记：必须在擦除主密码之前完成（登记需要明文主密码）
                        requestBiometricEnrollment(activity, passwordChars)
                        // 解锁成功后立即擦除驻留的密钥文件字节（会话已克隆缓存供保存使用）
                        keyFileData?.fill(0)
                        keyFileData = null
                        // 解锁成功后立即擦除驻留的主密码字符数组
                        passwordChars.fill('0')
                        passwordChars = CharArray(0)
                        _uiState.update { it.copy(isLoading = false, hasKeyFile = false, keyFileName = "") }
                        _events.emit(UnlockEvent.UnlockSuccess)
                    }
                    is KdbxResult.Failure -> {
                        debugLog.error(TAG, "主密码解锁失败: activeDb=$activeDatabaseId, pwdLen=${passwordChars.size}, keyFileLen=${keyFileData?.size}, err=${result.message}")
                        val errorMsg = if (result.error is com.keepasskey.database.exception.KdbxInvalidCredentialsException) {
                            UiMessage(R.string.unlock_error_invalid_password)
                        } else {
                            UiMessage(R.string.vault_op_failed, listOf(result.message))
                        }
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = errorMsg
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
     * 若开启生物识别且尚未登记，请求一次 BiometricPrompt 授权后封印主凭据。
     *
     * 关键约束：快速解锁硬件密钥以
     * `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL)` 生成——
     * **每次使用**（含加密）都必须先取得一次强生物识别或设备锁屏凭据授权（Wave 12 设备凭据绑定）。原实现直接对未授权 Cipher 调 `doFinal()`，
     * 真机必然抛 `UserNotAuthenticatedException` 并被 `catch (ignored)` 吞掉，
     * 导致「生物识别开关已开、凭据从未入库、下次冷启动无生物入口」的静默功能失效
     * （与 Wave 11 H4 QuickUnlock 同构故障）。
     * 故本方法改为：**先弹 BiometricPrompt 取得授权 Cipher，再在成功回调内执行封印**。
     *
     * 敏感数据设计考量与边界说明 (Wave 3-E P2-18)：
     * 消费 [CharArray]，经 CharBuffer 转为临时 UTF-8 字节并在 finally 块中立即显式清零，
     * 杜绝密码以持久明文字符串穿越硬件加密管线。
     *
     * 失败语义：登记失败（用户取消 / 硬件缺失 / 无宿主 Activity）一律 fail-safe——
     * 仅留痕日志，**不影响本次主密码解锁**。
     */
    private suspend fun requestBiometricEnrollment(
        activity: FragmentActivity?,
        passwordChars: CharArray
    ) {
        // 修复虚假开关整改：复合密钥库（主密码 + 密钥文件）的密钥文件因子无法经
        // Keystore 封印还原，持久化凭据将永远无法独立完成解锁——直接不保存，fail-safe
        if (keyFileData != null) return
        val dbId = activeDatabaseId ?: return
        val storage = biometricCredentialStorage ?: return
        val authManager = biometricAuthManager ?: return
        val settings = settingsRepository.getSettings().first()
        if (!settings.biometricEnabled) return
        // 已登记过则不再重复弹窗（仅首次 + 凭据被清除后重新登记）
        if (storage.hasEncryptedCredential(dbId)) return

        if (activity == null) {
            debugLog.warn(TAG, "生物识别凭据未登记：缺少宿主 Activity，本次跳过（fail-closed，不影响解锁）")
            return
        }

        val encrypted = try {
            val cipher = authManager.prepareEncryptCipher(dbId)
            val charBuffer = CharBuffer.wrap(passwordChars)
            val byteBuffer = StandardCharsets.UTF_8.encode(charBuffer)
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            try {
                val authResult = awaitBiometricAuth(
                    authManager = authManager,
                    activity = activity,
                    cipher = cipher
                )
                when (authResult) {
                    is BiometricResult.Success -> {
                        val authedCipher = authResult.cipher
                        if (authedCipher == null) {
                            debugLog.warn(TAG, "生物识别登记未取得授权 Cipher，跳过封印")
                            null
                        } else {
                            authedCipher.doFinal(bytes)
                        }
                    }
                    is BiometricResult.Cancelled -> {
                        debugLog.info(TAG, "用户取消生物识别登记")
                        null
                    }
                    is BiometricResult.Error -> {
                        debugLog.warn(TAG, "生物识别登记失败: ${authResult.errString}")
                        null
                    }
                    is BiometricResult.Failed -> {
                        debugLog.warn(TAG, "生物识别登记未通过")
                        null
                    }
                }?.let { cipher.iv to it }
            } finally {
                bytes.fill(0)
                byteBuffer.clear()
                if (byteBuffer.hasArray()) {
                    byteBuffer.array().fill(0)
                }
            }
        } catch (e: Exception) {
            // 禁止静默失败：任何异常一律留痕，绝不 catch(ignored)
            debugLog.warn(TAG, "生物识别凭据登记异常: ${e.javaClass.simpleName} - ${e.message}")
            null
        }

        if (encrypted != null) {
            storage.saveEncryptedCredential(dbId, encrypted.first, encrypted.second)
            // TASK-18：随快速解锁凭据登记设备绑定解锁通行密钥（best-effort，失败不影响本次解锁）
            if (unlockPasskeyManager?.enroll(dbId) == false) {
                debugLog.warn(TAG, "解锁通行密钥登记未成功，本次快速解锁回退为纯封印语义")
            }
            _uiState.update { it.copy(isQuickUnlockAvailable = true) }
            debugLog.info(TAG, "生物识别凭据登记成功")
        }
    }

    /**
     * 解锁通行密钥断言验证（TASK-18）。
     *
     * 已登记（含本次功能上线后新登记）：断言未通过一律 fail-closed——清除封印凭据与
     * 通行密钥登记（视为凭据被克隆/篡改），引导用户以主密码完整解锁后重新登记。
     * 兼容策略：本功能上线前登记的旧凭据无通行密钥记录——本次跳过断言（不破坏既有
     * 用户）并后台补登记，下次解锁起强制断言。
     */
    private suspend fun verifyUnlockPasskeyOrCompat(
        storage: BiometricCredentialStorage,
        dbId: String
    ): Boolean {
        val passkeyManager = unlockPasskeyManager ?: return true
        if (!passkeyManager.isEnrolled(dbId)) {
            // 旧凭据兼容通道：后台补登记，下次解锁起强制断言
            debugLog.warn(TAG, "旧快速解锁凭据无通行密钥记录，本次跳过断言并后台补登记")
            passkeyManager.enroll(dbId)
            return true
        }
        val assertion = passkeyManager.assertUnlock(dbId)
        if (assertion == null || !passkeyManager.verifyAndCommit(dbId, assertion)) {
            debugLog.warn(TAG, "解锁通行密钥断言未通过，fail-closed 拒绝快速解锁")
            storage.clearCredential(dbId)
            passkeyManager.clear(dbId)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isQuickUnlockAvailable = false,
                    unlockMode = UnlockMode.STANDARD,
                    errorMessage = UiMessage(R.string.unlock_passkey_verify_failed)
                )
            }
            return false
        }
        return true
    }

    /**
     * 唤起 BiometricPrompt 并挂起等待一次性结果。
     *
     * 超时保护：宿主 Activity 正在销毁等极端情形下 BiometricPrompt 可能不回调，
     * 超时后按失败处理并解除挂起，杜绝协程永久悬挂导致解锁流程卡死。
     */
    private suspend fun awaitBiometricAuth(
        authManager: BiometricAuthManager,
        activity: FragmentActivity,
        cipher: javax.crypto.Cipher
    ): BiometricResult {
        val deferred = CompletableDeferred<BiometricResult>()
        authManager.authenticate(
            activity = activity,
            title = activity.getString(R.string.unlock_biometric_enroll_title),
            subtitle = activity.getString(R.string.unlock_biometric_enroll_subtitle),
            authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS,
            cipher = cipher
        ) { result -> deferred.complete(result) }

        return withTimeoutOrNull(BIOMETRIC_ENROLL_TIMEOUT_MS) { deferred.await() }
            // P3-23：errString 仅经 debugLog 留痕（不外显 UI），属内部诊断文案，保留原样
            ?: BiometricResult.Error(-1, "生物识别登记超时")
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
                authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS,
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
                                // TASK-18：生物识别门控通过后，执行解锁通行密钥断言
                                // （硬件私钥签名 + 公钥验证 + signCount 严格单调防克隆，fail-closed）
                                if (!verifyUnlockPasskeyOrCompat(storage, dbId)) {
                                    return@launch
                                }
                                // P1-13 整改：精确按 CharBuffer.remaining() 拷贝字符，杜绝后备数组尾零残留导致非 ASCII 主密码解锁失败
                                val charBuf = Charsets.UTF_8.decode(ByteBuffer.wrap(decryptedBytes))
                                val chars = CharArray(charBuf.remaining())
                                charBuf.get(chars)
                                try {
                                    when (val unlockResult = vaultRepository.unlockActiveDatabase(chars)) {
                                        is KdbxResult.Success -> {
                                            _uiState.update { it.copy(isLoading = false) }
                                            _events.emit(UnlockEvent.UnlockSuccess)
                                        }
                                        is KdbxResult.Failure -> {
                                            // 生物识别已授权且密文成功解密，却解库失败：
                                            // 极可能是主密码已变更导致入库凭据陈旧（死循环态）。
                                            // 清除陈旧凭据，下次主密码解锁将自动重新登记。
                                            storage.clearCredential(dbId)
                                            _uiState.update {
                                                it.copy(
                                                    isLoading = false,
                                                    isQuickUnlockAvailable = false,
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
            unlockPasskeyManager?.clear(dbId)
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

    override fun onCleared() {
        // 离开解锁页：显式擦除驻留的敏感字节（主密码与密钥文件）
        passwordChars.fill('0')
        passwordChars = CharArray(0)
        keyFileData?.fill(0)
        keyFileData = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "Unlock"
        // 生物识别登记弹窗挂起等待上限：超时按失败处理，避免协程永久悬挂卡死解锁流程
        private const val BIOMETRIC_ENROLL_TIMEOUT_MS = 60_000L
    }
}
