package com.keepasskey.app.ui.screens.unlock

/**
 * 解锁屏幕的不可变 UI 状态
 */
data class UnlockUiState(
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val hasKeyFile: Boolean = false,
    val keyFileName: String = "master.key",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val databaseName: String = "personal-vault.kdbx",
    val databaseStatus: String = "本地已加密存储 • 关联 WebDAV 云端备份"
)
