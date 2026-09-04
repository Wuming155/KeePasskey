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
    val protectedFieldsVisibility: Map<String, Boolean> = emptyMap(),
    val userMessage: String? = null,
    // 复制密码提示文案（按设置中的剪贴板超时时长动态生成，避免写死时长）
    val passwordCopyMessage: String = "密码已复制到剪贴板"
)
