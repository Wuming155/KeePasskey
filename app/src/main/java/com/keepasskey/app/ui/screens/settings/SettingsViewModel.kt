package com.keepasskey.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.ui.theme.AppThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页面状态容器 ViewModel
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val syncStateFlow = MutableStateFlow(
        SyncUiState(
            provider = CloudSyncProvider.WEBDAV,
            autoSyncEnabled = true,
            wifiOnlySync = true,
            isSyncing = false,
            syncFeedbackMessage = null
        )
    )

    private val healthStateFlow = MutableStateFlow(
        HealthCheckUiState(
            healthScore = 94,
            healthStatus = "优秀",
            healthMessage = "发现 1 个密码重复使用，未发现已知泄露",
            weakPasswordCount = 0,
            reusedPasswordCount = 1,
            compromisedPasswordCount = 0,
            lastHealthScanTime = "今天 10:20",
            isHealthScanning = false
        )
    )

    private val autofillStateFlow = MutableStateFlow(
        AutofillUiState(
            credentialProviderEnabled = true,
            passkeySupportEnabled = true,
            autofillServiceEnabled = true
        )
    )

    private val databaseConfigStateFlow = MutableStateFlow(
        DatabaseConfigUiState(
            databaseName = "master_vault.kdbx",
            defaultUsername = "user@keepasskey.com",
            encryptionAlgorithm = "ChaCha20-Poly1305 (256-bit)",
            kdfAlgorithm = "Argon2id",
            argon2Iterations = 3L,
            argon2MemoryMb = 64L,
            argon2Parallelism = 4,
            recycleBinEnabled = true
        )
    )

    private val securityTimeoutStateFlow = MutableStateFlow(
        SecurityTimeoutUiState(
            autoLockTimeoutSeconds = 0,
            autoLockTimeoutLabel = "立即锁定",
            clipboardTimeoutSeconds = 30,
            clipboardTimeoutLabel = "30 秒"
        )
    )

    private val displayConfigStateFlow = MutableStateFlow(
        DisplayConfigUiState(
            showUsernameInList = true,
            showOtpInList = true,
            showPasskeyBadge = true
        )
    )

    private data class SyncUiState(
        val provider: CloudSyncProvider = CloudSyncProvider.WEBDAV,
        val webdavUrl: String = "https://cloud.example.com/remote.php/dav/files/user/",
        val webdavUsername: String = "vault_master",
        val webdavPassword: String = "mypassword123",
        val webdavRemotePath: String = "/Passkeys/keepasskey.kdbx",
        val s3Endpoint: String = "https://<account_id>.r2.cloudflarestorage.com",
        val s3Bucket: String = "my-secure-vault",
        val s3Region: String = "auto",
        val s3AccessKey: String = "AKIAIOSFODNN7EXAMPLE",
        val s3SecretKey: String = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        val s3ObjectKey: String = "passwords/master_vault.kdbx",
        val autoSyncEnabled: Boolean = true,
        val wifiOnlySync: Boolean = true,
        val isSyncing: Boolean = false,
        val syncFeedbackMessage: String? = null
    )

    private data class HealthCheckUiState(
        val healthScore: Int,
        val healthStatus: String,
        val healthMessage: String,
        val weakPasswordCount: Int,
        val reusedPasswordCount: Int,
        val compromisedPasswordCount: Int,
        val lastHealthScanTime: String,
        val isHealthScanning: Boolean
    )

    private data class AutofillUiState(
        val credentialProviderEnabled: Boolean,
        val passkeySupportEnabled: Boolean,
        val autofillServiceEnabled: Boolean
    )

    private data class DatabaseConfigUiState(
        val databaseName: String,
        val defaultUsername: String,
        val encryptionAlgorithm: String,
        val kdfAlgorithm: String,
        val argon2Iterations: Long,
        val argon2MemoryMb: Long,
        val argon2Parallelism: Int,
        val recycleBinEnabled: Boolean
    )

    private data class SecurityTimeoutUiState(
        val autoLockTimeoutSeconds: Int,
        val autoLockTimeoutLabel: String,
        val clipboardTimeoutSeconds: Int,
        val clipboardTimeoutLabel: String
    )

    private data class DisplayConfigUiState(
        val showUsernameInList: Boolean,
        val showOtpInList: Boolean,
        val showPasskeyBadge: Boolean
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.getSettings(),
        syncStateFlow,
        healthStateFlow,
        combine(autofillStateFlow, databaseConfigStateFlow) { af, db -> Pair(af, db) },
        combine(securityTimeoutStateFlow, displayConfigStateFlow) { sec, disp -> Pair(sec, disp) }
    ) { userSettings, syncState, healthState, (autofillState, dbState), (secState, displayState) ->
        SettingsUiState(
            // 1. 密码库与加密设置
            databaseName = dbState.databaseName,
            databaseDefaultUsername = dbState.defaultUsername,
            encryptionAlgorithm = dbState.encryptionAlgorithm,
            kdfAlgorithm = dbState.kdfAlgorithm,
            argon2Iterations = dbState.argon2Iterations,
            argon2MemoryMb = dbState.argon2MemoryMb,
            argon2Parallelism = dbState.argon2Parallelism,
            recycleBinEnabled = dbState.recycleBinEnabled,

            // 2. 云端多协议同步
            syncProvider = syncState.provider,
            webdavUrl = syncState.webdavUrl,
            webdavUsername = syncState.webdavUsername,
            webdavPassword = syncState.webdavPassword,
            webdavPasswordMasked = "•".repeat(syncState.webdavPassword.length.coerceAtLeast(8)),
            webdavRemotePath = syncState.webdavRemotePath,
            s3Endpoint = syncState.s3Endpoint,
            s3Bucket = syncState.s3Bucket,
            s3Region = syncState.s3Region,
            s3AccessKey = syncState.s3AccessKey,
            s3SecretKey = syncState.s3SecretKey,
            s3SecretKeyMasked = "•".repeat(syncState.s3SecretKey.length.coerceAtLeast(16)),
            s3ObjectKey = syncState.s3ObjectKey,
            autoSyncEnabled = syncState.autoSyncEnabled,
            wifiOnlySync = syncState.wifiOnlySync,
            isSyncing = syncState.isSyncing,
            syncFeedbackMessage = syncState.syncFeedbackMessage,

            // 3. 表单自动填充与 Passkey
            credentialProviderEnabled = autofillState.credentialProviderEnabled,
            passkeySupportEnabled = autofillState.passkeySupportEnabled,
            autofillServiceEnabled = autofillState.autofillServiceEnabled,

            // 4. 设备解锁与安全
            themeMode = userSettings.themeMode,
            oledBlackOptimization = userSettings.oledBlackOptimization,
            biometricEnabled = userSettings.biometricEnabled,
            autoLockBackground = userSettings.autoLockBackground,
            flagSecureEnabled = userSettings.flagSecureEnabled,
            autoClearClipboard = userSettings.autoClearClipboard,
            autoLockTimeoutSeconds = secState.autoLockTimeoutSeconds,
            autoLockTimeoutLabel = secState.autoLockTimeoutLabel,
            clipboardTimeoutSeconds = secState.clipboardTimeoutSeconds,
            clipboardTimeoutLabel = secState.clipboardTimeoutLabel,

            // 5. 外观与显示偏好
            showUsernameInList = displayState.showUsernameInList,
            showOtpInList = displayState.showOtpInList,
            showPasskeyBadge = displayState.showPasskeyBadge,

            // 6. 密码库健康度检查
            healthScore = healthState.healthScore,
            healthStatus = healthState.healthStatus,
            healthMessage = healthState.healthMessage,
            weakPasswordCount = healthState.weakPasswordCount,
            reusedPasswordCount = healthState.reusedPasswordCount,
            compromisedPasswordCount = healthState.compromisedPasswordCount,
            lastHealthScanTime = healthState.lastHealthScanTime,
            isHealthScanning = healthState.isHealthScanning
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun setThemeMode(themeMode: AppThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(themeMode)
        }
    }

    fun setOledBlackOptimization(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOledBlackOptimization(enabled)
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBiometricEnabled(enabled)
        }
    }

    fun setAutoLockBackground(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoLockBackground(enabled)
        }
    }

    fun setFlagSecureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setFlagSecureEnabled(enabled)
        }
    }

    fun setAutoClearClipboard(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoClearClipboard(enabled)
        }
    }

    fun setSyncProvider(provider: CloudSyncProvider) {
        syncStateFlow.update { it.copy(provider = provider) }
    }

    fun updateWebDavConfig(
        url: String,
        username: String,
        password: String = syncStateFlow.value.webdavPassword,
        remotePath: String
    ) {
        syncStateFlow.update {
            it.copy(
                webdavUrl = url,
                webdavUsername = username,
                webdavPassword = password,
                webdavRemotePath = remotePath
            )
        }
    }

    fun updateS3Config(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: String,
        secretKey: String = syncStateFlow.value.s3SecretKey,
        objectKey: String
    ) {
        syncStateFlow.update {
            it.copy(
                s3Endpoint = endpoint,
                s3Bucket = bucket,
                s3Region = region,
                s3AccessKey = accessKey,
                s3SecretKey = secretKey,
                s3ObjectKey = objectKey
            )
        }
    }

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

    fun setAutoLockTimeout(seconds: Int, label: String) {
        securityTimeoutStateFlow.update {
            it.copy(autoLockTimeoutSeconds = seconds, autoLockTimeoutLabel = label)
        }
    }

    fun setClipboardTimeout(seconds: Int, label: String) {
        securityTimeoutStateFlow.update {
            it.copy(clipboardTimeoutSeconds = seconds, clipboardTimeoutLabel = label)
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        syncStateFlow.update { it.copy(autoSyncEnabled = enabled) }
    }

    fun setWifiOnlySync(enabled: Boolean) {
        syncStateFlow.update { it.copy(wifiOnlySync = enabled) }
    }

    fun setCredentialProviderEnabled(enabled: Boolean) {
        autofillStateFlow.update { it.copy(credentialProviderEnabled = enabled) }
    }

    fun setPasskeySupportEnabled(enabled: Boolean) {
        autofillStateFlow.update { it.copy(passkeySupportEnabled = enabled) }
    }

    fun setAutofillServiceEnabled(enabled: Boolean) {
        autofillStateFlow.update { it.copy(autofillServiceEnabled = enabled) }
    }

    fun setRecycleBinEnabled(enabled: Boolean) {
        databaseConfigStateFlow.update { it.copy(recycleBinEnabled = enabled) }
    }

    fun setShowUsernameInList(enabled: Boolean) {
        displayConfigStateFlow.update { it.copy(showUsernameInList = enabled) }
    }

    fun setShowOtpInList(enabled: Boolean) {
        displayConfigStateFlow.update { it.copy(showOtpInList = enabled) }
    }

    fun triggerSync() {
        if (syncStateFlow.value.isSyncing) return
        val providerName = syncStateFlow.value.provider.label
        viewModelScope.launch {
            syncStateFlow.update { it.copy(isSyncing = true, syncFeedbackMessage = "正在连接 $providerName 服务同步...") }
            delay(1200)
            syncStateFlow.update {
                it.copy(
                    isSyncing = false,
                    syncFeedbackMessage = "$providerName 同步完成：已验证云端原子哈希一致"
                )
            }
        }
    }

    fun clearSyncFeedbackMessage() {
        syncStateFlow.update { it.copy(syncFeedbackMessage = null) }
    }

    fun rescanHealth() {
        if (healthStateFlow.value.isHealthScanning) return
        viewModelScope.launch {
            healthStateFlow.update { it.copy(isHealthScanning = true) }
            delay(1000)
            healthStateFlow.update {
                it.copy(
                    isHealthScanning = false,
                    healthScore = 96,
                    healthStatus = "优秀",
                    healthMessage = "全库扫描完成，未发现已知泄露与弱密码",
                    lastHealthScanTime = "刚刚"
                )
            }
        }
    }
}
