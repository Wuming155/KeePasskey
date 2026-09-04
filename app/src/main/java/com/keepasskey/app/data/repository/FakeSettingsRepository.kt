package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 阶段 1 内存设置仓库实现
 */
@Singleton
class FakeSettingsRepository @Inject constructor() : SettingsRepository {

    private val settingsFlow = MutableStateFlow(UserSettings())

    override fun getSettings(): Flow<UserSettings> = settingsFlow.asStateFlow()

    override suspend fun setThemeMode(themeMode: AppThemeMode) {
        settingsFlow.update { it.copy(themeMode = themeMode) }
    }

    override suspend fun setThemePalette(themePalette: com.keepasskey.app.ui.theme.AppThemePalette) {
        settingsFlow.update { it.copy(themePalette = themePalette) }
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
}
