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
    // Material You 动态取色（Android 12+ 生效，开启后覆盖品牌调色盘）
    val dynamicColorEnabled: Boolean = false,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val biometricEnabled: Boolean = true,
    val autoLockBackground: Boolean = true,
    val autoLockTimeoutSeconds: Int = 60,
    val lockWhenScreenOff: Boolean = true,
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
    val showGeneratorTab: Boolean = true,
    // ISSUE-P3-04：上次成功解锁使用的密钥文件「非密钥元数据」——SAF Uri 与文档显示名。
    // 绝不承载密钥文件字节或派生密钥；是否记忆由用户偏好开关
    // （ExtendedSettings.rememberKeyFileLocation，设置页「密钥文件策略」）控制，
    // 偏好关闭 / 授权失效时调用方须清除本记录，不得残留过期 Uri。
    val lastKeyFileUri: String = "",
    val lastKeyFileName: String = "",
    // ISSUE-P3-68：解锁失败重试节流总开关（默认 true，安全默认不放松）
    val unlockThrottleEnabled: Boolean = true,
    // ISSUE-P3-68：重试退避的最长锁定时长（秒，默认 1800 = 30 分钟；合法域 [60, 86400]）
    val unlockLockoutMaxSeconds: Int = 1800
)

/**
 * 设置数据仓库接口
 */
interface SettingsRepository {
    fun getSettings(): Flow<UserSettings>
    suspend fun setThemeMode(themeMode: AppThemeMode)
    suspend fun setThemePalette(themePalette: com.keepasskey.app.ui.theme.AppThemePalette)
    suspend fun setOledBlackOptimization(enabled: Boolean)
    suspend fun setDynamicColorEnabled(enabled: Boolean)
    suspend fun setAppLanguage(language: AppLanguage)
    suspend fun setBiometricEnabled(enabled: Boolean)
    suspend fun setAutoLockBackground(enabled: Boolean)
    suspend fun setAutoLockTimeoutSeconds(seconds: Int)
    suspend fun setLockWhenScreenOff(enabled: Boolean)
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

    /**
     * ISSUE-P3-04：记住上次成功解锁使用的密钥文件（仅 SAF Uri 与文档显示名，
     * 属非密钥元数据；密钥文件字节/派生密钥绝不经本通道持久化）。
     */
    suspend fun setRememberedKeyFile(uri: String, displayName: String)

    /**
     * ISSUE-P3-04：清除已记忆的密钥文件元数据——偏好开关关闭、持久化读授权失效
     * 或本次解锁没有使用密钥文件时调用，杜绝过期 Uri 在下次冷启动被误恢复。
     */
    suspend fun clearRememberedKeyFile()

    /** ISSUE-P3-68：解锁失败重试节流总开关（关闭后失败不再触发退避锁定） */
    suspend fun setUnlockThrottleEnabled(enabled: Boolean)

    /** ISSUE-P3-68：重试退避的最长锁定时长（秒；仓库层负责 coerce 到合法域 [60, 86400]） */
    suspend fun setUnlockLockoutMaxSeconds(seconds: Int)
}
