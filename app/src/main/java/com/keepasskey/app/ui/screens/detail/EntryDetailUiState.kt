package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.ui.model.UiVaultEntry

/**
 * 凭据详情页面 UI 状态
 */
data class EntryDetailUiState(
    val entry: UiVaultEntry? = null,
    val isLoading: Boolean = false,
    val isPasswordVisible: Boolean = false,
    val isFavorite: Boolean = false,
    val userMessage: String? = null
)
