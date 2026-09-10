package com.keepasskey.app.ui.screens.authenticator

import com.keepasskey.app.ui.model.UiMessage

/**
 * 验证码页面 UI 条目模型
 */
data class TotpCardItem(
    val entryId: String,
    val title: String,
    val account: String,
    val codeFormatted: String, // 例如 "582 910"；种子缺失/解析失败时为占位符 "------"
    // TASK-33 整改：null 表示验证码不可用（种子缺失/解析失败），复制通道一并禁用——
    // 此前回退假码 "000000" 会诱导用户复制无效第二因子
    val codeRaw: String?,      // 例如 "582910"
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
