package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * 界面语言设置
 */
enum class AppLanguage(val label: String, val code: String) {
    SYSTEM("跟随系统", "system"),
    ZH_CN("简体中文", "zh"),
    EN_US("English", "en")
}

/**
 * 用户安全与外观设置模型
 */
data class UserSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val themePalette: com.keepasskey.app.ui.theme.AppThemePalette = com.keepasskey.app.ui.theme.AppThemePalette.SAPPHIRE,
    val oledBlackOptimization: Boolean = false,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val biometricEnabled: Boolean = true,
    val autoLockBackground: Boolean = true,
    val flagSecureEnabled: Boolean = true,
    val autoClearClipboard: Boolean = true,
    // 列表视图显示偏好（由密码库列表消费，设置页外观项可调）
    val showUsernameInList: Boolean = true,
    val showOtpInList: Boolean = true,
    val showPasskeyBadge: Boolean = true,
    val showUrlInList: Boolean = true,
    val hideFabOnScroll: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
    // 剪贴板自动清空超时（秒，-1 = 从不清空）
    val clipboardTimeoutSeconds: Int = 30,
    // 冷启动自动与云端同步 (杀死进程重新启动时自动触发)
    val syncOnColdStart: Boolean = true,
    // 底部导航项可见性配置
    val showAuthenticatorTab: Boolean = true,
    val showGeneratorTab: Boolean = true
)

/**
 * 设置数据仓库接口
 */
interface SettingsRepository {
    fun getSettings(): Flow<UserSettings>
    suspend fun setThemeMode(themeMode: AppThemeMode)
    suspend fun setThemePalette(themePalette: com.keepasskey.app.ui.theme.AppThemePalette)
    suspend fun setOledBlackOptimization(enabled: Boolean)
    suspend fun setAppLanguage(language: AppLanguage)
    suspend fun setBiometricEnabled(enabled: Boolean)
    suspend fun setAutoLockBackground(enabled: Boolean)
    suspend fun setFlagSecureEnabled(enabled: Boolean)
    suspend fun setAutoClearClipboard(enabled: Boolean)
    suspend fun setShowUsernameInList(enabled: Boolean)
    suspend fun setShowOtpInList(enabled: Boolean)
    suspend fun setShowPasskeyBadge(enabled: Boolean)
    suspend fun setShowUrlInList(enabled: Boolean)
    suspend fun setHideFabOnScroll(enabled: Boolean)
    suspend fun setHapticFeedbackEnabled(enabled: Boolean)
    suspend fun setClipboardTimeout(seconds: Int)
    suspend fun setSyncOnColdStart(enabled: Boolean)
    suspend fun setShowAuthenticatorTab(enabled: Boolean)
    suspend fun setShowGeneratorTab(enabled: Boolean)
}
