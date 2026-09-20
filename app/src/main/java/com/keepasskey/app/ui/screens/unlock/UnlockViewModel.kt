package com.keepasskey.app.ui.screens.unlock

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.data.repository.lacksPersistedReadPermission
import com.keepasskey.app.data.repository.persistedReadUriStrings
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.security.UnlockPasskeyManager
import com.keepasskey.app.security.UnlockThrottleManager
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.result.KdbxResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * 同包协作者；ISSUE-P3-188 进一步把主密码缓冲区与解锁流程下沉到 [MasterPasswordUnlockSession]。
 * 本类仅保留 API 边界与协作者编排（签名与行为不变）。
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
    private val keyFileAccess: KeyFileAccess? = null,
    // ISSUE-P3-117：进阶偏好通道（`clearPasswordOnLeave` 的行为消费方）。
    // nullable 仅用于纯 JVM 单测；生产 DI 经 ExtendedSettingsSourceModule 恒注入
    private val extendedSettingsStore: com.keepasskey.app.data.repository.ExtendedSettingsStore? = null,
    // ISSUE-P3-230 AC①：`content://` 库持久化读授权的查询通道。nullable 仅用于纯 JVM 单测；
    // 生产 DI 注入 @ApplicationContext（与 DatabasePickerViewModel / SettingsViewModel 同一既有范式）
    @ApplicationContext private val appContext: Context? = null
) : ViewModel() {

    // P3-23：null 时回退空串实现（生产 Hilt 恒注入 StringsProviderModule 真实现）
    private val strings: StringsProvider = stringsProvider ?: StringsProvider { _, _ -> "" }

    private val _uiState = MutableStateFlow(UnlockUiState())
    val uiState: StateFlow<UnlockUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UnlockEvent>()
    val events: SharedFlow<UnlockEvent> = _events.asSharedFlow()

    private var activeDatabaseId: String? = null

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

    /** 主密码解锁会话（ISSUE-P3-188）：承载主密码缓冲区与「节流闸门 → 解锁 → 收尾/分型」全流程 */
    private val masterPasswordSession = MasterPasswordUnlockSession(
        scope = viewModelScope,
        uiState = _uiState,
        events = _events,
        vaultRepository = vaultRepository,
        unlockThrottleManager = unlockThrottleManager,
        keyFileSession = keyFileSession,
        enrollment = biometricEnrollment,
        debugLog = debugLog,
        activeDbId = { activeDatabaseId }
    )

    init {
        viewModelScope.launch {
            vaultRepository.getDatabases().collect { databases ->
                val active = databases.firstOrNull { it.isActive } ?: databases.firstOrNull()
                if (active != null) {
                    activeDatabaseId = active.id
                    // Wave 12：快速解锁可用性 = 统一封印存储中存在本库凭据（ISSUE-P1-08 起仅强生物识别路径）
                    val hasSealedCredential = biometricCredentialStorage?.hasEncryptedCredential(active.id) == true
                    if (hasSealedCredential) {
                        // ISSUE-P1-22：常驻声明自愈——确认记录残留而封印密钥实际已为硬件落位时清除标记，
                        // 避免「降级声明」在硬件设备上误报（null 探测 = 无法证明，保持原状不误清）
                        refreshQuickUnlockDowngradeFlag(active.id)
                    }
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
            _uiState.update { state ->
                state.copy(
                    isBiometricEnabled = settings.biometricEnabled,
                    // ISSUE-P1-22：常驻声明随确认记录推导（封印时同样会置位，两路汇合幂等）
                    quickUnlockDowngraded = settings.quickUnlockDowngradeAcknowledged
                )
            }
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
     * 从外部文件导入密码库（在空状态下快速打开已有库）。
     *
     * ISSUE-P2-87：导入成功后再按**外层明文头部**（按 KDBX 规范位于认证之前，无需凭据、
     * 不解密载荷）评估 KDF 工作因子，低于本应用建库默认强度时置
     * [UnlockUiState.infoMessage] —— 这是**非阻断提示**：成功仍是成功
     * （**不动** [UnlockUiState.errorMessage]），既不阻断后续解锁，也**不改写任何 KDF 参数**。
     * 判据与文案口径见 `KdbxKdfStrengthAssessor`（只表述「低于本应用建库默认强度」，
     * 不得解读为「不安全 / 已被攻破」）。
     *
     * 达标或**未能评估**（来源不可读 / 头部不可解析）时一并置空：同一槽位若残留上一次导入的
     * 弱因子提示，会变成对**当前**库的误导性告警，故以「不残留」优先。
     *
     * **本页是用户导入后确定停留在的页面**，故弱因子提示落在本页；选择器页的导入路径
     * （`DatabasePickerViewModel.importDatabaseFromSource`）随导入立即退栈、本页即其落点，
     * 其提示责任同样由本页承载。
     */
    fun importExternalDatabase(name: String, path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = vaultRepository.importExternalDatabase(name, path)
            _uiState.update { it.copy(isLoading = false) }
            if (result is KdbxResult.Failure) {
                // 失败路径只置 errorMessage：成功/失败是互斥结论，不得同时给出告警
                _uiState.update { it.copy(errorMessage = UiMessage(R.string.vault_op_failed, listOf(result.message))) }
            } else {
                val belowBaseline = vaultRepository.assessKdfStrength(path)?.isBelowBaseline == true
                _uiState.update {
                    it.copy(
                        infoMessage = when {
                            // ISSUE-P3-230 AC①：`content://` 库未拿到持久化授权时给出**一次性可见提示**
                            // （重启后可能打不开）——优先级高于弱因子提示，因为它直接决定「下次能否打开」
                            lacksPersistedPermission(path) ->
                                UiMessage(R.string.unlock_msg_no_persisted_permission)
                            belowBaseline -> UiMessage(R.string.unlock_msg_weak_kdf)
                            else -> null
                        }
                    )
                }
            }
        }
    }

    /**
     * `ISSUE-P3-230 AC①`：该库路径是否**缺少**持久化读授权。
     *
     * 判定与列表投影同源（`VaultUriPermission` 的纯函数 + 唯一平台查询），避免两处口径漂移。
     * `appContext == null`（纯 JVM 单测）或查询失败一律返回 false——**绝不误报**：
     * 把「查不到」说成「没授权」会凭空制造一条对用户的虚假告警。
     */
    private fun lacksPersistedPermission(path: String): Boolean {
        val context = appContext ?: return false
        val granted = persistedReadUriStrings(context) ?: return false
        return lacksPersistedReadPermission(path, granted)
    }

    /**
     * 主密码输入上行（来自 [com.keepasskey.app.ui.components.SecurePasswordField] 的 CharArray 桥接）。
     * 缓冲区生命周期见 [MasterPasswordUnlockSession.onPasswordChangeSecure]。
     */
    fun onPasswordChangeSecure(password: CharArray) =
        masterPasswordSession.onPasswordChangeSecure(password)

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
     * 主密码解锁（实现在 [MasterPasswordUnlockSession.unlock]：节流闸门 fail-closed、
     * 各路径无条件清零、成功收尾与失败分型）。
     *
     * @param activity 宿主 Activity。仅用于「首次登记生物识别凭据」时唤起 BiometricPrompt；
     *   为 null 时登记跳过（fail-closed），**不影响本次解锁**。
     */
    fun unlock(activity: FragmentActivity? = null) = masterPasswordSession.unlock(activity)

    /**
     * ISSUE-P3-117：离开解锁页（导航离开 / 页面进入后台）时的清理。
     *
     * 开关 `clearPasswordOnLeave`（设置页「离开密码页清空已输入字符」，默认关闭）的行为裁决
     * 在 [MasterPasswordUnlockSession.onScreenLeft]；此处只读进程内共享快照
     * （ExtendedSettingsStore KDoc 声明的「读侧唯一来源」，与设置页写入具备即时可见性），
     * 未注入（纯 JVM 单测）时按默认关闭处理。
     */
    fun onScreenLeft() = masterPasswordSession.onScreenLeft(
        extendedSettingsStore?.settings?.value?.clearPasswordOnLeave ?: false
    )

    /**
     * 生物识别解锁：结合 AndroidX Biometric 与硬件 Keystore 解封。
     * 缺少宿主 Activity / 硬件依赖 / 活动数据库时一律 fail-closed（不假解锁、不发成功事件）。
     * 实现见 [BiometricUnlockCoordinator.unlockWithBiometric]（自动唤起与手动点击共用同一通道）。
     */
    fun unlockWithBiometric(activity: FragmentActivity? = null) =
        biometricUnlock.unlockWithBiometric(activity)

    /**
     * ISSUE-P1-22：解锁页对「软件级快速解锁降级」确认弹窗的用户决定上行
     * （true = 仍要启用并持久化确认记录；false = 不启用）。
     * 无挂起中的确认时幂等空操作。
     */
    fun onQuickUnlockDowngradeDecision(confirmed: Boolean) =
        biometricEnrollment.completeDowngradeConsent(confirmed)

    /**
     * ISSUE-P1-22：注入封印密钥落位探测替身（**仅 JVM 单测使用**——AC③ 要求注入假探测结果；
     * 生产代码不得调用，封印协调器恒走默认真实供给：建钥后探测实际落位）。
     */
    internal fun installSealKeyProvisionForTest(provision: (String) -> SealedKeyProvision?) {
        biometricEnrollment.sealKeyProvisionOverride = provision
    }

    /**
     * ISSUE-P1-22：常驻声明自愈——确认记录残留而封印密钥实际已落位硬件（TEE / StrongBox）时
     * 清除确认记录与声明标记；探测失败（null / UNKNOWN）保持原状（不误清、不误报）。
     */
    private fun refreshQuickUnlockDowngradeFlag(dbId: String) {
        viewModelScope.launch {
            val settings = settingsRepository.getSettings().first()
            if (!settings.quickUnlockDowngradeAcknowledged) return@launch
            val level = biometricAuthManager?.getKeySecurityLevelForDatabase(dbId)
            if (level == KeystoreManager.KeySecurityLevel.STRONGBOX ||
                level == KeystoreManager.KeySecurityLevel.TRUSTED_ENVIRONMENT
            ) {
                settingsRepository.setQuickUnlockDowngradeAcknowledged(false)
                _uiState.update { it.copy(quickUnlockDowngraded = false) }
            }
        }
    }

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
        masterPasswordSession.wipe()
        keyFileSession.wipe()
        // ISSUE-P3-04：来源 Uri 属非密钥元数据，此处仅清引用，不撤销已持久化的记忆记录
        super.onCleared()
    }
}
