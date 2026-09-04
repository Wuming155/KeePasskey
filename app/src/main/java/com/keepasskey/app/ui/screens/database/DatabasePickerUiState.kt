package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.ui.model.VaultDatabaseInfo

/**
 * 密码库选择与管理页面 UI 状态
 */
data class DatabasePickerUiState(
    val databases: List<VaultDatabaseInfo> = emptyList(),
    val isLoading: Boolean = false,
    val showCreateDialog: Boolean = false,
    val userMessage: String? = null
)
