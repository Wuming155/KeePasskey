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

    override suspend fun setOledBlackOptimization(enabled: Boolean) {
        settingsFlow.update { it.copy(oledBlackOptimization = enabled) }
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
}
