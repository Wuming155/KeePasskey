package com.keepasskey.app.notification

import com.keepasskey.database.session.DatabaseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-18：通知发送与权限请求的纯决策层单测。
 *
 * 覆盖验收标准 2「权限缺失时静默降级、绝不反复弹窗」与验收标准 3「偏好真实控制发送」：
 * 每条用例只断言纯函数，不实例化任何 Android 框架对象（可在 JVM 直接运行）。
 */
class NotificationGateTest {

    // ===== 已解锁常驻通知（showUnlockedNotification）=====

    @Test
    fun `权限可用且偏好开启且库已解锁时发送常驻通知`() {
        assertTrue(
            NotificationGate.shouldPostUnlockedNotification(
                prefEnabled = true,
                permissionGranted = true,
                sessionState = DatabaseSession.SessionState.OPENED
            )
        )
    }

    @Test
    fun `存在未落盘改动时仍维持常驻通知`() {
        assertTrue(
            NotificationGate.shouldPostUnlockedNotification(
                prefEnabled = true,
                permissionGranted = true,
                sessionState = DatabaseSession.SessionState.DIRTY
            )
        )
    }

    @Test
    fun `偏好关闭时不发送常驻通知`() {
        assertFalse(
            NotificationGate.shouldPostUnlockedNotification(
                prefEnabled = false,
                permissionGranted = true,
                sessionState = DatabaseSession.SessionState.OPENED
            )
        )
    }

    @Test
    fun `通知权限缺失时静默降级不发送常驻通知`() {
        assertFalse(
            NotificationGate.shouldPostUnlockedNotification(
                prefEnabled = true,
                permissionGranted = false,
                sessionState = DatabaseSession.SessionState.OPENED
            )
        )
    }

    @Test
    fun `库锁定时不发送常驻通知`() {
        assertFalse(
            NotificationGate.shouldPostUnlockedNotification(
                prefEnabled = true,
                permissionGranted = true,
                sessionState = DatabaseSession.SessionState.LOCKED
            )
        )
    }

    @Test
    fun `库关闭时撤销常驻通知`() {
        assertFalse(
            NotificationGate.shouldPostUnlockedNotification(
                prefEnabled = true,
                permissionGranted = true,
                sessionState = DatabaseSession.SessionState.CLOSED
            )
        )
    }

    @Test
    fun `仅 OPENED 与 DIRTY 视为已解锁态`() {
        assertTrue(NotificationGate.isUnlockedState(DatabaseSession.SessionState.OPENED))
        assertTrue(NotificationGate.isUnlockedState(DatabaseSession.SessionState.DIRTY))
        assertFalse(NotificationGate.isUnlockedState(DatabaseSession.SessionState.LOCKED))
        assertFalse(NotificationGate.isUnlockedState(DatabaseSession.SessionState.CLOSED))
    }

    // ===== 自动填充验证码通知（autofillShowTotpNotification）=====

    @Test
    fun `权限可用且偏好开启且条目有验证码时发送验证码通知`() {
        assertTrue(
            NotificationGate.shouldPostTotpNotification(
                prefEnabled = true,
                permissionGranted = true,
                code = "123456"
            )
        )
    }

    @Test
    fun `偏好关闭时不发送验证码通知`() {
        assertFalse(
            NotificationGate.shouldPostTotpNotification(
                prefEnabled = false,
                permissionGranted = true,
                code = "123456"
            )
        )
    }

    @Test
    fun `通知权限缺失时静默降级不发送验证码通知`() {
        assertFalse(
            NotificationGate.shouldPostTotpNotification(
                prefEnabled = true,
                permissionGranted = false,
                code = "123456"
            )
        )
    }

    @Test
    fun `验证码为空串时不发送验证码通知`() {
        assertFalse(
            NotificationGate.shouldPostTotpNotification(
                prefEnabled = true,
                permissionGranted = true,
                code = ""
            )
        )
    }

    @Test
    fun `验证码为空白字符时不发送验证码通知`() {
        assertFalse(
            NotificationGate.shouldPostTotpNotification(
                prefEnabled = true,
                permissionGranted = true,
                code = "   "
            )
        )
    }

    // ===== 通知权限请求闸门（验收标准 2：不得反复弹窗）=====

    @Test
    fun `从未询问且未授权时允许请求权限`() {
        assertTrue(
            NotificationGate.shouldRequestNotificationPermission(
                permissionGranted = false,
                alreadyAsked = false,
                showRationale = false
            )
        )
    }

    @Test
    fun `已授权时不再请求权限`() {
        assertFalse(
            NotificationGate.shouldRequestNotificationPermission(
                permissionGranted = true,
                alreadyAsked = false,
                showRationale = false
            )
        )
    }

    @Test
    fun `已询问过时不再请求权限`() {
        assertFalse(
            NotificationGate.shouldRequestNotificationPermission(
                permissionGranted = false,
                alreadyAsked = true,
                showRationale = false
            )
        )
    }

    @Test
    fun `用户此前已拒绝需解释时不再请求权限`() {
        assertFalse(
            NotificationGate.shouldRequestNotificationPermission(
                permissionGranted = false,
                alreadyAsked = false,
                showRationale = true
            )
        )
    }

    @Test
    fun `已拒绝且已询问时同样不再请求权限`() {
        assertFalse(
            NotificationGate.shouldRequestNotificationPermission(
                permissionGranted = false,
                alreadyAsked = true,
                showRationale = true
            )
        )
    }

    // ===== 验证码剩余秒数（通知正文取值，纯函数时间边界）=====

    @Test
    fun `周期起点剩余整个周期`() {
        assertEquals(30, NotificationGate.totpRemainingSeconds(nowMillis = 0L, periodSeconds = 30))
    }

    @Test
    fun `周期内逐秒递减`() {
        assertEquals(29, NotificationGate.totpRemainingSeconds(nowMillis = 1_000L, periodSeconds = 30))
        assertEquals(10, NotificationGate.totpRemainingSeconds(nowMillis = 20_000L, periodSeconds = 30))
    }

    @Test
    fun `周期最后一秒剩余一秒且绝不为零`() {
        assertEquals(1, NotificationGate.totpRemainingSeconds(nowMillis = 29_000L, periodSeconds = 30))
    }

    @Test
    fun `跨周期边界重新计满`() {
        assertEquals(30, NotificationGate.totpRemainingSeconds(nowMillis = 30_000L, periodSeconds = 30))
        assertEquals(60, NotificationGate.totpRemainingSeconds(nowMillis = 60_000L, periodSeconds = 60))
    }

    @Test
    fun `非法周期按默认三十秒兜底`() {
        assertEquals(
            NotificationGate.DEFAULT_TOTP_PERIOD_SECONDS,
            NotificationGate.totpRemainingSeconds(nowMillis = 0L, periodSeconds = 0)
        )
        assertEquals(
            NotificationGate.DEFAULT_TOTP_PERIOD_SECONDS,
            NotificationGate.totpRemainingSeconds(nowMillis = 0L, periodSeconds = -5)
        )
    }
}
