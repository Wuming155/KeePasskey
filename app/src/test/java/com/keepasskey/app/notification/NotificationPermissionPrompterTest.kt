package com.keepasskey.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-18 验收标准 2：「运行时权限请求流程，用户拒绝时不得崩溃或反复弹窗」。
 *
 * 以「拾取到的 Android 事实 → 决策」这一无 Activity 依赖的入口驱动状态机
 * （`context` 传 null，走「权限不可用」的降级分支，不触达任何 Android 框架方法），
 * 断言的核心契约是：**同一安装周期内至多请求一次**，且「已询问」标志先于弹窗落盘。
 */
class NotificationPermissionPrompterTest {

    private val askStore = FakeNotificationPermissionAskStore()

    private val prompter = NotificationPermissionPrompter(context = null, askStore = askStore)

    @Test
    fun `从未询问且未授权时请求一次并落下已询问标志`() {
        val decision = prompter.resolveDecision(permissionGranted = false, showRationale = false)

        assertEquals(NotificationPermissionDecision.REQUEST, decision)
        assertTrue("发起请求前必须先落「已询问」标志（进程被杀也不重复打扰）", askStore.wasAsked())
    }

    @Test
    fun `同一进程内二次判定不再请求`() {
        prompter.resolveDecision(permissionGranted = false, showRationale = false)

        val second = prompter.resolveDecision(permissionGranted = false, showRationale = false)

        assertEquals(NotificationPermissionDecision.SKIP, second)
    }

    @Test
    fun `用户已拒绝且系统要求解释时不再请求`() {
        val decision = prompter.resolveDecision(permissionGranted = false, showRationale = true)

        assertEquals(NotificationPermissionDecision.SKIP, decision)
        assertFalse("拒绝态无需再落标志（闸门已由「需解释」闭环）", askStore.wasAsked())
    }

    @Test
    fun `已授权时不请求也不落标志`() {
        val decision = prompter.resolveDecision(permissionGranted = true, showRationale = false)

        assertEquals(NotificationPermissionDecision.SKIP, decision)
        assertFalse(askStore.wasAsked())
    }

    @Test
    fun `已持久化询问标志时跨冷启动仍不重复请求`() {
        val restored = NotificationPermissionPrompter(
            context = null,
            askStore = FakeNotificationPermissionAskStore(asked = true)
        )

        assertEquals(
            NotificationPermissionDecision.SKIP,
            restored.resolveDecision(permissionGranted = false, showRationale = false)
        )
    }

    @Test
    fun `无上下文时权限按不可用处理而非抛异常`() {
        // 纯 JVM 单测注入 null 上下文的降级语义：不崩溃、按「无权限」处理（相关通知静默不发）
        assertFalse(prompter.isGranted())
    }
}
