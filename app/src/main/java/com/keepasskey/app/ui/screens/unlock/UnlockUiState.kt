package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.ui.model.UiMessage

/**
 * 解锁模式
 */
enum class UnlockMode {
    STANDARD,      // 完整主密码 + 密钥文件解锁
    QUICK_UNLOCK   // 硬件 KeyStore / 生物识别快捷解锁 (KP2A / KeePassDX 特性)
}

/**
 * 解锁屏幕的不可变 UI 状态。
 *
 * 安全设计：主密码不再以 String 形式驻留 UiState / StateFlow——
 * 输入侧由 [com.keepasskey.app.ui.components.SecurePasswordField] 桥接为 CharArray 直达 ViewModel，
 * 显示用 String 仅存活于输入组件内部并在离开组合时清零。
 */
data class UnlockUiState(
    val isPasswordVisible: Boolean = false,
    val hasKeyFile: Boolean = false,
    val keyFileName: String = "master.key",
    val isLoading: Boolean = false,
    val errorMessage: UiMessage? = null,
    val infoMessage: UiMessage? = null,
    // H1 整改：默认值不再写死演示库名/假状态文案，由真实活动数据库填充
    val databaseName: String = "",
    val databaseStatus: String = "",
    // H4-只读整改：用户可选择以只读模式打开（会话期间写盘硬拒绝）
    val openReadOnly: Boolean = false,

    // QuickUnlock 状态 (KP2A & KeePassDX 特性)
    // true 表示本机已输入过主密码、存在快捷解锁缓存
    val isQuickUnlockAvailable: Boolean = true,
    // 默认要求输入完整主密码；由 ViewModel 依据快捷缓存与生物认证设置降级到快捷方式
    val unlockMode: UnlockMode = UnlockMode.STANDARD,
    val isBiometricEnabled: Boolean = false,
    val quickUnlockPin: String = "",
    // H1 整改：硬件安全声明与缓存剩余时长不再写死假值，仅在有真实数据时展示
    val hardwareBackedSecurity: String = "",
    val quickUnlockRemainingMinutes: Int = 0
)
