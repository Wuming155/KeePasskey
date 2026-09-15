package com.keepasskey.app.autofill

/**
 * 自动填充链路健康检查项（ISSUE-P3-41）。
 *
 * 每项都对应一个**用户可修复**的具体原因，避免设置页只给「异常」而无从下手。
 */
enum class AutofillHealthIssue {
    /** `AutofillService` 未在 Manifest 正确声明或缺少 `BIND_AUTOFILL_SERVICE` 权限 */
    SERVICE_NOT_DECLARED,

    /** 应用内「旧版自动填充服务」开关已关闭 */
    APP_DISABLED,

    /** 系统当前未把本应用选为自动填充服务 */
    SYSTEM_NOT_ENABLED,

    /** Credential Manager 通道不可用（依赖缺失 / 运行环境异常） */
    CREDENTIAL_MANAGER_UNAVAILABLE,

    /**
     * 字段屏蔽签名密钥不可用（ISSUE-P3-113）。
     *
     * 该状态下 `AutofillFieldBlocklistStore.isBlocked` 按 fail-closed 视为「已屏蔽」，
     * 结果是**整条字段级屏蔽判定恒为真** ⇒ 填充侧静态放弃下发候选；用户只看到「不出候选」，
     * 无从归因。此项即为该静默故障的显式化出口。
     */
    FIELD_BLOCK_SIGNATURE_UNAVAILABLE
}

/**
 * 自动填充服务健康报告（ISSUE-P3-41，纯数据 + 纯函数，JVM 可测）。
 *
 * 不承载任何敏感信息（不含用户安装应用清单、不含条目数据），仅描述本应用自身链路状态。
 */
data class AutofillHealthReport(
    val serviceDeclared: Boolean,
    val appEnabled: Boolean,
    val systemEnabled: Boolean,
    val credentialManagerAvailable: Boolean,
    /** 字段屏蔽签名密钥是否不可用（ISSUE-P3-113）；默认 false 以兼容既有调用与用例 */
    val fieldBlockSignatureUnavailable: Boolean = false
) {

    /** 全部检查项逐条列出（顺序即建议修复优先级） */
    val issues: List<AutofillHealthIssue>
        get() = buildList {
            if (!serviceDeclared) add(AutofillHealthIssue.SERVICE_NOT_DECLARED)
            if (!appEnabled) add(AutofillHealthIssue.APP_DISABLED)
            if (!systemEnabled) add(AutofillHealthIssue.SYSTEM_NOT_ENABLED)
            if (!credentialManagerAvailable) add(AutofillHealthIssue.CREDENTIAL_MANAGER_UNAVAILABLE)
            if (fieldBlockSignatureUnavailable) {
                add(AutofillHealthIssue.FIELD_BLOCK_SIGNATURE_UNAVAILABLE)
            }
        }

    /**
     * 链路是否完全可用。
     *
     * 注意：Credential Manager 不可用**不**使传统自动填充失效（两条通道独立），
     * 故这里要求「传统链路三要素」齐备即视为传统填充可用；[issues] 仍如实列出全部异常。
     */
    val isLegacyAutofillOperational: Boolean
        get() = serviceDeclared && appEnabled && systemEnabled

    val isFullyOperational: Boolean
        get() = issues.isEmpty()
}

/**
 * 健康判定策略（ISSUE-P3-41）：把探针读数映射为报告，集中承载判定语义以便单测。
 */
object AutofillHealthPolicy {

    fun evaluate(
        serviceDeclared: Boolean,
        appEnabled: Boolean,
        systemEnabled: Boolean,
        credentialManagerAvailable: Boolean,
        fieldBlockSignatureUnavailable: Boolean = false
    ): AutofillHealthReport = AutofillHealthReport(
        serviceDeclared = serviceDeclared,
        appEnabled = appEnabled,
        systemEnabled = systemEnabled,
        credentialManagerAvailable = credentialManagerAvailable,
        fieldBlockSignatureUnavailable = fieldBlockSignatureUnavailable
    )
}
