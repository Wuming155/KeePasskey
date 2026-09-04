package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * 用户安全与外观设置模型
 */
data class UserSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val oledBlackOptimization: Boolean = false,
    val biometricEnabled: Boolean = true,
    val autoLockBackground: Boolean = true,
    val flagSecureEnabled: Boolean = true,
    val autoClearClipboard: Boolean = true
)

/**
 * 设置数据仓库接口
 */
interface SettingsRepository {
    fun getSettings(): Flow<UserSettings>
    suspend fun setThemeMode(themeMode: AppThemeMode)
    suspend fun setOledBlackOptimization(enabled: Boolean)
    suspend fun setBiometricEnabled(enabled: Boolean)
    suspend fun setAutoLockBackground(enabled: Boolean)
    suspend fun setFlagSecureEnabled(enabled: Boolean)
    suspend fun setAutoClearClipboard(enabled: Boolean)
}
