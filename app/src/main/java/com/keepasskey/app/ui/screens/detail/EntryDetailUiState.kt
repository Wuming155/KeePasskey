package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
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
    val userMessage: UiMessage? = null,
    // 复制密码提示消息（按设置中的剪贴板超时时长动态生成，避免写死时长）
    val passwordCopyMessage: UiMessage = UiMessage(R.string.detail_password_copied_no_clear)
)
