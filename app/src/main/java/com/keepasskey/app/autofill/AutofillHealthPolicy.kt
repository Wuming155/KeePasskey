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

    /**
     * 系统当前未把本应用选为自动填充服务。
     *
     * `ISSUE-P3-391` 起该读数为**双路合成**（manager 优先 + `Settings.Secure` 回退复核，
     * 口径见 [AutofillHealthPolicy.resolveSystemEnabled]）：不再是单源判定，但两路**皆**
     * 读不到时仍会如实落到本项（不谎报正常）。
     */
    SYSTEM_NOT_ENABLED,

    /**
     * 应用内「旧版自动填充服务（无障碍）」开关已开，但系统侧本应用无障碍服务未启用
     * （`ISSUE-P2-405`）。
     *
     * 该通道是双闸门：只开应用内开关、系统侧未授权时服务根本不会收到事件，通道静默失效。
     * 此项即把「半接通」显式化，并给出系统设置入口。
     */
    LEGACY_ACCESSIBILITY_SYSTEM_NOT_ENABLED,

    /** Credential Manager 通道不可用（依赖缺失 / 运行环境异常） */
    CREDENTIAL_MANAGER_UNAVAILABLE,

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
    /**
     * 系统侧凭据提供者登记状态（`ISSUE-P2-239`）。
     *
     * **无默认值**（有意）：AC② 明令「读取失败一律呈现『未知』而非『正常』」，
     * 若给出 `= REGISTERED` 之类的缺省，任何漏传该参数的接线点都会静默渲染成「一切正常」——
     * 那正是本项要根治的失效形态。调用方必须显式交出探针读数。
     */
    val credentialProviderRegistration: CredentialProviderRegistration,
    /**
     * 应用内「旧版自动填充服务（无障碍）」开关（`ISSUE-P2-405`）。
     *
     * **无默认值**：漏传即编译失败，堵死「半接通被静默渲染成正常」。
     */
    val legacyAccessibilityAppEnabled: Boolean,
    /**
     * 系统侧本应用无障碍服务启用态（`ISSUE-P2-405`）。
     *
     * **无默认值**：读取失败时探针收敛为 false，UI 不得谎报「系统已启用」。
     */
    val legacyAccessibilitySystemEnabled: Boolean
) {

    /** 全部检查项逐条列出（顺序即建议修复优先级） */
    val issues: List<AutofillHealthIssue>
        get() = buildList {
            if (!serviceDeclared) add(AutofillHealthIssue.SERVICE_NOT_DECLARED)
            if (!appEnabled) add(AutofillHealthIssue.APP_DISABLED)
            if (!systemEnabled) add(AutofillHealthIssue.SYSTEM_NOT_ENABLED)
            // ISSUE-P2-405：双闸门半接通——应用开关开但系统无障碍服务未启用
            if (legacyAccessibilityAppEnabled && !legacyAccessibilitySystemEnabled) {
                add(AutofillHealthIssue.LEGACY_ACCESSIBILITY_SYSTEM_NOT_ENABLED)
            }
            if (!credentialManagerAvailable) add(AutofillHealthIssue.CREDENTIAL_MANAGER_UNAVAILABLE)
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
     *
     * `ISSUE-P2-405` 同口径：legacy 无障碍通道是**可选兜底**，其系统侧未启用**不**污染
     * 框架 `AutofillService` 通道的可用性判定。
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
        /** `ISSUE-P2-239`：由 [com.keepasskey.app.passkey.CredentialProviderHealthProbe] 采集 */
        credentialProviderRegistration: CredentialProviderRegistration,
        /** `ISSUE-P2-405`：旧版无障碍通道应用内开关 */
        legacyAccessibilityAppEnabled: Boolean,
        /** `ISSUE-P2-405`：旧版无障碍通道系统侧启用态 */
        legacyAccessibilitySystemEnabled: Boolean
    ): AutofillHealthReport = AutofillHealthReport(
        serviceDeclared = serviceDeclared,
        appEnabled = appEnabled,
        systemEnabled = systemEnabled,
        credentialManagerAvailable = credentialManagerAvailable,
        credentialProviderRegistration = credentialProviderRegistration,
        legacyAccessibilityAppEnabled = legacyAccessibilityAppEnabled,
        legacyAccessibilitySystemEnabled = legacyAccessibilitySystemEnabled
    )

    /**
     * 系统启用态**双路合成**（`ISSUE-P3-391` AC①）。
     *
     * 单源判定（仅 `AutofillManager.hasEnabledAutofillServices()`）在部分厂商 ROM 上会因查询
     * 延迟或返回空而把「已启用」误报成「未启用」（Monica `AutofillServiceChecker.kt:172`
     * 同坑实证），其修复即本口径：manager 读数为 false 时回退读 `Settings.Secure` 的
     * `autofill_service` 复核。口径落定如下：
     *
     * - manager 读数已启用 ⇒ 直接判启用（误报方向只在 false 一侧，无须回退）；
     * - manager 读数未启用 ⇒ 以回退读数与本应用组件名**精确比对**为准（[ownServiceIds]
     *   收录组件名两种落盘形态——全名 / 短名，哪系 ROM 落哪种不做臆测）；
     * - 两路皆读不到（回退读取抛异常 / 值为空、其他服务当选、组件名推导失败）⇒
     *   如实判「未启用」，与原单源失败口径一致，**不**谎报正常（同 `ISSUE-P2-239` 反例纪律）。
     *
     * 纯函数，JVM 可测；平台读取（`AutofillManager` / `Settings.Secure`）单点在
     * [com.keepasskey.app.autofill.AutofillHealthProbe]。
     *
     * **待真机实测（AC③，2026-09-29 整改时无真机）**：哪些厂商 ROM 复现 manager 延迟/空值、
     * `autofill_service` 内部键在各 ROM 上是否可读（本仓先例：内部键 `credential_service`
     * 真机读取抛 `SecurityException`，见 `CredentialProviderHealthProbe` KDoc），均待真机实测
     * 后回填本注释留痕。
     */
    fun resolveSystemEnabled(
        managerEnabled: Boolean,
        secureSetting: String?,
        ownServiceIds: Set<String>
    ): Boolean = managerEnabled || (!secureSetting.isNullOrBlank() && secureSetting in ownServiceIds)
}
