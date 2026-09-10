package com.keepasskey.app.notification

import com.keepasskey.database.session.DatabaseSession

/**
 * 通知权限请求决策（ISSUE-P3-18 验收标准 2：拒绝后不得反复弹窗）。
 */
enum class NotificationPermissionDecision {
    /** 允许发起一次系统运行时权限请求 */
    REQUEST,

    /** 不请求（已授权 / 已询问过 / 用户此前已拒绝过一次） */
    SKIP
}

/**
 * 通知发送与权限请求的**纯决策层**（ISSUE-P3-18）。
 *
 * 全部判定收敛为无副作用的纯函数，JVM 单测可直接断言（不依赖任何 Android 框架实例）：
 * - 权限不可用 / 偏好关闭 → 一律返回 false（静默降级：不发送、不崩溃、不弹窗）；
 * - 会话态只有真正「已解密可用」时才发常驻通知，锁定与关闭都必须撤销；
 * - 权限请求仅允许「从未询问过且用户未表达过拒绝」这一次。
 */
object NotificationGate {

    /** TOTP 周期兜底值（秒）：条目快照未给出合法周期时按 RFC 6238 默认 30 秒计 */
    const val DEFAULT_TOTP_PERIOD_SECONDS = 30

    private const val MILLIS_PER_SECOND = 1_000L

    /**
     * 会话态是否表示「库已解密且可用」。
     *
     * [DatabaseSession.SessionState.DIRTY] 表示「已解锁且存在未落盘改动」，
     * 同样必须维持常驻通知——若只认 OPENED，任何一次编辑都会让通知错误地消失。
     */
    fun isUnlockedState(sessionState: DatabaseSession.SessionState): Boolean =
        sessionState == DatabaseSession.SessionState.OPENED ||
            sessionState == DatabaseSession.SessionState.DIRTY

    /**
     * 是否发送「已解锁」常驻通知。
     *
     * @param prefEnabled 用户偏好 `showUnlockedNotification`
     * @param permissionGranted 通知权限当前是否可用（`areNotificationsEnabled()`）
     * @param sessionState 当前会话状态
     */
    fun shouldPostUnlockedNotification(
        prefEnabled: Boolean,
        permissionGranted: Boolean,
        sessionState: DatabaseSession.SessionState
    ): Boolean = permissionGranted && prefEnabled && isUnlockedState(sessionState)

    /**
     * 是否发送自动填充验证码通知。
     *
     * @param prefEnabled 用户偏好 `autofillShowTotpNotification`
     * @param permissionGranted 通知权限当前是否可用
     * @param code 该条目的当前验证码；空白表示条目未配置 TOTP 或计算失败
     */
    fun shouldPostTotpNotification(
        prefEnabled: Boolean,
        permissionGranted: Boolean,
        code: String
    ): Boolean = permissionGranted && prefEnabled && code.isNotBlank()

    /**
     * 是否发起 `POST_NOTIFICATIONS` 运行时权限请求（ISSUE-P3-18 验收标准 2）。
     *
     * 三重闸门，任一命中即不再请求，杜绝「反复弹窗」：
     * 1. 已授权 → 无需请求；
     * 2. 已询问过（持久化标志，跨冷启动有效）→ 不再请求；
     * 3. [showRationale] 为 true 说明用户此前已明确拒绝过一次 → 不再自动请求
     *    （需要时由用户自行去系统设置开启，应用不骚扰）。
     */
    fun shouldRequestNotificationPermission(
        permissionGranted: Boolean,
        alreadyAsked: Boolean,
        showRationale: Boolean
    ): Boolean = !permissionGranted && !alreadyAsked && !showRationale

    /**
     * 验证码剩余有效秒数（纯函数，`nowMillis` 由调用方注入，便于单测断言时间边界）。
     *
     * RFC 6238 时间步语义：`elapsed = unixSeconds % period`，剩余 `period - elapsed`；
     * 结果恒落在 `1..period`，绝不出现 0 或负值——0 会让 `setTimeoutAfter` 立刻吞掉通知，
     * 负值则属非法入参。周期非正时按 [DEFAULT_TOTP_PERIOD_SECONDS] 兜底。
     */
    fun totpRemainingSeconds(nowMillis: Long, periodSeconds: Int): Int {
        val period = if (periodSeconds > 0) periodSeconds else DEFAULT_TOTP_PERIOD_SECONDS
        val elapsed = ((nowMillis / MILLIS_PER_SECOND) % period).toInt()
        return (period - elapsed).coerceIn(1, period)
    }
}
