package com.keepasskey.app.autofill

import com.keepasskey.app.security.IntegrityEnforcement
import java.util.Locale

/**
 * 自动填充访问闸门（ISSUE-P2-07 / ISSUE-P2-08 / ISSUE-P2-226 共用，纯逻辑，JVM 可测）。
 *
 * 填充与保存两条路径在产出任何数据集或落库之前，必须统一经过本闸门：
 * 1. 运行完整性风险态（[IntegrityEnforcement.disableAutofill]）→ 拒绝；
 * 2. 调用方即本应用自身（ISSUE-P2-226）→ 拒绝；
 * 3. 调用包名命中自动填充黑名单 → 拒绝。
 *
 * 抽为纯函数的原因：AutofillService 依赖 Android 框架无法 JVM 实例化，
 * 闸门决策逻辑（尤其「保存侧命中黑名单不得落库」）需可被纯 JVM 单测直接覆盖。
 */
enum class AutofillRejection {
    /** 运行环境完整性风险（ISSUE-P2-08） */
    INTEGRITY_RISK,

    /**
     * 调用方即本应用自身（ISSUE-P2-226）。
     *
     * 框架**并不禁止**自身界面发起填充请求，故本应用 Compose 口令框（`SecurePasswordField`
     * 的 `KeyboardType.Password` + `password()` 语义）会正常向本服务发请求，
     * 结果是「在密码管理器里给自己填密码」——既无意义又可能把错误凭据填进主密码框、
     * 计入解锁失败节流。
     */
    SELF_APP,

    /** 调用应用命中自动填充黑名单（ISSUE-P2-07） */
    BLOCKLISTED
}

object AutofillAccessPolicy {

    /**
     * 「调用方是否即本应用」的**唯一判据**（ISSUE-P2-226）。
     *
     * 两侧包名同源自框架（调用方由系统背书、自身由 `Context.packageName`），故只做归一化等值比较，
     * 不套 [AutofillPackageNames.normalize] 的合法性正则——非法形态本就不该出现在该位置，
     * 且闸门随后仍会经黑名单侧 fail-closed 拒绝，不因此放行。
     *
     * 空/空白一律**不**判为自身（避免以空串 `selfPackage` 误伤全部调用方）。
     */
    fun isSelfApp(callingPackage: String, selfPackage: String): Boolean {
        val normalized = callingPackage.trim().lowercase(Locale.ROOT)
        return normalized.isNotEmpty() && normalized == selfPackage.trim().lowercase(Locale.ROOT)
    }

    /**
     * 返回拒绝原因；null 表示放行。
     *
     * @param enforcement 当前完整性策略
     * @param callingPackage 系统背书的调用方包名
     * @param selfPackage 本应用包名（`Context.packageName`），命中即拒（ISSUE-P2-226）
     * @param isBlocklisted 黑名单判定（由 AutofillBlocklistStore 提供，其自身对非法包名 fail-closed）
     */
    fun rejectReason(
        enforcement: IntegrityEnforcement,
        callingPackage: String,
        selfPackage: String,
        isBlocklisted: (String) -> Boolean
    ): AutofillRejection? {
        if (enforcement.disableAutofill) return AutofillRejection.INTEGRITY_RISK
        if (isSelfApp(callingPackage, selfPackage)) return AutofillRejection.SELF_APP
        if (isBlocklisted(callingPackage)) return AutofillRejection.BLOCKLISTED
        return null
    }
}
