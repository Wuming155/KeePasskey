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
 * 解锁屏幕的不可变 UI 状态
 */
data class UnlockUiState(
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val hasKeyFile: Boolean = false,
    val keyFileName: String = "master.key",
    val isLoading: Boolean = false,
    val errorMessage: UiMessage? = null,
    val databaseName: String = "personal-vault.kdbx",
    val databaseStatus: String = "本地已加密存储 • 关联 WebDAV 云端备份",

    // QuickUnlock 状态 (KP2A & KeePassDX 特性)
    // true 表示本机已输入过主密码、存在快捷解锁缓存
    val isQuickUnlockAvailable: Boolean = true,
    // 默认要求输入完整主密码；由 ViewModel 依据快捷缓存与生物认证设置降级到快捷方式
    val unlockMode: UnlockMode = UnlockMode.STANDARD,
    val isBiometricEnabled: Boolean = false,
    val quickUnlockPin: String = "",
    val hardwareBackedSecurity: String = "Android StrongBox / TEE 硬件隔离密钥已激活",
    val quickUnlockRemainingMinutes: Int = 118 // 缓存有效剩余时长
)
