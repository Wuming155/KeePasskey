package com.keepasskey.app.ui.screens.authenticator

import com.keepasskey.app.ui.model.UiMessage

/** 验证码不可用（种子缺失 / 解析失败）时的占位文本：UI 依此禁用复制，绝不回退假码 */
internal const val TOTP_CODE_PLACEHOLDER = "------"

/**
 * 验证码显示文本（**单一实现**：卡片显示与用例断言共用，不再由 ViewModel 另算一份）。
 *
 * ISSUE-P3-182：此前格式化只写在 `AuthenticatorViewModel` 的整页 combine 变换体内，
 * 而卡片显示的是窄通道下发的**实时码**（每周期一份）——两条路径各写一份格式化必然漂移，
 * 故提为共用函数。入参先剔除空格：投影层之码与窄通道之码在此**不假设**是否已分组。
 *
 * 6 位 → `"xxx xxx"`；8 位 → `"xxxx xxxx"`；其余原样；null → [TOTP_CODE_PLACEHOLDER]。
 */
internal fun formatTotpCode(raw: String?): String {
    val digits = raw?.replace(" ", "") ?: return TOTP_CODE_PLACEHOLDER
    return when (digits.length) {
        6 -> "${digits.substring(0, 3)} ${digits.substring(3)}"
        8 -> "${digits.substring(0, 4)} ${digits.substring(4)}"
        else -> digits
    }
}

/**
 * 验证码页面 UI 条目模型
 */
data class TotpCardItem(
    val entryId: String,
    val title: String,
    val account: String,
    /**
     * 投影层即时计算的兜底之码（ISSUE-P3-182：只下发**码本身**，显示文本由卡片经
     * [formatTotpCode] 现算——此前另存一份 `codeFormatted`，与窄通道的实时码各持一份必然漂移）。
     *
     * TASK-33 语义保留：null 表示验证码不可用（种子缺失 / 解析失败），复制通道一并禁用——
     * 回退假码 "000000" 会诱导用户复制无效第二因子。
     */
    val codeRaw: String?,
    /**
     * 条目自身 TOTP 周期（秒）：倒计时与进度环分母必须取此值（ISSUE-P3-158）。
     * 缺省 30 仅用于预览 / 测试构造；生产由 ViewModel 按条目填充。
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
