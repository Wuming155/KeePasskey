package com.keepasskey.app.autofill

/**
 * 字段签名级屏蔽的放行判定（ISSUE-P3-43 ②）。
 *
 * 抽为纯策略的理由：判定「哪个框还能填」直接决定明文是否下发，必须能在纯 JVM 上
 * 对每一种组合（含 fail-closed 分支）逐条断言，而不是埋在 `AutofillService` 里靠设备复现。
 *
 * 本策略**只会收紧、不会放宽**：它的唯一作用是把已识别到的字段标记为不可填充。
 */
object AutofillFieldBlockPolicy {

    /**
     * 判定结果。
     *
     * @param allowUsername 账号框是否仍可填充（识别不到该框时恒为 false）
     * @param allowPassword 密码框是否仍可填充（识别不到该框时恒为 false）
     */
    data class Decision(
        val allowUsername: Boolean,
        val allowPassword: Boolean
    ) {
        /** 两个框都不可填充：调用方应按「等价于未注册本填充服务」返回空响应。 */
        val blocksEntireForm: Boolean get() = !allowUsername && !allowPassword
    }

    /**
     * @param hasUsernameField 扫描是否识别到账号框
     * @param hasPasswordField 扫描是否识别到密码框
     * @param isBlocked 角色级屏蔽查询（实现须 fail-closed：不可判定时返回 true）
     */
    fun decide(
        hasUsernameField: Boolean,
        hasPasswordField: Boolean,
        isBlocked: (AutofillFieldRole) -> Boolean
    ): Decision = Decision(
        allowUsername = hasUsernameField && !isBlocked(AutofillFieldRole.USERNAME),
        allowPassword = hasPasswordField && !isBlocked(AutofillFieldRole.PASSWORD)
    )
}
