package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry

/**
 * 凭据详情页面 UI 状态。
 * M1 整改：条目投影不再携带密码明文；[revealedPassword] 仅在用户显式点击「查看」时
 * 按需解密并驻留本状态（单条、最短生命周期），隐藏/离开即置空。
 */
data class EntryDetailUiState(
    val entry: UiVaultEntry? = null,
    val isLoading: Boolean = false,
    val isPasswordVisible: Boolean = false,
    // 按需解密出的当前密码明文（仅在 isPasswordVisible 期间持有）
    val revealedPassword: String? = null,
    // 按需解密出的历史修订密码（对比弹窗打开期间持有），键为修订 id
    val revealedRevisionPasswords: Map<String, String> = emptyMap(),
    val isFavorite: Boolean = false,
    val protectedFieldsVisibility: Map<String, Boolean> = emptyMap(),
    val userMessage: UiMessage? = null,
    // 复制密码提示消息（按设置中的剪贴板超时时长动态生成，避免写死时长）
    val passwordCopyMessage: UiMessage = UiMessage(R.string.detail_password_copied_no_clear)
)
