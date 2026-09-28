package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.ui.model.UiMessage

import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.VaultGroup

/**
 * 编辑页单次事件（ISSUE-P2-355 批次自 `EntryEditViewModel.kt` 同包平移，零行为变更——
 * 该文件行数触及 tier1 红线，非职责相关声明一律外迁）
 */
sealed interface EntryEditEvent {
    data object SaveSuccess : EntryEditEvent
}

/**
 * 凭据编辑/添加页面的不可变 UI 状态
 *
 * M1 整改（加解密审查 2026-09）：本状态类不再承载密码明文（String 不可变驻留 StateFlow 堆内存，
 * 且每次 update 都会产生新副本）。密码经 EntryEditViewModel 的 CharArray 私有链路承载，
 * UI 仅感知 [passwordLength] 用于强度条渲染。
 *
 * TASK-10 整改（加解密审查 B9）：TOTP 种子与受保护自定义字段明文同样退出本状态类——
 * 前者由 ViewModel 的 CharArray 私有链路承载，后者在 UI 投影中恒为空串（与详情页读路径
 * 的掩码投影语义一致），编辑明文经 ViewModel 的 CharArray 私有链路承载与显式提交。
 */
data class EntryEditUiState(
    val entryId: String? = null,
    val groupId: String? = null,
    val availableGroups: List<VaultGroup> = emptyList(),
    val iconName: String = "key",
    // TASK-15：选中的自定义图标 UUID（hex）；非空时优先于 iconName 展示
    val customIconId: String? = null,
    val title: String = "",
    val username: String = "",
    /** 密码长度（非敏感元数据，用于强度条）；密码明文本身经 ViewModel CharArray 链路 */
    val passwordLength: Int = 0,
    /**
     * ISSUE-P2-286 AC①：密码真实熵位数（crypto 内核 `guessesLog10`，与详情页同一实现；
     * null = 未评估 / 评估不可用，强度条隐藏）。熵由 ViewModel 在 `Dispatchers.Default`
     * 按最新输入异步评估——密码明文不回流本状态类（铁律不变）。
     */
    val passwordEntropyBits: Int? = null,
    val url: String = "",
    val notes: String = "",
    val isPasskey: Boolean = false,
    val customFields: List<UiCustomField> = emptyList(),
    val attachments: List<UiAttachment> = emptyList(),
    // KP2A 能力补齐：标签（逗号/空格分隔输入）与 AutoType 默认序列、Override URL
    val tagsInput: String = "",
    val autoTypeSequence: String = "",
    val overrideUrl: String = "",
    // ISSUE-P3-310：过期编辑两态（false = 永不过期；expiryDate 取当日 23:59:59 本地时刻落库）
    val expiresEnabled: Boolean = false,
    val expiryDate: java.time.LocalDate? = null,
    val isPasswordVisible: Boolean = false,
    val showGenerator: Boolean = false,
    val passLength: Float = 20f,
    val useUpper: Boolean = true,
    val useLower: Boolean = true,
    val useDigits: Boolean = true,
    val useSymbols: Boolean = true,
    val excludeConfusing: Boolean = true,
    val userMessage: UiMessage? = null,
    val isSaved: Boolean = false,
    /**
     * ISSUE-P3-320：TOTP 预填回显轮次（非敏感元数据）。扫码回填成功后自增，
     * 作为 [com.keepasskey.app.ui.components.SecurePasswordField] 的 initialKey 组成部分
     * 驱动预填通道以**新种子**重新消费一次（字段即时回显扫码结果）。种子明文本身
     * 仍经 ViewModel 的 CharArray 预填通道承载，不进本状态类（铁律不变）。
     */
    val totpPrefillEpoch: Int = 0,
    // H4-只读整改：数据库以只读模式打开时禁用保存
    val isReadOnly: Boolean = false,
    /**
     * ISSUE-P2-354 AC①②：保存进行中（含 KDBX 落盘的整个保存期间）。
     * `saveEntry` 入口按此守卫——并发第二次直接 return（双击不再产生重复条目），
     * 两个保存按钮按此禁用并内嵌进度；成功/失败两条路径都回落为 false。
     */
    val isSaving: Boolean = false,
    /**
     * ISSUE-P3-368 AC②：保存链进度（0..1 确定段；null = KDF 派生等分段不确定段）。
     * 仅在 isSaving 期间由顶栏进度条渲染；isSaving 与按钮内嵌圈语义不回归。
     */
    val saveProgress: Float? = null,
    /**
     * ISSUE-P3-359 AC②：标题必填校验的**字段级**错误位（保存被拒时置位、用户重新输入即清除）。
     * 标题框据此渲染 `isError + supportingText`，与一次性 Snackbar 并存——
     * 后者随时间消失，inline 错误常驻到问题被修复为止。
     */
    val titleError: Boolean = false,
    /**
     * ISSUE-P3-359 AC⑤：打开既有条目 / 模板的异步解密预填进行中。
     * 载入期间编辑内容被遮罩禁输（见 `EntryEditContent` 的加载遮罩），载入完成
     * `applyLoadedEntry` 覆盖表单时用户不可能已有键入 ⇒ 「载入不覆盖用户输入」。
     */
    val isLoading: Boolean = false,
    // 表单脏标记：发生任何未保存修改后为 true，驱动返回前的丢弃确认
    val isDirty: Boolean = false
)
