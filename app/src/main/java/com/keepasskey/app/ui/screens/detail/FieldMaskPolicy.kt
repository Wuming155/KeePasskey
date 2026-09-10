package com.keepasskey.app.ui.screens.detail

/**
 * 敏感字段遮掩初始态决策（ISSUE-P3-17 语义红线）。
 *
 * `maskPasswordsDefault` / `maskTotpDefault` 的语义是**默认值**，不是**强制覆盖**：
 * 偏好只决定字段首次呈现时的遮掩态；用户本次会话对该字段的显式展开 / 收起恒优先，
 * 设置流再次发射（或页面重组、偏好快照刷新）**不得**把手动展开的字段重新盖上。
 *
 * 纯函数、无 Android 依赖，可 JVM 直测——「默认值 vs 强制覆盖」这条回归红线由单测直接钉住。
 */
object FieldMaskPolicy {

    /**
     * @param defaultMasked 偏好声明的默认遮掩态（`maskPasswordsDefault` / `maskTotpDefault`）
     * @param userOverride 用户本次会话显式设定的遮掩态：true = 已收起（遮掩）、
     *                     false = 已展开（明文）、null = 本次会话尚未对该字段操作过
     * @return 该字段当前是否应处于遮掩态
     */
    fun initialMaskState(defaultMasked: Boolean, userOverride: Boolean?): Boolean =
        userOverride ?: defaultMasked
}
