package com.keepasskey.app.autofill

/**
 * 字段识别置信度（ISSUE-P3-39；从 [AutofillFieldScanner] 抽出以遵守单一职责与行数门禁）。
 *
 * 取代此前的「命中即布尔」判定：不同信号源（autofillHints / inputType / htmlName / 邻近 label）
 * 的可信度不同，统一为四档以支撑「多候选择优」——仅用于字段角色择优排序，
 * 不参与任何凭据放行判定。
 */
enum class FieldConfidence(val score: Int) {
    /** 未命中任何信号 */
    NONE(0),

    /** 邻近 label 文本命中（最弱，易受界面文案干扰） */
    LOW(10),

    /** inputType 掩码或 htmlName / idEntry 命中（中） */
    MEDIUM(20),

    /** autofillHints 显式声明（最强，由页面作者标注） */
    HIGH(30)
}

/**
 * 结构树抽象扫描节点。
 * 纯 Kotlin 模型，零 Android SDK 类依赖，便于直接在 JVM 上进行单元测试。
 */
data class ScanNode(
    val id: String,
    val autofillHints: List<String> = emptyList(),
    val inputType: Int = 0,
    val isFocused: Boolean = false,
    val htmlName: String? = null,
    val webDomain: String? = null,
    val packageName: String = "",
    /**
     * ISSUE-P3-39：邻近 label / 提示文本（AssistStructure 的 `ViewNode.hint` 等）。
     * 用于在无 autofillHints、无 htmlName 的表单中按界面文案（含中/英/西里尔文）兜底识别。
     */
    val label: String? = null,
    /**
     * ISSUE-P3-39：节点是否可见。
     * 不可见的密码框仍按密码准入（部分应用聚焦账号框时密码框尚未可见，若整体丢弃会导致填充失效）；
     * 不可见的账号框一律不参与（避免隐藏的搜索/备注被误判为登录账号）。
     */
    val isVisible: Boolean = true,
    /**
     * ISSUE-P3-43：页面是否允许对该字段自动填充（对应 AssistStructure 的 `importantForAutofill`）。
     * 仅当扫描方要求「尊重页面标记」（[scan] 的 `respectImportantForAutofill = true`）时，
     * false 才会导致该字段被跳过；对应设置项 `overrideNoAutofill`（默认 false=尊重）。
     */
    val importantForAutofill: Boolean = true,
    /**
     * ISSUE-P3-375 AC①：HTML `autocomplete` 属性（WebView 表单的结构化字段源之二，
     * 如 `cc-number` / `postal-code`）。原生控件该值为 null。
     */
    val autocomplete: String? = null,
    /** ISSUE-P2-588：视图控件类名（用于识别 SearchView 等系统/第三方搜索组件） */
    val className: String? = null,
    /** ISSUE-P2-588：视图无障碍描述（用于识别标注为「搜索」但 hint 为推荐词的搜索框） */
    val contentDescription: String? = null
)

