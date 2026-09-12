package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 仅供 JVM 单元测试使用的内存设置仓库假实现（H4 整改：移出生产 source set）。
 */
class FakeSettingsRepository() : SettingsRepository {

    private val settingsFlow = MutableStateFlow(UserSettings())

    override fun getSettings(): Flow<UserSettings> = settingsFlow.asStateFlow()

    override suspend fun setThemeMode(themeMode: AppThemeMode) {
        settingsFlow.update { it.copy(themeMode = themeMode) }
    }

    override suspend fun setThemePalette(themePalette: com.keepasskey.app.ui.theme.AppThemePalette) {
        settingsFlow.update { it.copy(themePalette = themePalette) }
    }

    override suspend fun setDynamicColorEnabled(enabled: Boolean) {
        settingsFlow.update { it.copy(dynamicColorEnabled = enabled) }
    }

    override suspend fun setOledBlackOptimization(enabled: Boolean) {
        settingsFlow.update { it.copy(oledBlackOptimization = enabled) }
    }

    override suspend fun setAppLanguage(language: AppLanguage) {
        settingsFlow.update { it.copy(appLanguage = language) }
    }

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        settingsFlow.update { it.copy(biometricEnabled = enabled) }
    }

    override suspend fun setAutoLockBackground(enabled: Boolean) {
        settingsFlow.update { it.copy(autoLockBackground = enabled) }
    }

    override suspend fun setAutoLockTimeoutSeconds(seconds: Int) {
        settingsFlow.update { it.copy(autoLockTimeoutSeconds = seconds) }
    }

    override suspend fun setLockWhenScreenOff(enabled: Boolean) {
        settingsFlow.update { it.copy(lockWhenScreenOff = enabled) }
    }

    override suspend fun setFlagSecureEnabled(enabled: Boolean) {
        settingsFlow.update { it.copy(flagSecureEnabled = enabled) }
    }

    override suspend fun setAutoClearClipboard(enabled: Boolean) {
        settingsFlow.update { it.copy(autoClearClipboard = enabled) }
    }

    override suspend fun setShowUsernameInList(enabled: Boolean) {
        settingsFlow.update { it.copy(showUsernameInList = enabled) }
    }

    override suspend fun setShowOtpInList(enabled: Boolean) {
        settingsFlow.update { it.copy(showOtpInList = enabled) }
    }

    override suspend fun setShowPasskeyBadge(enabled: Boolean) {
        settingsFlow.update { it.copy(showPasskeyBadge = enabled) }
    }

    override suspend fun setShowUrlInList(enabled: Boolean) {
        settingsFlow.update { it.copy(showUrlInList = enabled) }
    }

    override suspend fun setHideFabOnScroll(enabled: Boolean) {
        settingsFlow.update { it.copy(hideFabOnScroll = enabled) }
    }

    override suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        settingsFlow.update { it.copy(hapticFeedbackEnabled = enabled) }
    }

    override suspend fun setClipboardTimeout(seconds: Int) {
        settingsFlow.update { it.copy(clipboardTimeoutSeconds = seconds) }
    }

    override suspend fun setSyncOnColdStart(enabled: Boolean) {
        settingsFlow.update { it.copy(syncOnColdStart = enabled) }
    }

    override suspend fun setShowAuthenticatorTab(enabled: Boolean) {
        settingsFlow.update { it.copy(showAuthenticatorTab = enabled) }
    }

    override suspend fun setShowGeneratorTab(enabled: Boolean) {
        settingsFlow.update { it.copy(showGeneratorTab = enabled) }
    }

    /** ISSUE-P3-04：密钥文件「非密钥元数据」入内存流（与生产 DataStore 语义一致） */
    override suspend fun setRememberedKeyFile(uri: String, displayName: String) {
        settingsFlow.update { it.copy(lastKeyFileUri = uri, lastKeyFileName = displayName) }
    }

    override suspend fun clearRememberedKeyFile() {
        settingsFlow.update { it.copy(lastKeyFileUri = "", lastKeyFileName = "") }
    }

    /** ISSUE-P3-68：重试节流开关入内存流（与生产 DataStore 语义一致） */
    override suspend fun setUnlockThrottleEnabled(enabled: Boolean) {
        settingsFlow.update { it.copy(unlockThrottleEnabled = enabled) }
    }

    /** ISSUE-P3-68：最长锁定时长入内存流（与生产一致的 coerce 域） */
    override suspend fun setUnlockLockoutMaxSeconds(seconds: Int) {
        settingsFlow.update {
            it.copy(unlockLockoutMaxSeconds = seconds.coerceIn(60, 24 * 60 * 60))
        }
    }
}
