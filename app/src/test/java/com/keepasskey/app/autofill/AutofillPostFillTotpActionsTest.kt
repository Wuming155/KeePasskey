package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.EntryTotpSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [runPostFillTotpActions]（填充后 TOTP 二次动作共用内核，ISSUE-P3-186）单元测试。
 *
 * 覆盖策略判定与超时放弃路径：双开关闸门、按偏好分流复制 / 通知、无 TOTP 与计算异常
 * 的零动作降级、硬超时放弃不抛错。协作方全部为函数参数（无 Android 依赖，纯 JVM）。
 */
class AutofillPostFillTotpActionsTest {

    private val snapshot = EntryTotpSnapshot(
        code = "123456",
        periodSeconds = 30,
        digits = 6,
        algorithm = "SHA1"
    )

    @Test
    fun `两开关皆关时不触达仓库且零副作用`() = runTest {
        var calculated = false
        runPostFillTotpActions(
            entryId = "entry-1",
            copyEnabled = false,
            notifyEnabled = false,
            calculateTotp = { calculated = true; snapshot },
            copyToClipboard = {},
            publishNotification = { _, _ -> }
        )
        assertFalse("双开关皆关时不得发起 TOTP 计算（零额外开销）", calculated)
    }

    @Test
    fun `条目标识空白时零动作`() = runTest {
        var calculated = false
        runPostFillTotpActions(
            entryId = "  ",
            copyEnabled = true,
            notifyEnabled = true,
            calculateTotp = { calculated = true; snapshot },
            copyToClipboard = {},
            publishNotification = { _, _ -> }
        )
        assertFalse(calculated)
    }

    @Test
    fun `仅开复制时复制且不发通知`() = runTest {
        var copied: String? = null
        var notified = false
        runPostFillTotpActions(
            entryId = "entry-1",
            copyEnabled = true,
            notifyEnabled = false,
            calculateTotp = { snapshot },
            copyToClipboard = { copied = it },
            publishNotification = { _, _ -> notified = true }
        )
        assertEquals("123456", copied)
        assertFalse(notified)
    }

    @Test
    fun `仅开通知时发布且不复制`() = runTest {
        var copied: String? = null
        var publishedPeriod = -1
        runPostFillTotpActions(
            entryId = "entry-1",
            copyEnabled = false,
            notifyEnabled = true,
            calculateTotp = { snapshot },
            copyToClipboard = { copied = it },
            publishNotification = { _, periodSeconds -> publishedPeriod = periodSeconds }
        )
        assertEquals(null, copied)
        assertEquals(30, publishedPeriod)
    }

    @Test
    fun `无TOTP条目（快照为null）零动作`() = runTest {
        var copied: String? = null
        var notified = false
        runPostFillTotpActions(
            entryId = "entry-1",
            copyEnabled = true,
            notifyEnabled = true,
            calculateTotp = { null },
            copyToClipboard = { copied = it },
            publishNotification = { _, _ -> notified = true }
        )
        assertEquals(null, copied)
        assertFalse(notified)
    }

    @Test
    fun `TOTP计算异常按无快照处理不外抛`() = runTest {
        var copied: String? = null
        runPostFillTotpActions(
            entryId = "entry-1",
            copyEnabled = true,
            notifyEnabled = false,
            calculateTotp = { throw IllegalStateException("会话已锁定") },
            copyToClipboard = { copied = it },
            publishNotification = { _, _ -> }
        )
        assertEquals(null, copied)
    }

    @Test
    fun `验证码空白不复制`() = runTest {
        var copied: String? = null
        runPostFillTotpActions(
            entryId = "entry-1",
            copyEnabled = true,
            notifyEnabled = false,
            calculateTotp = { snapshot.copy(code = "  ") },
            copyToClipboard = { copied = it },
            publishNotification = { _, _ -> }
        )
        assertEquals(null, copied)
    }

    @Test
    fun `硬超时放弃且不阻断调用方`() = runTest {
        var copied: String? = null
        var notified = false
        runPostFillTotpActions(
            entryId = "entry-1",
            copyEnabled = true,
            notifyEnabled = true,
            calculateTotp = {
                delay(2_000) // 远超超时预算（runTest 虚拟时间，瞬时完成）
                snapshot
            },
            copyToClipboard = { copied = it },
            publishNotification = { _, _ -> notified = true },
            timeoutMillis = 500
        )
        assertEquals(null, copied)
        assertFalse("超时即放弃，不得部分执行二次动作", notified)
    }
}
