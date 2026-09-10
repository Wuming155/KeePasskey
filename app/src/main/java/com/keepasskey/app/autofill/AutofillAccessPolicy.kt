package com.keepasskey.app.autofill

import com.keepasskey.app.security.IntegrityEnforcement

/**
 * 自动填充访问闸门（ISSUE-P2-07 / ISSUE-P2-08 共用，纯逻辑，JVM 可测）。
 *
 * 填充与保存两条路径在产出任何数据集或落库之前，必须统一经过本闸门：
 * 1. 运行完整性风险态（[IntegrityEnforcement.disableAutofill]）→ 拒绝；
 * 2. 调用包名命中自动填充黑名单 → 拒绝。
 *
 * 抽为纯函数的原因：AutofillService 依赖 Android 框架无法 JVM 实例化，
 * 闸门决策逻辑（尤其「保存侧命中黑名单不得落库」）需可被纯 JVM 单测直接覆盖。
 */
enum class AutofillRejection {
    /** 运行环境完整性风险（ISSUE-P2-08） */
    INTEGRITY_RISK,

    /** 调用应用命中自动填充黑名单（ISSUE-P2-07） */
    BLOCKLISTED
}

object AutofillAccessPolicy {

    /**
     * 返回拒绝原因；null 表示放行。
     *
     * @param enforcement 当前完整性策略
     * @param callingPackage 系统背书的调用方包名
     * @param isBlocklisted 黑名单判定（由 AutofillBlocklistStore 提供，其自身对非法包名 fail-closed）
     */
    fun rejectReason(
        enforcement: IntegrityEnforcement,
        callingPackage: String,
        isBlocklisted: (String) -> Boolean
    ): AutofillRejection? {
        if (enforcement.disableAutofill) return AutofillRejection.INTEGRITY_RISK
        if (isBlocklisted(callingPackage)) return AutofillRejection.BLOCKLISTED
        return null
    }
}
