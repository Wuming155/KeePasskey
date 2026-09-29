package com.keepasskey.app.autofill

import android.view.autofill.AutofillId

/**
 * 认证回传链路的字段级屏蔽**复检**（ISSUE-P2-384）。
 *
 * 背景：字段级屏蔽（ISSUE-P3-43 ②）此前只挂在 onFillRequest 的目标解析期
 * （[AutofillTargetFieldResolver]），认证回传（picker `confirmAndFill` / 确认页
 * `resolveAuthResultIntent`）构造 Dataset 时**不复检**——屏蔽写入后系统侧缓存响应重放 /
 * 再次确认时，被屏蔽字段的值仍可交付（Monica `c854ef2a` 同型实证）。
 *
 * 本策略与 [AutofillFieldBlockPolicy] 同族：纯 JVM 可断言、只会收紧不会放宽。
 * fail-closed：[isBlocked] 不可判定时实现须返回 true（字段不可交付）。
 */
internal object AutofillAuthDeliveryBlockPolicy {

    /**
     * 认证回传时刻仍可交付的目标框。
     *
     * @param usernameId 回传前复检后仍可交付的用户名框 id
     * @param passwordId 回传前复检后仍可交付的密码框 id
     */
    data class DeliverableFields(
        val usernameId: AutofillId?,
        val passwordId: AutofillId?
    ) {
        /** 两个框都不可交付：调用方应按「等价于取消」处置，不得构造空数据集谎报成功 */
        val blocksEntireForm: Boolean get() = usernameId == null && passwordId == null
    }

    /**
     * 复检认证 Intent 携带的目标框是否仍可交付。
     *
     * 语义：与 onFillRequest 期的 [AutofillFieldBlockPolicy.decide] **同一判定源**
     * （包名 + 表单自报域 + 角色），只是把闸门挪到「构造 Dataset 之前」——
     * 屏蔽后缓存重放 / 再次确认均走本函数，被屏蔽字段一律不出现在回传数据集里。
     *
     * @param usernameId 认证 Intent 携带的用户名框 id（可为 null：纯密码表单）
     * @param passwordId 认证 Intent 携带的密码框 id（可为 null：纯用户名表单）
     * @param hasUsernameField 本次交付是否**打算**写用户名（Intent 有 id 即视为 true）
     * @param hasPasswordField 本次交付是否**打算**写密码
     * @param isBlocked 角色级屏蔽查询（实现须 fail-closed：不可判定时返回 true）
     */
    fun filter(
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        hasUsernameField: Boolean = usernameId != null,
        hasPasswordField: Boolean = passwordId != null,
        isBlocked: (AutofillFieldRole) -> Boolean
    ): DeliverableFields {
        val decision = AutofillFieldBlockPolicy.decide(
            hasUsernameField = hasUsernameField,
            hasPasswordField = hasPasswordField,
            isBlocked = isBlocked
        )
        return DeliverableFields(
            usernameId = usernameId.takeIf { decision.allowUsername },
            passwordId = passwordId.takeIf { decision.allowPassword }
        )
    }
}
