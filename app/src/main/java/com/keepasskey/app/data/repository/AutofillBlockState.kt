package com.keepasskey.app.data.repository

/**
 * 「包名 → 自动填充屏蔽状态」三态判定结果（ISSUE-P3-15）。
 *
 * 背景：ISSUE-P2-07 把 [AutofillBlocklistStore.isBlocked] 改为 fail-closed（非法包名返回 true）。
 * 该语义对**填充决策**是正确的（绝不因不可信包名放行凭据），但一个布尔值无法区分
 * 「用户显式屏蔽」与「包名非法被保守拒绝」两件不同的事：详情页「屏蔽 / 恢复自动填充」入口
 * 沿用布尔判定时，会把非法绑定包名误判为「已屏蔽」，进而在点击后向用户谎报「已恢复」。
 *
 * 因此把判定升维为三态：安全语义（[UnidentifiablePackage] 在填充侧仍按已屏蔽处理，见
 * [AutofillBlocklistStore.isBlocked] 的穷尽映射）与交互诚实性（对不可识别包名不产出
 * 「已屏蔽 / 已恢复」语义）各自显式表达，互不迁就——**安全判定不为文案让路**。
 */
sealed interface AutofillBlockState {

    /** 该包名合法且确在用户黑名单中（仅由用户显式屏蔽动作产生） */
    data object Blocked : AutofillBlockState

    /** 该包名合法且未被屏蔽 */
    data object NotBlocked : AutofillBlockState

    /**
     * 包名非法或无法识别（空串、段数不足、含非法字符等，即
     * [AutofillBlocklistStore.normalize] 返回 null）。填充侧据此 fail-closed 处理
     * （等价于已屏蔽）；交互侧不得声称用户已屏蔽或已恢复。
     */
    data object UnidentifiablePackage : AutofillBlockState
}
