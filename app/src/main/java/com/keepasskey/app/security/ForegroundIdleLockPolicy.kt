package com.keepasskey.app.security

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * ISSUE-P2-379：前台闲置自动锁定策略（纯函数，JVM 可测）。
 *
 * 裁决（批次留痕）：
 * - 与既有熄屏 / 后台超时**并列**的独立安全网；
 * - 默认 **开启**，默认闲置 **300 秒**（5 分钟）——超出系统息屏之外的兜底；
 * - 交互刷新语义：`onUserInteraction` 类事件重置前台闲置计时（触摸 / 键盘 / 焦点）；
 * - 与自动填充会话、剪贴板清理口径一致：**闲置锁定后一律锁库**，不出现
 *   「一边判活一边已锁」——锁定态下交互不再重启计时，须重新解锁；
 * - 时钟回拨 fail-closed（对齐 §354 AutofillActivePromptThrottle）：
 *   `now < lastInteraction`（回拨）时按**立即过期**处理。
 */
object ForegroundIdleLockPolicy {

    /** 默认闲置超时（秒）——与后台 60s 默认错开，前台阅读态给更宽裕窗口 */
    const val DEFAULT_IDLE_TIMEOUT_SECONDS = 300

    /** 合法超时域：0 = 立即锁，-1 = 永不，其余 > 0 秒 */
    const val NEVER = -1
    const val IMMEDIATE = 0

    /** 时钟回拨容差：now 早于 lastInteraction 超过该值仍按回拨处理（防毫秒抖动误判） */
    private const val CLOCK_ROLLBACK_TOLERANCE_MILLIS = 1_000L

    sealed class IdleDecision {
        object KeepAlive : IdleDecision()

        /** 闲置超时 → 应锁库 */
        data class LockNow(val reason: String) : IdleDecision()

        /** 「永不」档 → 不因前台闲置锁定 */
        object NeverIdleLock : IdleDecision()
    }

    /**
     * 判定前台闲置是否应锁定。
     *
     * @param idleTimeoutSeconds 设置值（-1 永不 / 0 立即 / >0 秒）
     * @param lastInteractionMillis 最近一次用户交互时刻；null = 尚未交互（按解锁成功时刻播种）
     * @param nowMillis 当前墙钟毫秒（可注入）
     */
    fun decide(
        idleTimeoutSeconds: Int,
        lastInteractionMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): IdleDecision {
        if (idleTimeoutSeconds < 0) return IdleDecision.NeverIdleLock
        val last = lastInteractionMillis ?: return when {
            idleTimeoutSeconds == IMMEDIATE -> IdleDecision.LockNow("前台立即锁定（从未交互）")
            else -> IdleDecision.KeepAlive
        }

        // ISSUE-P2-379 / §354：时钟回拨 fail-closed —— 回拨按立即过期
        if (nowMillis + CLOCK_ROLLBACK_TOLERANCE_MILLIS < last) {
            return IdleDecision.LockNow("检测到时钟回拨，前台闲置锁定 fail-closed")
        }

        if (idleTimeoutSeconds == IMMEDIATE) {
            return IdleDecision.LockNow("前台立即闲置锁定")
        }

        val elapsed = nowMillis - last
        val timeoutMillis = idleTimeoutSeconds * 1_000L
        return if (elapsed >= timeoutMillis) {
            IdleDecision.LockNow("前台闲置超时自动锁定 (${idleTimeoutSeconds} 秒)")
        } else {
            IdleDecision.KeepAlive
        }
    }

    /**
     * 交互刷新后的下一次应锁时刻；「永不」返回 null。
     * 与 [decide] 共用同一超时语义。
     */
    fun nextLockAtMillis(
        idleTimeoutSeconds: Int,
        lastInteractionMillis: Long?
    ): Long? {
        if (idleTimeoutSeconds < 0) return null
        val last = lastInteractionMillis ?: return null
        if (idleTimeoutSeconds == IMMEDIATE) return last
        return last + idleTimeoutSeconds * 1_000L
    }

    /**
     * 锁定态下交互是否应刷新计时——**否**。
     * 口径：锁定后须重新解锁；禁止「一边判活一边已锁」的矛盾态。
     */
    fun shouldRefreshOnInteraction(isLocked: Boolean): Boolean = !isLocked
}
