package com.keepasskey.app.autofill.legacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutofillActivePromptThrottle] 单元测试（ISSUE-P3-374 AC②）。
 *
 * 判定层（[AutofillActivePromptThrottle.shouldPromptAt]）纯函数穷举 + 实例记忆层
 * （按包独立 / 容量淘汰 / 空白包名 fail-closed）各半。
 */
class AutofillActivePromptThrottleTest {

    // ===== 判定纯函数 =====

    @Test
    fun `首次无记录恒可提示`() {
        assertTrue(AutofillActivePromptThrottle.shouldPromptAt(lastPromptAt = null, now = 1_000L))
    }

    @Test
    fun `冷却期内拒绝到期放行`() {
        val last = 10_000L
        val cooldown = AutofillActivePromptThrottle.COOLDOWN_MS
        assertFalse(
            "冷却起点（elapsed=0）必须拒绝",
            AutofillActivePromptThrottle.shouldPromptAt(last, now = last, cooldownMs = cooldown)
        )
        assertFalse(
            "冷却差 1ms 仍拒绝",
            AutofillActivePromptThrottle.shouldPromptAt(last, now = last + cooldown - 1, cooldownMs = cooldown)
        )
        assertTrue(
            "到期即放行",
            AutofillActivePromptThrottle.shouldPromptAt(last, now = last + cooldown, cooldownMs = cooldown)
        )
        assertTrue(
            "超期更放行",
            AutofillActivePromptThrottle.shouldPromptAt(last, now = last + cooldown + 1, cooldownMs = cooldown)
        )
    }

    @Test
    fun `时钟回拨按未到冷却拒绝`() {
        assertFalse(
            "now < last（elapsed<0）必须 fail-closed 拒绝，防时钟回拨重刷提示",
            AutofillActivePromptThrottle.shouldPromptAt(lastPromptAt = 10_000L, now = 5_000L)
        )
    }

    // ===== 实例记忆层 =====

    @Test
    fun `同包冷却跨包独立`() {
        var now = 0L
        val throttle = AutofillActivePromptThrottle(nowMillis = { now })

        assertTrue("首次提示放行", throttle.shouldPrompt("com.example.app"))
        assertFalse(
            "同包冷却期内拒绝",
            throttle.shouldPrompt("com.example.app")
        )
        assertTrue(
            "不同包互不影响（冷却按包隔离）",
            throttle.shouldPrompt("com.other.app")
        )

        now += AutofillActivePromptThrottle.COOLDOWN_MS
        assertTrue("冷却到期后同包重新放行", throttle.shouldPrompt("com.example.app"))
    }

    @Test
    fun `空白包名恒不提示`() {
        val throttle = AutofillActivePromptThrottle(nowMillis = { 0L })
        assertFalse(throttle.shouldPrompt(""))
        assertFalse(throttle.shouldPrompt("   "))
    }

    @Test
    fun `容量超限按插入序淘汰最旧包`() {
        val now = 0L
        val throttle = AutofillActivePromptThrottle(nowMillis = { now })
        repeat(AutofillActivePromptThrottle.MAX_TRACKED_PACKAGES) { i ->
            assertTrue(throttle.shouldPrompt("pkg$i"))
        }
        // 未超容量：同包冷却期内仍拒绝
        assertFalse(throttle.shouldPrompt("pkg1"))
        // 灌入超容量一条 ⇒ 插入序最旧的 pkg0 被淘汰
        assertTrue(throttle.shouldPrompt("pkg_overflow"))
        // 被淘汰者记忆已清 ⇒ 重新视为首次（时钟未动，存活包冷却仍有效）
        assertTrue(throttle.shouldPrompt("pkg0"))
        assertFalse("存活包仍在冷却", throttle.shouldPrompt("pkg2"))
    }

    @Test
    fun `reset 清空全部记忆`() {
        var now = 0L
        val throttle = AutofillActivePromptThrottle(nowMillis = { now })
        assertTrue(throttle.shouldPrompt("com.example.app"))
        throttle.reset()
        // 冷却未到期，但记忆已清 ⇒ 重新放行
        assertTrue(throttle.shouldPrompt("com.example.app"))
    }
}
