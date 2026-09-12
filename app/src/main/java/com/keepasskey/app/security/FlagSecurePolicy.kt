package com.keepasskey.app.security

/**
 * FLAG_SECURE 守卫状态机内核（ISSUE-P2-09 / ZT-14；2026-09-12 用户裁决语义修订）。
 *
 * 语义（**开关即生效**模型，取代原「临时豁免」模型）：
 * - **锁定态无条件强制遮蔽**：会话 CLOSED / LOCKED 时，无论用户开关状态如何，
 *   FLAG_SECURE 一律生效（主密码输入与 Recents 预览绝不泄露）；
 * - **解锁态只看用户开关**：开关开启 → 强制遮蔽；开关关闭 → **真实解除遮蔽**。
 *   原模型在开关关闭后仍默认强制、仅授予 5 分钟临时豁免，且 UI 风险确认从未接通
 *   `requestTemporaryExemption`，构成「关闭无效」的假开关（用户报告 2026-09-12），
 *   现按用户裁决改为开关即生效；风险确认对话框保留（确认后才真正写偏好）。
 * 纯函数实现，状态机可被 JVM 单测完整覆盖。
 */
object FlagSecurePolicy {

    /**
     * 裁决当前是否应施加 FLAG_SECURE。
     *
     * @param sessionLocked 会话是否处于 CLOSED / LOCKED（未解锁）
     * @param userEnabled 用户设置中的「截屏防护」开关
     * @return true 表示必须施加 FLAG_SECURE（锁定态 fail-closed）
     */
    fun shouldApplySecure(
        sessionLocked: Boolean,
        userEnabled: Boolean
    ): Boolean = sessionLocked || userEnabled
}
