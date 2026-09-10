package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.AppThemePalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 基础偏好（`UserSettings` 仓库直写项）与本页局部 UI 状态（ISSUE-P3-29：自
 * `SettingsViewModel.kt` 拆出，纯结构性拆分，行为零变更）。
 *
 * 承接三类内容：
 * 1. `SettingsRepository` 直写偏好（主题 / 生物识别 / 列表显示 / 标签页等）；
 * 2. 本页局部投影状态（密码库配置 / 自动填充启用 / 自动锁定超时）及其 setter；
 * 3. 调试日志实时缓冲快照与刷新/清除动作。
 */
internal class SettingsPreferencesController(
    private val settingsRepository: SettingsRepository,
    vaultRepository: VaultRepository,
    private val autofillBlocklistStore: AutofillBlocklistStore,
    private val debugLogBuffer: DebugLogBuffer,
    private val scope: CoroutineScope
) {

    private val autofillStateFlow = MutableStateFlow(
        AutofillUiState(
            credentialProviderEnabled = true,
            passkeySupportEnabled = true,
            autofillServiceEnabled = true
        )
    )

    private val databaseConfigStateFlow = MutableStateFlow(
        DatabaseConfigUiState(
            databaseName = "",
            defaultUsername = "",
            encryptionAlgorithm = "ChaCha20-Poly1305 (256-bit)",
            kdfAlgorithm = "Argon2id",
            argon2Iterations = 3L,
            argon2MemoryMb = 64L,
            argon2Parallelism = 4,
            recycleBinEnabled = true,
            tanExpiresOnUse = true,
            checkForDuplicateUuids = true
            // ISSUE-P3-20：childDatabasesCount 字段已整体移除——它原先承载的硬编码 0
            // 会与真实挂载数冲突；真实值改由 childDatabaseCountFlow（核心层 mountedCount）下发
        )
    )

    private val securityTimeoutStateFlow = MutableStateFlow(
        SecurityTimeoutUiState(
            autoLockTimeoutSeconds = 0
        )
    )

    /** 调试日志真实缓冲快照（随刷新/清除动作更新） */
    private val debugLogLinesFlow = MutableStateFlow(debugLogBuffer.snapshot())

    val autofillState: StateFlow<AutofillUiState> = autofillStateFlow
    val databaseConfigState: StateFlow<DatabaseConfigUiState> = databaseConfigStateFlow
    val securityTimeoutState: StateFlow<SecurityTimeoutUiState> = securityTimeoutStateFlow
    val debugLogLines: StateFlow<List<String>> = debugLogLinesFlow

    // ========== TASK-44：自动填充黑名单（真实条目生命周期） ==========

    /**
     * 自动填充黑名单快照（按包名升序）。黑名单不进 `uiState` 的 combine 链——
     * 其更新频率与生命周期独立于设置项，单独下发可避免 5 流 combine 的元组膨胀。
     */
    val autofillBlockedPackages: StateFlow<List<String>> = autofillBlocklistStore.blockedPackages

    /**
     * 将应用加入黑名单。
     * @return true=新增成功；false=包名非法或已在黑名单中（调用方据此如实提示，不谎报成功）
     */
    fun blockAutofillPackage(packageName: String): Boolean = autofillBlocklistStore.add(packageName)

    /** 将应用移出黑名单（删除动作）。@return true=移除成功；false=包名非法或本就不在黑名单中 */
    fun unblockAutofillPackage(packageName: String): Boolean =
        autofillBlocklistStore.remove(packageName)

    init {
        // 动态订阅活动数据库，更新设置页数据库名称
        scope.launch {
            vaultRepository.getDatabases().collect { databases ->
                val active = databases.firstOrNull { it.isActive } ?: databases.firstOrNull()
                databaseConfigStateFlow.update {
                    it.copy(
                        databaseName = active?.name.orEmpty()
                    )
                }
            }
        }
    }

    // ========== 密码库与加密配置 ==========
    fun setEncryptionAlgorithm(algorithm: String) {
        databaseConfigStateFlow.update { it.copy(encryptionAlgorithm = algorithm) }
    }

    fun setKdfAlgorithm(kdf: String) {
        databaseConfigStateFlow.update { it.copy(kdfAlgorithm = kdf) }
    }

    fun setArgon2Parameters(iterations: Long, memoryMb: Long, parallelism: Int) {
        databaseConfigStateFlow.update {
            it.copy(
                argon2Iterations = iterations,
                argon2MemoryMb = memoryMb,
                argon2Parallelism = parallelism
            )
        }
    }

    fun setRecycleBinEnabled(enabled: Boolean) {
        databaseConfigStateFlow.update { it.copy(recycleBinEnabled = enabled) }
    }

    fun setTanExpiresOnUse(enabled: Boolean) {
        databaseConfigStateFlow.update { it.copy(tanExpiresOnUse = enabled) }
    }

    fun setCheckForDuplicateUuids(enabled: Boolean) {
        databaseConfigStateFlow.update { it.copy(checkForDuplicateUuids = enabled) }
    }

    // ========== 自动填充启用开关 ==========
    fun setCredentialProviderEnabled(enabled: Boolean) {
        autofillStateFlow.update { it.copy(credentialProviderEnabled = enabled) }
    }

    fun setPasskeySupportEnabled(enabled: Boolean) {
        autofillStateFlow.update { it.copy(passkeySupportEnabled = enabled) }
    }

    fun setAutofillServiceEnabled(enabled: Boolean) {
        autofillStateFlow.update { it.copy(autofillServiceEnabled = enabled) }
    }

    // ========== 设备解锁与安全 ==========
    fun setAutoLockTimeout(seconds: Int) {
        securityTimeoutStateFlow.update {
            it.copy(autoLockTimeoutSeconds = seconds)
        }
        scope.launch {
            settingsRepository.setAutoLockTimeoutSeconds(seconds)
        }
    }

    fun setClipboardTimeout(seconds: Int) {
        scope.launch {
            settingsRepository.setClipboardTimeout(seconds)
        }
    }

    // ========== 仓库直写偏好 ==========
    fun setAppLanguage(language: AppLanguage) {
        scope.launch {
            settingsRepository.setAppLanguage(language)
        }
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        scope.launch {
            settingsRepository.setThemeMode(themeMode)
        }
    }

    fun setThemePalette(themePalette: AppThemePalette) {
        scope.launch {
            settingsRepository.setThemePalette(themePalette)
        }
    }

    fun setOledBlackOptimization(enabled: Boolean) {
        scope.launch {
            settingsRepository.setOledBlackOptimization(enabled)
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.setDynamicColorEnabled(enabled)
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.setBiometricEnabled(enabled)
        }
    }

    fun setAutoLockBackground(enabled: Boolean) {
        scope.launch {
            settingsRepository.setAutoLockBackground(enabled)
        }
    }

    fun setFlagSecureEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.setFlagSecureEnabled(enabled)
        }
    }

    fun setAutoClearClipboard(enabled: Boolean) {
        scope.launch {
            settingsRepository.setAutoClearClipboard(enabled)
        }
    }

    fun setShowUsernameInList(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowUsernameInList(enabled)
        }
    }

    fun setShowOtpInList(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowOtpInList(enabled)
        }
    }

    fun setShowPasskeyBadge(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowPasskeyBadge(enabled)
        }
    }

    fun setShowUrlInList(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowUrlInList(enabled)
        }
    }

    fun setHideFabOnScroll(enabled: Boolean) {
        scope.launch {
            settingsRepository.setHideFabOnScroll(enabled)
        }
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.setHapticFeedbackEnabled(enabled)
        }
    }

    fun setSyncOnColdStart(enabled: Boolean) {
        scope.launch {
            settingsRepository.setSyncOnColdStart(enabled)
        }
    }

    fun setShowAuthenticatorTab(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowAuthenticatorTab(enabled)
        }
    }

    fun setShowGeneratorTab(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowGeneratorTab(enabled)
        }
    }

    // ========== KP2A 扩展：调试日志（真实进程内缓冲） ==========
    fun refreshDebugLogs() {
        debugLogLinesFlow.value = debugLogBuffer.snapshot()
    }

    fun clearDebugLogs() {
        debugLogBuffer.clear()
        debugLogLinesFlow.value = emptyList()
    }
}

/** 自动填充启用开关的局部投影（原 `SettingsViewModel` 私有嵌套类型，ISSUE-P3-29 上移为同包 internal） */
internal data class AutofillUiState(
    val credentialProviderEnabled: Boolean,
    val passkeySupportEnabled: Boolean,
    val autofillServiceEnabled: Boolean
)

/** 密码库配置的局部投影（原 `SettingsViewModel` 私有嵌套类型，ISSUE-P3-29 上移为同包 internal） */
internal data class DatabaseConfigUiState(
    val databaseName: String,
    val defaultUsername: String,
    val encryptionAlgorithm: String,
    val kdfAlgorithm: String,
    val argon2Iterations: Long,
    val argon2MemoryMb: Long,
    val argon2Parallelism: Int,
    val recycleBinEnabled: Boolean,
    val tanExpiresOnUse: Boolean,
    val checkForDuplicateUuids: Boolean
)

/** 安全超时配置的局部投影（原 `SettingsViewModel` 私有嵌套类型，ISSUE-P3-29 上移为同包 internal） */
internal data class SecurityTimeoutUiState(
    val autoLockTimeoutSeconds: Int
)
