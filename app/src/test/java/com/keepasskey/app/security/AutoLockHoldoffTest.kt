package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-366 AC②：长任务挂锁闸对称性单测（纯 Kotlin，无会话参与）。
 *
 * 锁定的是三条硬语义：
 * 1. 挂起期间的锁定请求**延迟暂存而非丢弃**，depth 归零后返回补执行原因；
 * 2. **嵌套计数对称**——内层结束不补锁，最外层结束才补锁；漏配对方向的危害在此显式登记；
 * 3. 多余的 end（无配对 begin）钳制在 0，不产生补锁原因、不使深度为负。
 */
class AutoLockHoldoffTest {

    @Test
    fun `未挂锁时锁定请求不暂存直接返回 false`() {
        val holdoff = AutoLockHoldoff()

        assertFalse(holdoff.defer("熄屏"))
        assertNull(holdoff.end())
        assertFalse(holdoff.isHolding)
    }

    @Test
    fun `挂起期间暂存的请求在深度归零后返回补执行原因`() {
        val holdoff = AutoLockHoldoff()

        holdoff.begin()
        assertTrue(holdoff.isHolding)
        assertTrue(holdoff.defer("后台超时"))

        assertEquals("后台超时", holdoff.end())
        assertFalse(holdoff.isHolding)
        // 原因一次性消费：再次结束无锁可补
        assertNull(holdoff.end())
    }

    @Test
    fun `嵌套挂起仅在最外层结束时返回补执行原因`() {
        val holdoff = AutoLockHoldoff()

        holdoff.begin()
        holdoff.begin()
        assertTrue(holdoff.defer("熄屏"))

        assertNull("内层结束不得补执行", holdoff.end())
        assertTrue("内层结束仍应挂锁", holdoff.isHolding)
        assertEquals("熄屏", holdoff.end())
        assertFalse(holdoff.isHolding)
    }

    @Test
    fun `多次暂存保留最近原因绝不丢锁`() {
        val holdoff = AutoLockHoldoff()

        holdoff.begin()
        assertTrue(holdoff.defer("熄屏"))
        assertTrue(holdoff.defer("回前台超时补偿"))

        assertEquals("回前台超时补偿", holdoff.end())
    }

    @Test
    fun `多余的结束不产生补锁原因且深度钳制在零`() {
        val holdoff = AutoLockHoldoff()

        assertNull(holdoff.end())
        assertNull(holdoff.end())

        // 深度未被拖成负数：此后挂起行为与全新实例一致
        holdoff.begin()
        assertTrue(holdoff.defer("保存"))
        assertEquals("保存", holdoff.end())
    }

    @Test
    fun `漏配对的挂起使闸保持挂锁状态（挂点必须 tryfinally 成对）`() {
        val holdoff = AutoLockHoldoff()

        holdoff.begin()
        holdoff.begin()
        holdoff.end()

        // 只结束了一层：depth 仍 > 0 ⇒ 挂锁持续。这正是挂点必须 try/finally 成对的
        // 根据（漏配对会令挂锁永不恢复），本用例将该风险显式登记
        assertTrue(holdoff.isHolding)
        assertTrue(holdoff.defer("仍未恢复"))
    }
}
