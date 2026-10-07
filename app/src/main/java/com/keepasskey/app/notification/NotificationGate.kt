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
     * `ISSUE-P3-440`：常驻通知是否附带**复制快捷动作**（用户名 / TOTP）。
     *
     * 动作必须有明确的作用对象，而常驻通知本身是全局的（不含条目）：只有在「最近查看过某个条目」
     * 时才挂动作；否则呈现一组点了没反应（或复制别处）的按钮，属假入口。
     *
     * 字段子集由安全复核定案 `PD-69` 收窄为「用户名 + TOTP」，密码与受保护字段永不上通知面。
     */
    fun shouldShowUnlockedCopyActions(recentEntryId: String?): Boolean =
        !recentEntryId.isNullOrBlank()

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
     * `ISSUE-P3-528`：已解锁常驻通知的「自动锁定倒计时」应交由系统自动撤销的时长（毫秒）；
     * `0` 表示**不设** timeout（非倒计时态）。
     *
     * 缘起：倒计时由系统 `Chronometer` 渲染，而它越过零点后**不停在 0**——AOSP
     * `widget/Chronometer.java` 的 `updateText()` 对负秒取绝对值后加负号（`R.string.negative_duration`），
     * 即继续显示 `-0:01`、`-0:02`…。而撤销只能在**进程存活**时由本应用完成：进程被系统回收后
     * 到点重投空闲文案的 `delay()` 任务随之消失，通知留在通知栏、`when` 已成过去时刻 ⇒ 负数一直累积
     * 到下次冷启动。
     *
     * 故把「到期撤销」交给系统侧：`Notification.Builder.setTimeoutAfter()` 由
     * `NotificationManagerService.scheduleTimeoutLocked()`（AOSP master，2026-10-07 核实）直接排定一个
     * `ELAPSED_REALTIME_WAKEUP` 精确闹钟，**与本应用进程无关**；到点撤销的排除位只有
     * `FLAG_FOREGROUND_SERVICE | FLAG_USER_INITIATED_JOB`，本通知两者皆非 ⇒ 生效。
     * 闹钟由 system_server 自行排定，**不需要**应用持有 `SCHEDULE_EXACT_ALARM`。
     *
     * 口径：`0` 必须不设 timeout——`setTimeoutAfter(0)` 等价「立刻撤销」，会瞬间吞掉常驻通知
     * （同 [totpRemainingSeconds] 已立的教训）；已过期（`deadline <= now`）同样按非倒计时态处置，
     * 与 `UnlockedNotificationController.post()` 的 Chronometer 闸门同判据。
     */
    fun autoLockCountdownTimeoutMs(deadlineMillis: Long?, nowMillis: Long): Long =
        if (deadlineMillis != null && deadlineMillis > nowMillis) deadlineMillis - nowMillis else 0L

    /**
     * `ISSUE-P3-528`：重投常驻通知前是否**必须先撤销一次**系统侧通知。
     *
     * 条件＝上一版是倒计时态（系统侧已按通知 key 排定 timeout 闹钟）、本次不再是。
     * 必要性：`NotificationManagerService` 只在通知被**撤销**时取消已排定的闹钟
     * （`cancelScheduledTimeoutLocked()` 的唯一调用点在 `cancelNotificationLocked()` 内）——
     * 重投同键通知**不会**撤销旧闹钟，旧闹钟到点会按 key 找到「当前这条」并把它撤掉。
     * 缺了这一步，表现是「已回到前台（或已把超时改成永不）的常驻通知在原截止时刻无声消失」，
     * 且要等下一次状态变化才回来。
     *
     * 反向（倒计时 → 倒计时）不需要撤销：同 key 的 timeout 只重排该闹钟（同一 PendingIntent
     * 再次 `setExactAndAllowWhileIdle` 即替换旧触发器），不会留下第二条。
     */
    fun mustClearSystemTimeoutOnRepost(previousWasCountdown: Boolean, nextIsCountdown: Boolean): Boolean =
        previousWasCountdown && !nextIsCountdown

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
