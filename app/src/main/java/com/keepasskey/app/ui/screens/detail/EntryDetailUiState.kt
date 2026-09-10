package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.EntryDecorations
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
    /**
     * 密码明文是否可见。
     * ISSUE-P3-17：由 `maskPasswordsDefault`（**默认值**）与用户本次会话的显式展开/收起
     * 共同决定（见 [FieldMaskPolicy]）；偏好不得覆盖用户显式操作。
     */
    val isPasswordVisible: Boolean = false,
    /**
     * TOTP 验证码是否可见。
     * ISSUE-P3-17：语义同 [isPasswordVisible]，默认态来自 `maskTotpDefault`。
     */
    val isTotpVisible: Boolean = true,
    /**
     * ISSUE-P3-17：条目所属分组的完整路径（如「工作与生产力 / 研发与基础设施」）。
     * 仅 `showGroupInEntry` 开启且分组可解析时非空；null 表示该行不展示。
     */
    val groupPath: String? = null,
    // 按需解密出的当前密码明文（仅在 isPasswordVisible 期间持有）
    val revealedPassword: String? = null,
    // 按需解密出的历史修订密码（对比弹窗打开期间持有），键为修订 id
    val revealedRevisionPasswords: Map<String, String> = emptyMap(),
    val isFavorite: Boolean = false,
    val protectedFieldsVisibility: Map<String, Boolean> = emptyMap(),
    // F2 整改：受保护自定义字段按需解密出的明文（仅查看期间驻留，收起即清空），键为字段 id
    val revealedProtectedFields: Map<String, String> = emptyMap(),
    val userMessage: UiMessage? = null,
    // H4-只读整改：只读会话禁用编辑入口
    val isReadOnly: Boolean = false,
    // 断点6 整改：每秒驱动的 TOTP 剩余秒数（null=尚未首帧，沿用投影值）
    val totpRemainingSeconds: Int? = null,
    // 断点6 整改：周期翻转时按需重算的实时验证码（null=沿用投影值）
    val liveTotpCode: String? = null,
    // TASK-32 整改：真实熵估算的密码强度位数（null=未计算/无密码，投影层不解密密码，
    // 真实熵由详情页 ViewModel 在用户显式查看密码时按需估算下发）
    val passwordStrengthBits: Int? = null,
    // 复制密码提示消息（按设置中的剪贴板超时时长动态生成，避免写死时长）
    val passwordCopyMessage: UiMessage = UiMessage(R.string.detail_password_copied_no_clear),
    // TASK-44：本条目绑定的 Android 应用包名（自 URL 的 android:// 绑定解析）。
    // null=条目未绑定具体应用（如纯 Web 凭据），此时详情页不提供「为本应用禁用填充」入口
    val autofillBoundPackage: String? = null,
    // TASK-44：该绑定应用是否已列入自动填充黑名单（fill-closed 判定的 UI 回显）
    val isAutofillBlockedForApp: Boolean = false,
    // ISSUE-P3-02：条目展示装饰——自定义图标投影（已解码位图/缺图占位）与
    // Notes/URL 字段引用展开文案（仅公开字段，受保护字段恒为掩码）。
    // 图标解码与引用解析均在状态层完成，Composable 只做纯绘制。
    val decorations: EntryDecorations = EntryDecorations.EMPTY
)
