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
    // 修复虚假开关整改：仅在选择真实密钥文件后由 ViewModel 填充真实文件名，不再写死假名
    val keyFileName: String = "",
    val isLoading: Boolean = false,
    val errorMessage: UiMessage? = null,
    val infoMessage: UiMessage? = null,
    // H1 整改：默认值不再写死演示库名/假状态文案，由真实活动数据库填充
    val databaseName: String = "",
    val databaseStatus: String = "",
    // H4-只读整改：用户可选择以只读模式打开（会话期间写盘硬拒绝）
    val openReadOnly: Boolean = false,

    // 快速解锁状态 (KP2A & KeePassDX 特性；Wave 12 起由强生物识别或设备锁屏凭据承载，自研 PIN 已移除)
    // true 表示本库已封印快速解锁凭据（完整主密码解锁成功后自动登记）
    val isQuickUnlockAvailable: Boolean = false,
    // 是否存在已配置的可用数据库（无数据库时展示空状态开箱引导）
    val hasDatabase: Boolean = false,
    // 默认要求输入完整主密码；由 ViewModel 依据封印凭据与快速解锁设置降级到快捷方式
    val unlockMode: UnlockMode = UnlockMode.STANDARD,
    val isBiometricEnabled: Boolean = false,
    // H1 整改：硬件安全声明不再写死假值，仅在有真实数据时展示
    val hardwareBackedSecurity: String = ""
)
