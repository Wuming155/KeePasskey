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
    /**
     * 条目自身 TOTP 周期（秒）：进度环分母必须取此值（ISSUE-P3-158）。
     * 缺省 30 仅用于预览 / 测试构造；生产由 ViewModel 按快照或条目填充。
     */
    val periodSeconds: Int = 30,
    val iconName: String = "key",
    val url: String = "",
    // ISSUE-P3-49：HOTP 条目——无时间倒计时，取码为「推进计数器」显式动作
    val isHotp: Boolean = false
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
