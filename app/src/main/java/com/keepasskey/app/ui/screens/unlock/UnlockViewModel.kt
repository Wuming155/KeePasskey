package com.keepasskey.app.ui.screens.unlock

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
import com.keepasskey.app.security.ThrottleGate
import com.keepasskey.app.security.UnlockPasskeyManager
import com.keepasskey.app.security.UnlockThrottleManager
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * （强生物识别经硬件 Keystore 解封，ISSUE-P1-08 起不再允许锁屏凭据解封）。
 *
 * ISSUE-P3-25 拆分：生物识别状态机（[BiometricUnlockCoordinator]）、首次封印
 * （[BiometricEnrollmentCoordinator]）与密钥文件会话（[KeyFileSessionCoordinator]）已外移为
 * 同包协作者，本类仅保留 API 边界、主密码解锁、失败节流与会话编排（签名与行为不变）。
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
    private val unlockPasskeyManager: UnlockPasskeyManager? = null,
    // ISSUE-P1-04：主密码解锁失败节流管理器（nullable 仅用于单测；生产 DI 恒注入真实实例）
    private val unlockThrottleManager: UnlockThrottleManager? = null,
    // ISSUE-P3-04：密钥文件（复合密钥第二因子）SAF 访问通道。nullable 仅用于单测注入空实现；
    // 生产 DI 经 KeyFileAccessModule 恒注入 SafKeyFileAccess（SAF 读取 + 持久化授权 + 记忆）
    private val keyFileAccess: KeyFileAccess? = null
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

    /** 密钥文件因子会话协调器（ISSUE-P3-25）：承载 `keyFileData` 全部借用/克隆/清零路径 */
    private val keyFileSession = KeyFileSessionCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        keyFileAccess = keyFileAccess,
        debugLog = debugLog
    )

    /** 生物识别解锁协调器（ISSUE-P3-25）：解锁模式/自动唤起/解封与解锁收尾 */
    private val biometricUnlock = BiometricUnlockCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        events = _events,
        vaultRepository = vaultRepository,
        activeDbId = { activeDatabaseId },
        biometricAuthManager = biometricAuthManager,
        biometricCredentialStorage = biometricCredentialStorage,
        unlockPasskeyManager = unlockPasskeyManager,
        debugLog = debugLog
    )

    /** 生物识别凭据登记协调器（ISSUE-P3-25）：解锁成功后的首次封印（best-effort） */
    private val biometricEnrollment = BiometricEnrollmentCoordinator(
        uiState = _uiState,
        settingsRepository = settingsRepository,
        activeDbId = { activeDatabaseId },
        // ISSUE-P2-23：携带密钥文件时一并封印（复合载荷），不再整体跳过登记
        keyFileBytes = { keyFileSession.keyFileData },
        biometricAuthManager = biometricAuthManager,
        biometricCredentialStorage = biometricCredentialStorage,
        unlockPasskeyManager = unlockPasskeyManager,
        debugLog = debugLog
    )

    init {
        viewModelScope.launch {
            vaultRepository.getDatabases().collect { databases ->
                val active = databases.firstOrNull { it.isActive } ?: databases.firstOrNull()
                if (active != null) {
                    activeDatabaseId = active.id
                    // Wave 12：快速解锁可用性 = 统一封印存储中存在本库凭据（ISSUE-P1-08 起仅强生物识别路径）
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
                // ISSUE-P3-01：数据库/封印凭据状态变化后统一重算（与设置流抵达顺序解耦）
                biometricUnlock.refreshUnlockModeAndAutoPrompt()
            }
        }

        viewModelScope.launch {
            val settings = settingsRepository.getSettings().first()
            _uiState.update { state -> state.copy(isBiometricEnabled = settings.biometricEnabled) }
            // ISSUE-P3-01：设置抵达后同样统一重算，不再由「谁先到」决定解锁模式终态
            biometricUnlock.refreshUnlockModeAndAutoPrompt()
        }

        viewModelScope.launch {
            // ISSUE-P3-04：恢复上次成功解锁记忆的密钥文件（受偏好开关与持久化授权双重裁决）
            keyFileSession.restoreRememberedKeyFile()
        }
    }

    /** 解锁页一次性意图透传（ISSUE-P3-01）；守卫条件见 [BiometricUnlockCoordinator]，false = 幂等空操作 */
    fun onBiometricAutoPromptRequested(activity: FragmentActivity?): Boolean =
        biometricUnlock.onBiometricAutoPromptRequested(activity)

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
        // F-25 同族整改（A6 报告「相邻发现」）：主密码**长度**亦属可导出调试缓冲的元数据，
        // 与库 id / 密钥文件长度同口径收敛，只记录事件本身
        debugLog.info(TAG, "onPasswordChangeSecure: input updated")
        passwordChars.fill('0')
        passwordChars = password.copyOf()
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    fun onTogglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    /** 密钥文件 SAF 选取结果上行（ISSUE-P3-04）：读取/擦除/授权/记忆全在 [KeyFileSessionCoordinator] */
    fun onKeyFileSelected(uri: String) = keyFileSession.onKeyFileSelected(uri)

    /** 密钥文件字节上行：字节在本回调内即被复制持有，调用方（Screen）侧临时数组用毕自行清零 */
    fun onKeyFileSelected(data: ByteArray, fileName: String) =
        keyFileSession.onKeyFileSelected(data, fileName)

    /** 取消密钥文件：擦除驻留字节并复位开关状态（不撤销已记忆的记录） */
    fun clearKeyFile() = keyFileSession.clearKeyFile()

    /** 密钥文件读取失败：显式反馈用户，绝不静默忽略（禁止静默失败纪律） */
    fun onKeyFileReadFailed() = keyFileSession.onKeyFileReadFailed()

    /** H4-只读整改：切换「只读打开」——开启后本次会话写盘硬拒绝 */
    fun onToggleReadOnly() {
        _uiState.update { it.copy(openReadOnly = !it.openReadOnly) }
    }

    /** 手动切换解锁模式：登记为显式选择，后续异步状态重算不再覆盖（ISSUE-P3-01） */
    fun switchUnlockMode(mode: UnlockMode) = biometricUnlock.switchUnlockMode(mode)

    /**
     * 主密码解锁。
     *
     * @param activity 宿主 Activity。仅用于「首次登记生物识别凭据」时唤起 BiometricPrompt；
     *   为 null 时登记跳过（fail-closed），**不影响本次解锁**。
     */
    fun unlock(activity: FragmentActivity? = null) {
        viewModelScope.launch {
            val dbId = activeDatabaseId

            // ISSUE-P1-04：失败节流闸门——锁定期内 fail-closed 直接拒绝，绝不触碰 KDF/解密管线，
            // 并同步清零驻留主密码与输入框显示态（避免锁定期间明文滞留堆内存）
            val gate = dbId?.let { unlockThrottleManager?.gate(it) }
            if (gate is ThrottleGate.Locked) {
                wipeMasterPassword()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        throttleFailureCount = gate.failureCount,
                        throttleLockoutRemainingMs = gate.remainingMs,
                        clearPasswordFieldToken = it.clearPasswordFieldToken + 1,
                        errorMessage = lockoutMessage(gate.remainingMs)
                    )
                }
                return@launch
            }

            // P1-10：已选择密钥文件时空密码合法（仅密钥文件解锁，对齐官方 KeePass
            // 解锁框对空密码不添加密码分量的语义）；密码与密钥文件均缺失才拦截
            if (passwordChars.isEmpty() && keyFileSession.keyFileData == null) {
                _uiState.update { it.copy(errorMessage = UiMessage(R.string.unlock_error_empty_password)) }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            // ISSUE-P3-04：本次尝试是否携带密钥文件因子（决定失败语义分型与记忆写入）
            val usedKeyFile = keyFileSession.keyFileData != null

            try {
                when (val result = vaultRepository.unlockActiveDatabase(
                    passwordChars,
                    keyFileData = keyFileSession.keyFileData,
                    readOnly = _uiState.value.openReadOnly
                )) {
                    is KdbxResult.Success -> {
                        debugLog.info(TAG, "主密码解锁成功")
                        // 成功解锁：清零失败计数与锁定状态（节流状态机复位）
                        dbId?.let { unlockThrottleManager?.registerSuccess(it) }
                        // 快速解锁凭据登记：必须在擦除主密码之前完成（登记需要明文主密码）
                        biometricEnrollment.requestBiometricEnrollment(activity, passwordChars)
                        // ISSUE-P3-04：解锁成功 → 按偏好记忆本次使用的密钥文件
                        // （仅 Uri + 显示名；显示名须在下方状态复位前读取）
                        keyFileSession.rememberKeyFileOnSuccess(usedKeyFile, _uiState.value.keyFileName)
                        // 解锁成功后立即擦除驻留的密钥文件字节（会话已克隆缓存供保存使用）
                        keyFileSession.wipe()
                        // 解锁成功后立即擦除驻留的主密码字符数组
                        wipeMasterPassword()
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                hasKeyFile = false,
                                keyFileName = "",
                                throttleFailureCount = 0,
                                throttleLockoutRemainingMs = 0L,
                                // ISSUE-P3-01：主密码解锁成功即用尽本实例的自动唤起机会，
                                // 防止解锁后残留的状态重算把用户重新拉回快速解锁界面/再次弹窗
                                biometricAutoPrompt = BiometricAutoPrompt.CONSUMED
                            )
                        }
                        _events.emit(UnlockEvent.UnlockSuccess)
                    }
                    is KdbxResult.Failure -> {
                        val invalidCredentials =
                            result.error is com.keepasskey.database.exception.KdbxInvalidCredentialsException
                        // F-25 整改：失败留痕口径收敛为「异常类名 + 布尔判定」——原实现把库 id
                        // （activeDb）、密钥文件长度（keyFileLen）与异常原文 message 一并写进可导出的
                        // 调试日志缓冲，泄漏「用户在解锁哪个库 / 是否携带密钥文件及其大小」。
                        // 与仓内其它调用点同一口径（如 SafKeyFileAccess「仅留痕异常类名，不外传异常 message」）。
                        debugLog.error(
                            TAG,
                            "主密码解锁失败: errType=${result.error.javaClass.simpleName}, " +
                                "invalidCreds=$invalidCredentials"
                        )
                        // ISSUE-P1-04：仅「凭据错误」计入暴力破解节流；IO/文件损坏等非认证失败不计入，避免瞬时故障误锁
                        val newGate = if (invalidCredentials) {
                            dbId?.let { unlockThrottleManager?.registerFailure(it) }
                        } else {
                            null
                        }
                        val errorMsg = when {
                            newGate is ThrottleGate.Locked -> lockoutMessage(newGate.remainingMs)
                            // ISSUE-P3-04：携带密钥文件时的凭据失败——KDBX 复合密钥在一次
                            // HMAC 校验中协议上无法判定具体是哪个因子错，故给出并列可行动提示
                            // （不谎称「主密码错」，也不新造底层不存在的分型异常）
                            invalidCredentials && usedKeyFile ->
                                UiMessage(R.string.keyfile_or_password_mismatch)
                            invalidCredentials -> UiMessage(R.string.unlock_error_invalid_password)
                            else -> UiMessage(R.string.vault_op_failed, listOf(result.message))
                        }
                        // ISSUE-P1-04：失败路径无条件清零主密码（不再保留错误密码驻留堆内存）
                        wipeMasterPassword()
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = errorMsg,
                                // 通知输入组件同步擦除显示态，与 VM 清零保持一致（用户须重新输入后重试）
                                clearPasswordFieldToken = it.clearPasswordFieldToken + 1,
                                throttleFailureCount = newGate?.failureCount ?: it.throttleFailureCount,
                                throttleLockoutRemainingMs =
                                    (newGate as? ThrottleGate.Locked)?.remainingMs ?: 0L
                            )
                        }
                    }
                }
            } finally {
                // ISSUE-P1-04：兜底无条件清零——原实现判据为 `if (_uiState.value.isLoading)`，
                // 而失败分支已先将 isLoading 置 false，导致失败态主密码永不清零、持续驻留堆内存。
                // 现改为无条件清零，异常/失败/成功各路径均不残留主密码明文。
                wipeMasterPassword()
            }
        }
    }

    /** 无条件擦除驻留的主密码字符数组（失败/成功/异常/锁定各路径共用） */
    private fun wipeMasterPassword() {
        passwordChars.fill('0')
        passwordChars = CharArray(0)
    }

    /**
     * 将锁定剩余时长映射为本地化 [UiMessage]：≥1 分钟按分钟（向上取整）呈现，否则按秒。
     * 数值计算内联、文案交由字符串资源，杜绝硬编码文案泄漏到代码层。
     */
    private fun lockoutMessage(remainingMs: Long): UiMessage {
        val totalSeconds = (remainingMs + 999L) / 1000L
        return if (totalSeconds >= 60L) {
            val minutes = (totalSeconds + 59L) / 60L
            UiMessage(R.string.unlock_error_locked_out_minutes, listOf(minutes.toInt()))
        } else {
            UiMessage(R.string.unlock_error_locked_out_seconds, listOf(totalSeconds.toInt()))
        }
    }

    /**
     * 生物识别解锁：结合 AndroidX Biometric 与硬件 Keystore 解封。
     * 缺少宿主 Activity / 硬件依赖 / 活动数据库时一律 fail-closed（不假解锁、不发成功事件）。
     * 实现见 [BiometricUnlockCoordinator.unlockWithBiometric]（自动唤起与手动点击共用同一通道）。
     */
    fun unlockWithBiometric(activity: FragmentActivity? = null) =
        biometricUnlock.unlockWithBiometric(activity)

    /**
     * 生物识别结果统一处理入口（ISSUE-P3-01 可测性拆分）：与 Android `BiometricPrompt`
     * 回调解耦，使「成功 / 用户取消 / 单次比对未通过 / 系统错误」四条结果路径可在 JVM 单测中
     * 直接驱动；实现见 [BiometricUnlockCoordinator.handleBiometricResult]。
     */
    internal fun handleBiometricResult(
        result: BiometricResult,
        storage: BiometricCredentialStorage,
        dbId: String,
        sealedCiphertext: ByteArray
    ) = biometricUnlock.handleBiometricResult(result, storage, dbId, sealedCiphertext)

    /**
     * 解封出主密码后的解锁收尾（ISSUE-P3-01 可测性拆分）：字节 → `CharArray` → 既有
     * [VaultRepository.unlockActiveDatabase] 管线，成功即发解锁事件；用毕在 `finally` 中显式清零。
     * 实现见 [BiometricUnlockCoordinator.completeBiometricUnlock]。
     */
    internal suspend fun completeBiometricUnlock(
        decryptedBytes: ByteArray,
        storage: BiometricCredentialStorage,
        dbId: String
    ) = biometricUnlock.completeBiometricUnlock(decryptedBytes, storage, dbId)

    override fun onCleared() {
        // 离开解锁页：显式擦除驻留的敏感字节（主密码与密钥文件）
        passwordChars.fill('0')
        passwordChars = CharArray(0)
        keyFileSession.wipe()
        // ISSUE-P3-04：来源 Uri 属非密钥元数据，此处仅清引用，不撤销已持久化的记忆记录
        super.onCleared()
    }

    companion object {
        private const val TAG = "Unlock"
    }
}
