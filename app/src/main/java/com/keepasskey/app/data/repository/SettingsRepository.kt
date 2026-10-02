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
 * 底栏 Tab 名单的存储规范名（ISSUE-P3-443）。
 *
 * 值与 `com.keepasskey.app.ui.components.BottomNavItem` 枚举 `name` 一致；存储层不引 UI 类型，
 * 借本常量收敛字面量（解析 / 排序语义在 UI 层 `BottomNavItem.resolveVisibleItems`）。
 */
object BottomNavTabNames {
    const val VAULT = "VAULT"
    const val AUTHENTICATOR = "AUTHENTICATOR"
    const val GENERATOR = "GENERATOR"
    const val SETTINGS = "SETTINGS"

    /** 出厂默认：全部可见，枚举序 */
    val DEFAULT_ORDER = listOf(VAULT, AUTHENTICATOR, GENERATOR, SETTINGS)
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
    // ISSUE-P2-212：默认**关闭**——生物识别解锁必须由用户在设置页手动开启，
    // 且开启动作本身要当场通过一次强生物识别验证（此前默认 true 却因无封印凭据
    // 而完全不生效，形成「开关显示已开、实际无任何生物入口」的静默失效）
    val biometricEnabled: Boolean = false,
    val autoLockBackground: Boolean = true,
    val autoLockTimeoutSeconds: Int = 60,
    val lockWhenScreenOff: Boolean = true,
    // ISSUE-P2-379：前台闲置自动锁定（与后台/熄屏并列；默认开 + 300 秒）
    val autoLockForegroundEnabled: Boolean = true,
    val autoLockForegroundTimeoutSeconds: Int = 300,
    // ISSUE-P3-381：回前台/网络恢复时轻量远端探测（默认开）
    val syncProbeOnResumeEnabled: Boolean = true,
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
    // ISSUE-P3-443：底栏 Tab「显隐 + 排序」一体化配置——**有序可见 Tab 名单**
    // （元素为 [BottomNavTabNames] 规范名；不在名单中的 Tab 为隐藏；空 / 损坏名单由
    // UI 解析层 `BottomNavItem.resolveVisibleItems` fail-safe 兜底）。
    // 旧「show_authenticator_tab / show_generator_tab」布尔键由仓库层装载时迁移（RealSettingsRepository）。
    val bottomNavOrder: List<String> = BottomNavTabNames.DEFAULT_ORDER,
    // ISSUE-P3-04：上次成功解锁使用的密钥文件「非密钥元数据」——SAF Uri 与文档显示名。
    // 绝不承载密钥文件字节或派生密钥；是否记忆由用户偏好开关
    // （ExtendedSettings.rememberKeyFileLocation，设置页「密钥文件策略」）控制，
    // 偏好关闭 / 授权失效时调用方须清除本记录，不得残留过期 Uri。
    val lastKeyFileUri: String = "",
    val lastKeyFileName: String = "",
    // ISSUE-P3-68：解锁失败重试节流总开关（2026-09-12 用户裁决：默认**关闭**，
    // 需要暴力破解防护的用户可在设置页显式开启）
    val unlockThrottleEnabled: Boolean = false,
    // ISSUE-P3-68：重试退避的最长锁定时长（秒，默认 1800 = 30 分钟；合法域 [60, 86400]）
    val unlockLockoutMaxSeconds: Int = 1800,
    // ISSUE-P1-22：用户对「软件级 Keystore 快速解锁降级」的显式确认记录。
    // 仅在封印密钥实际落位为 SOFTWARE / UNKNOWN 且用户在风险提示弹窗中明确选择
    // 「仍要启用」时置位；硬件落位（TEE / StrongBox）不依赖本标记。
    // 该标记同时驱动解锁页与安全设置页的常驻声明
    // 「本机快速解锁降级为软件密钥，不提供硬件级保护」。
    val quickUnlockDowngradeAcknowledged: Boolean = false
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
    /** ISSUE-P2-379：前台闲置自动锁定开关 */
    suspend fun setAutoLockForegroundEnabled(enabled: Boolean)
    /** ISSUE-P2-379：前台闲置超时（秒；-1 永不 / 0 立即 / >0 秒） */
    suspend fun setAutoLockForegroundTimeoutSeconds(seconds: Int)
    /** ISSUE-P3-381：回前台远端探测开关 */
    suspend fun setSyncProbeOnResumeEnabled(enabled: Boolean)
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

    /**
     * ISSUE-P3-443：底栏 Tab「显隐 + 排序」一体化配置——持久化**有序可见 Tab 名单**
     * （元素为 [BottomNavTabNames] 规范名，不在名单中的 Tab 即隐藏）。
     */
    suspend fun setBottomNavOrder(order: List<String>)

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

    /**
     * ISSUE-P1-22：记录/清除用户对「软件级 Keystore 快速解锁降级」的显式确认。
     * 置位即代表用户已在风险提示弹窗中明确选择在无硬件隔离的软件密钥上继续使用快速解锁。
     */
    suspend fun setQuickUnlockDowngradeAcknowledged(acknowledged: Boolean)
}
