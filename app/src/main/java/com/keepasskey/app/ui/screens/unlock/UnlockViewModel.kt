package com.keepasskey.app.ui.screens.unlock

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
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
import com.keepasskey.app.security.UnlockAuthPolicy
import com.keepasskey.app.security.UnlockPasskeyGate
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
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
     * 本次进入解锁页**显式选择**的解锁模式（null = 尚未显式选择，由可用性推导）。
     *
     * ISSUE-P3-01 根因修复：`unlockMode` 的推导依赖「生物识别开关」（设置流）与
     * 「封印凭据存在性」（数据库流）两条独立异步源，抵达顺序不定。原实现在设置流抵达时
     * 用当时的可用性计算一次即定终态，先到者决定结果 → 真机出现「开关已开仍停在主密码界面」。
     * 现改为：本字段记录用户/系统的显式选择（非空即优先），其余情况由
     * [UnlockModePolicy] 在任一异步源变化后统一重算。
     */
    private var unlockModeSelection: UnlockMode? = null

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

    /**
     * 密钥文件来源 SAF Uri（ISSUE-P3-04）：属**非密钥元数据**（可持久化、可比较），
     * 与 UiState 的 `keyFileName` 共同构成「记住上次成功解锁使用的密钥文件」的记录内容。
     * 为空表示本次尝试无可登记来源（未选择 / 字节由调用方直接提供 / 提供方不支持持久授权）
     * ——此时不写入记忆，也不覆盖既有记忆。
     */
    private var keyFileSourceUri: String? = null

    /**
     * 用户是否已在本会话**显式**选择/清除过密钥文件（ISSUE-P3-04）：
     * 记忆恢复为异步 IO 流程，若用户抢先手动选择，则丢弃恢复结果，尊重用户显式选择。
     */
    private var keyFileUserTouched = false

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
                refreshUnlockModeAndAutoPrompt()
            }
        }

        viewModelScope.launch {
            val settings = settingsRepository.getSettings().first()
            _uiState.update { state -> state.copy(isBiometricEnabled = settings.biometricEnabled) }
            // ISSUE-P3-01：设置抵达后同样统一重算，不再由「谁先到」决定解锁模式终态
            refreshUnlockModeAndAutoPrompt()
        }

        viewModelScope.launch {
            // ISSUE-P3-04：恢复上次成功解锁记忆的密钥文件（受偏好开关与持久化授权双重裁决）
            restoreRememberedKeyFile()
        }
    }

    /**
     * 依据「生物识别开关 + 封印凭据 + 活动库」重算解锁模式与自动唤起判定（ISSUE-P3-01）。
     *
     * 关键点：设置流与数据库流是两条独立异步源，抵达顺序不确定；本方法在**任一路径抵达后**
     * 统一调用，使解锁模式与自动唤起判定只取决于两者的最终取值，而非抵达先后。
     * 自动唤起判定另经 [BiometricAutoPromptPolicy] 状态机，`CONSUMED` 为不可逆终态。
     */
    private fun refreshUnlockModeAndAutoPrompt() {
        val state = _uiState.value
        val mode = UnlockModePolicy.resolve(
            biometricEnabled = state.isBiometricEnabled,
            quickUnlockAvailable = state.isQuickUnlockAvailable,
            explicitSelection = unlockModeSelection
        )
        val prompt = BiometricAutoPromptPolicy.next(
            current = state.biometricAutoPrompt,
            biometricEnabled = state.isBiometricEnabled,
            quickUnlockAvailable = state.isQuickUnlockAvailable,
            unlockMode = mode,
            hasDatabase = state.hasDatabase
        )
        _uiState.update { it.copy(unlockMode = mode, biometricAutoPrompt = prompt) }
    }

    /**
     * 回落主密码输入模式（生物识别取消 / 系统错误 / 解封密钥失效等）。
     *
     * 同时把模式登记为**显式选择**，使后续异步状态重算不会把用户重新拖回快速解锁界面；
     * 该「显式选择」不影响一次性自动唤起状态机——后者在消费时已进入 `CONSUMED` 终态，
     * 因此「取消 → 回落主密码 → 再次自动弹出」这条死循环链路在结构上不可达（ISSUE-P3-01 验收 3）。
     */
    private fun fallbackToMasterPasswordMode() {
        unlockModeSelection = UnlockMode.STANDARD
        _uiState.update { it.copy(unlockMode = UnlockMode.STANDARD) }
    }

    /**
     * 解锁页一次性意图：消费「应自动唤起生物识别」的判定，并立即发起一次认证（ISSUE-P3-01）。
     *
     * Screen 只在 `LaunchedEffect(uiState.biometricAutoPrompt)` 中**透传**本调用，不做任何条件判断；
     * 判定条件（开关 / 封印凭据 / 快速解锁模式 / 活动库）与「已消费」守卫全部收在本 ViewModel：
     * - 仅当状态机处于 [BiometricAutoPrompt.PENDING] 时真正发起 → 每次进入解锁页**至多一次**；
     * - 发起前先推进为 [BiometricAutoPrompt.CONSUMED]（不可逆终态）→ 取消 / 失败 / 重组 /
     *   切后台回前台都不可能再次自动弹窗（原实现在 Composable 内直接判断条件，
     *   任何状态抖动都会重复满足条件，这正是重复弹窗/死循环的结构性来源）。
     *
     * 无论本次认证是否最终成功，调用返回后自动唤起机会即已用尽；之后只能由用户显式点击按钮触发。
     *
     * @return true 表示本次调用确实发起了一次自动唤起；false 表示无待消费判定（幂等空操作）
     */
    fun onBiometricAutoPromptRequested(activity: FragmentActivity?): Boolean {
        if (_uiState.value.biometricAutoPrompt != BiometricAutoPrompt.PENDING) return false
        _uiState.update { it.copy(biometricAutoPrompt = BiometricAutoPrompt.CONSUMED) }
        unlockWithBiometric(activity)
        return true
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
     * 密钥文件 SAF 选取结果上行（ISSUE-P3-04）：组合层只上传 Uri，
     * 读取上限、流关闭、缓冲擦除、持久化读授权与记忆登记全部由 [KeyFileAccess] 在 IO 线程承担
     * （原实现在 Composable 内自行读流，规则上属「Screen 写业务逻辑」）。
     */
    fun onKeyFileSelected(uri: String) {
        viewModelScope.launch {
            val access = keyFileAccess
            if (access == null) {
                debugLog.warn(TAG, "密钥文件读取通道不可用，拒绝以未读取的字节参与解锁")
                onKeyFileReadFailed()
                return@launch
            }
            when (val outcome = access.read(uri)) {
                is KeyFileReadResult.Success -> {
                    try {
                        adoptKeyFile(outcome.bytes, outcome.displayName)
                    } finally {
                        // 移交后立即擦除读取结果（VM 内部持独立副本）
                        outcome.bytes.fill(0)
                    }
                    keyFileUserTouched = true
                    trackKeyFileSource(uri)
                }
                // 「读不到」分型：空文件 / 超限 / 流异常一律显式反馈，绝不静默忽略
                KeyFileReadResult.Empty,
                KeyFileReadResult.TooLarge,
                KeyFileReadResult.Unreadable -> onKeyFileReadFailed()
            }
        }
    }

    /**
     * 密钥文件字节上行（来自解锁页 SAF 选择器，修复虚假开关整改）。
     * 字节在本回调内即被复制持有，调用方（Screen）侧临时数组用毕自行清零。
     * 本入口不携带来源 Uri（ISSUE-P3-04 记忆登记需经 [onKeyFileSelected] 的 Uri 通道）。
     */
    fun onKeyFileSelected(data: ByteArray, fileName: String) {
        adoptKeyFile(data, fileName)
        keyFileUserTouched = true
    }

    /** 采纳密钥文件字节：覆盖驻留副本（旧副本显式清零）并同步「已选择 + 显示名」语义 */
    private fun adoptKeyFile(data: ByteArray, fileName: String) {
        keyFileData?.fill(0)
        keyFileData = data.copyOf()
        _uiState.update {
            it.copy(hasKeyFile = true, keyFileName = fileName, errorMessage = null)
        }
    }

    /**
     * 取消密钥文件：擦除驻留字节并复位开关状态。
     *
     * ISSUE-P3-04 语义裁决：取消仅作用于**本次解锁尝试**，不撤销已记忆的记录
     * （记忆的写入/清除以「成功解锁实际使用的因子」为准），故此处不清除偏好中的 Uri。
     */
    fun clearKeyFile() {
        keyFileData?.fill(0)
        keyFileData = null
        keyFileSourceUri = null
        keyFileUserTouched = true
        _uiState.update { it.copy(hasKeyFile = false, keyFileName = "") }
    }

    /**
     * 密钥文件读取失败（SAF 流打开/读取异常或超出大小上限）：
     * 显式反馈用户，绝不静默忽略（禁止静默失败纪律）
     */
    fun onKeyFileReadFailed() {
        keyFileData?.fill(0)
        keyFileData = null
        keyFileSourceUri = null
        keyFileUserTouched = true
        _uiState.update {
            it.copy(hasKeyFile = false, keyFileName = "", errorMessage = UiMessage(R.string.unlock_keyfile_read_failed))
        }
    }

    /**
     * 登记密钥文件来源并按偏好申请持久化读授权（ISSUE-P3-04）。
     *
     * - 偏好关闭：不申请持久授权、不登记来源（最小权限原则：不记忆就不扩大持久授权面）；
     * - 提供方不支持持久化授权（[KeyFileAccess.persistReadPermission] 返回 false）：
     *   优雅降级——本次解锁仍可用（字节已在内存），但不记忆并给出可理解提示
     *   （否则下次冷启动恢复必然失败，用户无从理解）。
     */
    private fun trackKeyFileSource(uri: String) {
        val access = keyFileAccess ?: return
        viewModelScope.launch {
            val rememberEnabled = access.isRememberEnabled()
            if (!KeyFileRememberPolicy.shouldTrackSource(rememberEnabled, uri)) {
                keyFileSourceUri = null
                return@launch
            }
            if (access.persistReadPermission(uri)) {
                keyFileSourceUri = uri
            } else {
                keyFileSourceUri = null
                _uiState.update {
                    it.copy(infoMessage = UiMessage(R.string.keyfile_permission_not_persisted))
                }
            }
        }
    }

    /**
     * 恢复上次成功解锁记忆的密钥文件（ISSUE-P3-04）。
     *
     * 裁决链：[KeyFileRememberPolicy.canRestore]（偏好开启 + 记录完整 + 仍持有持久化读授权）
     * → 真实读取成功才落到 UiState。任一环节不满足即**静默降级为「未记住」**并清除记录：
     * 恢复由系统在进入解锁页时自动发起，用户未做任何操作，故不弹错误、不阻断解锁，
     * 仅记录**非敏感**日志（偏好/授权布尔值，不含 Uri 与显示名）。
     */
    private suspend fun restoreRememberedKeyFile() {
        val access = keyFileAccess ?: return
        if (keyFileUserTouched) return
        val rememberEnabled = access.isRememberEnabled()
        val remembered = access.loadRemembered() ?: return
        val permissionValid = access.hasPersistedReadPermission(remembered.uri)
        if (!KeyFileRememberPolicy.canRestore(rememberEnabled, remembered, permissionValid)) {
            debugLog.info(
                TAG,
                "密钥文件记忆不可用（偏好=$rememberEnabled，持久授权有效=$permissionValid），静默降级为未记住"
            )
            access.forget()
            return
        }
        when (val outcome = access.read(remembered.uri)) {
            is KeyFileReadResult.Success -> {
                if (keyFileUserTouched) {
                    // 读取期间用户已显式选择其它密钥文件：丢弃恢复结果，尊重用户选择
                    outcome.bytes.fill(0)
                    return
                }
                val displayName = outcome.displayName.ifBlank { remembered.displayName }
                try {
                    adoptKeyFile(outcome.bytes, displayName)
                } finally {
                    outcome.bytes.fill(0)
                }
                keyFileSourceUri = remembered.uri
                _uiState.update {
                    it.copy(
                        infoMessage = UiMessage(
                            R.string.keyfile_restored_from_memory,
                            listOf(displayName)
                        )
                    )
                }
            }
            else -> {
                debugLog.warn(TAG, "记忆的密钥文件已不可读（授权有效但读取失败），清除记录并降级为未记住")
                access.forget()
            }
        }
    }

    /**
     * 解锁成功后按偏好记忆密钥文件（ISSUE-P3-04）。
     *
     * - 偏好开启 + 本次使用且可定位来源的密钥文件 → 记住 Uri 与显示名（非密钥元数据）；
     * - 偏好开启 + 本次未使用密钥文件 → 清除旧记录：标准解锁在「未携带密钥文件」下成功，
     *   只可能是密码库本身不含密钥文件因子（携带不匹配的密钥文件必然凭据失败），
     *   此时旧记录归属其它库或已失效，留存会误导下次解锁；
     * - 偏好关闭 → 一并清除，不残留任何密钥文件元数据。
     *
     * 全程**不落任何密钥字节**：字节仍只驻留单次解锁尝试内，成功后立即清零。
     */
    private suspend fun rememberKeyFileOnSuccess(usedKeyFile: Boolean, displayName: String) {
        val access = keyFileAccess ?: return
        if (!access.isRememberEnabled()) {
            access.forget()
            return
        }
        val sourceUri = keyFileSourceUri
        if (usedKeyFile && !sourceUri.isNullOrBlank()) {
            access.remember(sourceUri, displayName)
        } else if (!usedKeyFile) {
            access.forget()
        }
    }

    /** H4-只读整改：切换「只读打开」——开启后本次会话写盘硬拒绝 */
    fun onToggleReadOnly() {
        _uiState.update { it.copy(openReadOnly = !it.openReadOnly) }
    }

    /** 手动切换解锁模式：登记为显式选择，后续异步状态重算不再覆盖（ISSUE-P3-01） */
    fun switchUnlockMode(mode: UnlockMode) {
        unlockModeSelection = mode
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
            if (passwordChars.isEmpty() && keyFileData == null) {
                _uiState.update { it.copy(errorMessage = UiMessage(R.string.unlock_error_empty_password)) }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            // ISSUE-P3-04：本次尝试是否携带密钥文件因子（决定失败语义分型与记忆写入）
            val usedKeyFile = keyFileData != null

            try {
                when (val result = vaultRepository.unlockActiveDatabase(
                    passwordChars,
                    keyFileData = keyFileData,
                    readOnly = _uiState.value.openReadOnly
                )) {
                    is KdbxResult.Success -> {
                        debugLog.info(TAG, "主密码解锁成功")
                        // 成功解锁：清零失败计数与锁定状态（节流状态机复位）
                        dbId?.let { unlockThrottleManager?.registerSuccess(it) }
                        // 快速解锁凭据登记：必须在擦除主密码之前完成（登记需要明文主密码）
                        requestBiometricEnrollment(activity, passwordChars)
                        // ISSUE-P3-04：解锁成功 → 按偏好记忆本次使用的密钥文件
                        // （仅 Uri + 显示名；显示名须在下方状态复位前读取）
                        rememberKeyFileOnSuccess(usedKeyFile, _uiState.value.keyFileName)
                        // 解锁成功后立即擦除驻留的密钥文件字节（会话已克隆缓存供保存使用）
                        keyFileData?.fill(0)
                        keyFileData = null
                        keyFileSourceUri = null
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
                        debugLog.error(TAG, "主密码解锁失败: activeDb=$dbId, invalidCreds=$invalidCredentials, keyFileLen=${keyFileData?.size}, err=${result.message}")
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
     * 若开启生物识别且尚未登记，请求一次 BiometricPrompt 授权后封印主凭据。
     *
     * 关键约束（ISSUE-P1-08）：快速解锁硬件密钥以
     * `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` 生成（**仅强生物识别**授权，
     * 设备锁屏凭据不再可解封）——**每次使用**（含加密）都必须先取得一次 Class 3 强生物识别授权。
     * 原实现直接对未授权 Cipher 调 `doFinal()`，
     * 真机必然抛 `UserNotAuthenticatedException` 并被 `catch (ignored)` 吞掉，
     * 导致「生物识别开关已开、凭据从未入库、下次冷启动无生物入口」的静默功能失效
     * （与 Wave 11 H4 QuickUnlock 同构故障）。
     * 故本方法改为：**先弹 BiometricPrompt 取得授权 Cipher，再在成功回调内执行封印**。
     * 封印前另经 [UnlockAuthPolicy.canSeal] 校验设备具备「硬件存在且已录入」的强生物识别，
     * 弱凭据设备禁用封印（fail-closed），绝不降级到锁屏凭据路径。
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

        // ISSUE-P1-08：封印闸门——设备须具备「硬件存在且已录入」的 Class 3 强生物识别，
        // 无强生物（含未录入）时禁用封印（fail-closed），不降级到弱锁屏凭据路径
        val biometricStatus = authManager.canAuthenticate(
            activity,
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )
        if (!UnlockAuthPolicy.canSeal(biometricStatus)) {
            debugLog.warn(TAG, "生物识别凭据未登记：设备无可用强生物识别（$biometricStatus），禁用封印（fail-closed）")
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
     * 解锁通行密钥断言验证（TASK-18 / ISSUE-P1-09 fail-closed 化）。
     *
     * 每次快速解锁生成一次性随机 challenge，要求硬件私钥（已绑定强生物识别认证
     * 时间窗）对 AuthenticatorData || SHA-256(clientDataJSON) 签名，并本地复核
     * clientDataJSON 规范性（type/challenge/origin）、rpIdHash 归属与 signCount
     * 严格单调。
     *
     * 未登记（记录被删/被篡改/未登记，[UnlockPasskeyGate.NotEnrolled]）与硬件
     * 签名失败一律 fail-closed——清除封印凭据与通行密钥登记（视为凭据被克隆/
     * 篡改），引导用户以主密码完整解锁后重新登记。原「旧凭据兼容通道」（未登记
     * 即跳过断言并后台补登记）已移除：任何能写应用私有数据者删除 3 个 key 即可
     * 一步绕过反克隆断言，静默放行语义不可接受。
     */
    private suspend fun verifyUnlockPasskey(
        storage: BiometricCredentialStorage,
        dbId: String
    ): Boolean {
        val passkeyManager = unlockPasskeyManager ?: return true
        val challenge = passkeyManager.newChallenge()
        val gate = passkeyManager.assertUnlock(dbId, challenge)
        val assertion = (gate as? UnlockPasskeyGate.AssertionReady)?.assertion
        if (assertion == null || !passkeyManager.verifyAndCommit(dbId, assertion, challenge)) {
            debugLog.warn(TAG, "解锁通行密钥断言不可用/未通过（gate=$gate），fail-closed 拒绝快速解锁")
            storage.clearCredential(dbId)
            passkeyManager.clear(dbId)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isQuickUnlockAvailable = false,
                    errorMessage = UiMessage(R.string.unlock_passkey_verify_failed)
                )
            }
            fallbackToMasterPasswordMode()
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
        cipher: Cipher
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
            // ISSUE-P3-14：超时结果同样只承载「错误码 + 内部诊断标识」，
            // 用户可见文案一律由消费侧按错误码映射资源，不在此写死任何语言文案
            ?: BiometricResult.Error(
                BiometricAuthManager.ERROR_AUTH_TIMEOUT,
                BiometricAuthManager.AUTH_TIMEOUT_DIAGNOSTIC
            )
    }

    /**
     * 生物识别解锁：结合 AndroidX Biometric 与硬件 Keystore 解封。
     * 缺少宿主 Activity / 硬件依赖 / 活动数据库时一律 fail-closed（不假解锁、不发成功事件）。
     *
     * 本方法只负责「发起」认证（Android 依赖仅存于此）；结果处理统一收敛到
     * [handleBiometricResult]，以便 JVM 单测直接驱动成功/取消/失败三条路径。
     * 自动唤起与手动点击共用本方法，**不新造任何解密/解锁通道**。
     */
    fun unlockWithBiometric(activity: FragmentActivity? = null) {
        if (_uiState.value.isLoading) return

        val storage = biometricCredentialStorage
        val authManager = biometricAuthManager
        val dbId = activeDatabaseId

        if (activity == null || storage == null || authManager == null || dbId == null) {
            // fail-closed：无真实生物识别上下文时不得伪造解锁成功
            _uiState.update {
                it.copy(isLoading = false, errorMessage = UiMessage(R.string.sec_biometric_auth_failed))
            }
            fallbackToMasterPasswordMode()
            return
        }

        val cred = storage.getEncryptedCredential(dbId)
        if (cred == null) {
            _uiState.update {
                it.copy(
                    isQuickUnlockAvailable = false,
                    errorMessage = UiMessage(R.string.unlock_error_empty_password)
                )
            }
            fallbackToMasterPasswordMode()
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
            ) { result -> handleBiometricResult(result, storage, dbId, cred.second) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // 系统指纹增删导致密钥作废：清空失效凭据与硬件密钥别名，提示用户使用主密码重新验证
            storage.clearCredential(dbId)
            unlockPasskeyManager?.clear(dbId)
            authManager.deleteKeyForDatabase(dbId)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isQuickUnlockAvailable = false,
                    errorMessage = UiMessage(R.string.sec_biometric_key_invalidated)
                )
            }
            fallbackToMasterPasswordMode()
        } catch (e: Exception) {
            // 禁止静默失败：留痕异常类型（不外传裸异常 message），并由结果路径统一提示
            debugLog.warn(TAG, "生物识别解锁启动异常: ${e.javaClass.simpleName}")
            _uiState.update {
                it.copy(isLoading = false, errorMessage = UiMessage(R.string.sec_biometric_auth_failed))
            }
            fallbackToMasterPasswordMode()
        }
    }

    /**
     * 生物识别结果统一处理入口（ISSUE-P3-01 可测性拆分）：
     * 与 Android `BiometricPrompt` 回调解耦，使「成功 / 用户取消 / 单次比对未通过 / 系统错误」
     * 四条结果路径可在 JVM 单测中直接驱动（硬件与 Activity 依赖仅保留在 [unlockWithBiometric] 启动侧）。
     *
     * 回退语义（ISSUE-P3-01 验收 3）：
     * - 用户主动取消 → 清除提示并回落主密码输入框（不再自动重试）；
     * - 系统错误（含完整性风险态）→ 按错误码映射资源文案后回落主密码输入框；
     * - 单次比对未通过（[BiometricResult.Failed]）→ 系统弹窗仍驻留等待重试，
     *   故保留快速解锁界面，仅置失败提示（自动唤起机会已用尽，不会重复弹窗）。
     */
    internal fun handleBiometricResult(
        result: BiometricResult,
        storage: BiometricCredentialStorage,
        dbId: String,
        sealedCiphertext: ByteArray
    ) {
        when (result) {
            is BiometricResult.Success -> startBiometricUnlock(result.cipher, storage, dbId, sealedCiphertext)
            is BiometricResult.Cancelled -> {
                _uiState.update { it.copy(isLoading = false, errorMessage = null, infoMessage = null) }
                fallbackToMasterPasswordMode()
            }
            is BiometricResult.Error -> {
                // ISSUE-P3-14：errString 为内部诊断标识，仅落日志；用户可见文案经错误码映射到已资源化字符串
                debugLog.warn(TAG, "生物识别认证失败: code=${result.errorCode}")
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = BiometricFailureMessagePolicy.of(result))
                }
                fallbackToMasterPasswordMode()
            }
            is BiometricResult.Failed -> {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = UiMessage(R.string.sec_biometric_auth_failed))
                }
            }
        }
    }

    /**
     * 生物识别授权通过后的解封阶段：以授权 Cipher 解封封印凭据，再交 [completeBiometricUnlock] 走既有解锁管线。
     * 解封失败（密钥被生物录入变更吊销 / 旧密钥迁移重建等）一律清除陈旧凭据并回落主密码，
     * 杜绝「永远解不开又永不重登记」的死循环态。
     */
    private fun startBiometricUnlock(
        authedCipher: Cipher?,
        storage: BiometricCredentialStorage,
        dbId: String,
        sealedCiphertext: ByteArray
    ) {
        if (authedCipher == null) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        viewModelScope.launch {
            try {
                val decryptedBytes = authedCipher.doFinal(sealedCiphertext)
                // TASK-18 / ISSUE-P1-09：生物识别门控通过后，执行解锁通行密钥断言
                // （随机 challenge + clientDataJSON + 认证绑定私钥签名 + signCount
                // 严格单调防克隆；记录缺失/被删与签名失败均 fail-closed）
                if (verifyUnlockPasskey(storage, dbId)) {
                    completeBiometricUnlock(decryptedBytes, storage, dbId)
                }
            } catch (e: Exception) {
                // ISSUE-P1-08 迁移容错：密钥经生物录入变更吊销（KeyPermanentlyInvalidated）
                // 或旧「BIOMETRIC_STRONG|DEVICE_CREDENTIAL」密钥迁移重建后，
                // 旧封印密文解密必然失败（AEADBadTagException 等）——清除陈旧凭据，
                // 下次主密码解锁自动重新封印，杜绝「永远解不开又永不重登记」的死循环态
                debugLog.warn(TAG, "生物识别解封失败: ${e.javaClass.simpleName}")
                storage.clearCredential(dbId)
                unlockPasskeyManager?.clear(dbId)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isQuickUnlockAvailable = false,
                        errorMessage = UiMessage(R.string.unlock_error_invalid_password)
                    )
                }
            }
        }
    }

    /**
     * 解封出主密码后的解锁收尾（ISSUE-P3-01 可测性拆分）：
     * 字节 → `CharArray` → 既有 [VaultRepository.unlockActiveDatabase] 管线，成功即发解锁事件。
     * 全程 `ByteArray` / `CharArray` 承载，用毕在 `finally` 中显式清零，绝不落地为 `String`。
     */
    internal suspend fun completeBiometricUnlock(
        decryptedBytes: ByteArray,
        storage: BiometricCredentialStorage,
        dbId: String
    ) {
        // P1-13 整改：精确按 CharBuffer.remaining() 拷贝字符，杜绝后备数组尾零残留导致非 ASCII 主密码解锁失败
        val charBuf = Charsets.UTF_8.decode(ByteBuffer.wrap(decryptedBytes))
        val chars = CharArray(charBuf.remaining())
        charBuf.get(chars)
        try {
            when (val unlockResult = vaultRepository.unlockActiveDatabase(chars)) {
                is KdbxResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            // ISSUE-P3-01：生物识别解锁成功同样用尽自动唤起机会（终态不可逆）
                            biometricAutoPrompt = BiometricAutoPrompt.CONSUMED
                        )
                    }
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
    }

    override fun onCleared() {
        // 离开解锁页：显式擦除驻留的敏感字节（主密码与密钥文件）
        passwordChars.fill('0')
        passwordChars = CharArray(0)
        keyFileData?.fill(0)
        keyFileData = null
        // ISSUE-P3-04：来源 Uri 属非密钥元数据，此处仅清引用，不撤销已持久化的记忆记录
        keyFileSourceUri = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "Unlock"
        // 生物识别登记弹窗挂起等待上限：超时按失败处理，避免协程永久悬挂卡死解锁流程
        private const val BIOMETRIC_ENROLL_TIMEOUT_MS = 60_000L
    }
}
