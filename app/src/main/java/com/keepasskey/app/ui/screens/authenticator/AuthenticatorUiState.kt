package com.keepasskey.app.ui.screens.authenticator

import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry

/**
 * 验证码页面 UI 条目模型
 */
data class TotpCardItem(
    val entryId: String,
    val title: String,
    val account: String,
    val codeFormatted: String, // 例如 "582 910"
    val codeRaw: String,       // 例如 "582910"
    val remainingSeconds: Int,
    val iconName: String = "key",
    val url: String = ""
)

/**
 * 验证码页面 UI 状态
 */
data class AuthenticatorUiState(
    val items: List<TotpCardItem> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val userMessage: UiMessage? = null
)
