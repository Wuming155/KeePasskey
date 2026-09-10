package com.keepasskey.app.security

/**
 * FLAG_SECURE 守卫状态机内核（ISSUE-P2-09 / ZT-14）。
 *
 * 语义选型（**临时豁免模型**，非「允许永久关闭」）：
 * - **锁定态无条件强制遮蔽**：会话 CLOSED / LOCKED 时，无论用户开关与豁免状态如何，
 *   FLAG_SECURE 一律生效（主密码输入与 Recents 预览绝不泄露）；
 * - **解锁态默认强制遮蔽**：用户开关开启时强制；用户开关关闭时**仍默认强制**——
 *   因为「关闭」在密码管理器场景中不再是静默生效的普通偏好；
 * - **显式风险确认后的临时豁免**：仅当存在一个由 UI 显式风险确认写入、且尚未过期的
 *   豁免截止时间戳时，才允许在解锁态临时解除遮蔽；窗口到期或会话重新锁定即恢复强制。
 *
 * 该模型保证：即使设置项被持久化为关闭（进程重启后豁免丢失），守卫仍 fail-closed 强制遮蔽。
 * 纯函数实现，状态机可被 JVM 单测完整覆盖。
 */
object FlagSecurePolicy {

    /**
     * 用户显式确认风险后，允许临时解除遮蔽的最长窗口（5 分钟）。
     * 仅存储于内存，不随进程重启恢复。
     */
    const val TEMPORARY_EXEMPTION_WINDOW_MS = 5 * 60 * 1000L

    /**
     * 裁决当前是否应施加 FLAG_SECURE。
     *
     * @param sessionLocked 会话是否处于 CLOSED / LOCKED（未解锁）
     * @param userEnabled 用户设置中的「截屏防护」开关
     * @param exemptionUntilMs 临时豁免截止时间戳（null 表示无豁免）
     * @param nowMs 当前时间
     * @return true 表示必须施加 FLAG_SECURE（fail-closed）
     */
    fun shouldApplySecure(
        sessionLocked: Boolean,
        userEnabled: Boolean,
        exemptionUntilMs: Long?,
        nowMs: Long
    ): Boolean {
        if (sessionLocked) return true
        if (userEnabled) return true
        if (exemptionUntilMs == null) return true
        // 到期即失效（nowMs == until 视为已过期）
        return nowMs >= exemptionUntilMs
    }

    /** 是否处于「已确认风险的临时解除遮蔽」窗口内（供 UI 展示倒计时/状态） */
    fun isExemptionActive(
        sessionLocked: Boolean,
        exemptionUntilMs: Long?,
        nowMs: Long
    ): Boolean = !sessionLocked && exemptionUntilMs != null && nowMs < exemptionUntilMs
}
