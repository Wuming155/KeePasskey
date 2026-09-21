package com.keepasskey.app.autofill

import com.keepasskey.app.passkey.CredentialProviderRegistration

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
    FIELD_BLOCK_SIGNATURE_UNAVAILABLE,

    /**
     * 系统未把本应用登记为凭据提供者（`ISSUE-P2-239`）。
     *
     * 该状态下系统的创建请求在框架层即被丢弃（`TYPE_NO_CREATE_OPTIONS`），
     * **根本不会发到本应用** —— 用户只会看到「点保存没反应」。此前 CM 通道既无自检也无引导，
     * 此项即该静默失效的显式化出口（判定见 [com.keepasskey.app.passkey.CredentialProviderRegistration]）。
     */
    CREDENTIAL_PROVIDER_NOT_REGISTERED,

    /**
     * 系统凭据提供者登记状态**未知**（`ISSUE-P2-239` AC②）。
     *
     * 读取系统登记失败时**不得**当作「正常」渲染：那会让用户继续面对「点保存没反应」
     * 却看到一张全绿的卡 ⇒ 本项按异常项如实列出，并同样给出系统设置入口供用户自行核对。
     */
    CREDENTIAL_PROVIDER_STATE_UNKNOWN
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
    val fieldBlockSignatureUnavailable: Boolean = false,
    /**
     * 系统侧凭据提供者登记状态（`ISSUE-P2-239`）。
     *
     * **无默认值**（有意）：AC② 明令「读取失败一律呈现『未知』而非『正常』」，
     * 若给出 `= REGISTERED` 之类的缺省，任何漏传该参数的接线点都会静默渲染成「一切正常」——
     * 那正是本项要根治的失效形态。调用方必须显式交出探针读数。
     */
    val credentialProviderRegistration: CredentialProviderRegistration
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
            // ISSUE-P2-239：凭据提供者通道的两态各自成项——「未登记」给出修复指引、
            // 「未知」如实声明读不到（**不得**并入正常）
            when (credentialProviderRegistration) {
                CredentialProviderRegistration.REGISTERED -> Unit
                CredentialProviderRegistration.NOT_REGISTERED ->
                    add(AutofillHealthIssue.CREDENTIAL_PROVIDER_NOT_REGISTERED)
                CredentialProviderRegistration.UNKNOWN ->
                    add(AutofillHealthIssue.CREDENTIAL_PROVIDER_STATE_UNKNOWN)
            }
        }

    /**
     * 链路是否完全可用。
     *
     * 注意：Credential Manager 不可用**不**使传统自动填充失效（两条通道独立），
     * 故这里要求「传统链路三要素」齐备即视为传统填充可用；[issues] 仍如实列出全部异常。
     *
     * `ISSUE-P2-239` 同口径：系统未登记本应用只影响 **CM（保存 / 通行密钥）** 通道，
     * 传统 `AutofillService` 通道照常工作 ⇒ **不得**计入本判据（否则界面会给出错误的修复指引）。
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
        fieldBlockSignatureUnavailable: Boolean = false,
        /** `ISSUE-P2-239`：由 [com.keepasskey.app.passkey.CredentialProviderHealthProbe] 采集 */
        credentialProviderRegistration: CredentialProviderRegistration
    ): AutofillHealthReport = AutofillHealthReport(
        serviceDeclared = serviceDeclared,
        appEnabled = appEnabled,
        systemEnabled = systemEnabled,
        credentialManagerAvailable = credentialManagerAvailable,
        fieldBlockSignatureUnavailable = fieldBlockSignatureUnavailable,
        credentialProviderRegistration = credentialProviderRegistration
    )
}
