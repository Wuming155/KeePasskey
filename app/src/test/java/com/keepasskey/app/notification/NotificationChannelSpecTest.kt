package com.keepasskey.app.notification

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-18 验收标准 1：「建立通知通道」的**声明式契约**单测。
 *
 * 说明（如实登记测试边界）：`NotificationManager.createNotificationChannel` 的实际调用必须在
 * 设备/模拟器上验证，本工作流禁用真机与模拟器，故此处锁定的是通道契约本身——
 * 通道齐备、id 唯一且带应用前缀、名称/描述资源齐备、重要度符合设计取舍、通知 id 合法。
 * 这些断言足以在重构中拦住「少建一条通道」「误用 IMPORTANCE_NONE（通知永不显示）」
 * 「两个通道 id 撞车」这类会直接导致通知静默失效的回归。
 *
 * `NotificationManager.IMPORTANCE_*` 为编译期常量（已内联），不触发 Android 框架实例化。
 */
class NotificationChannelSpecTest {

    @Test
    fun `必需通道齐备且无冗余`() {
        assertEquals(
            setOf(NotificationChannelSpec.UNLOCKED_STATUS, NotificationChannelSpec.AUTOFILL_TOTP),
            NotificationChannelSpec.entries.toSet()
        )
    }

    @Test
    fun `通道 id 唯一且非空`() {
        val ids = NotificationChannelSpec.entries.map { it.channelId }

        assertTrue(ids.none { it.isBlank() })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `通道 id 统一带应用命名空间前缀`() {
        assertTrue(
            NotificationChannelSpec.entries.all { it.channelId.startsWith(CHANNEL_ID_PREFIX) }
        )
    }

    @Test
    fun `每个通道都声明了名称与描述资源`() {
        assertTrue(
            NotificationChannelSpec.entries.all { it.nameRes != 0 && it.descriptionRes != 0 }
        )
    }

    @Test
    fun `解锁状态通道为低重要度静默通道`() {
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            NotificationChannelSpec.UNLOCKED_STATUS.importance
        )
    }

    @Test
    fun `验证码通道为默认重要度可见通道`() {
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationChannelSpec.AUTOFILL_TOTP.importance
        )
    }

    @Test
    fun `两条通知的通知 id 互不相同且为正数`() {
        val unlocked = NotificationChannels.ID_UNLOCKED_STATUS
        val totp = NotificationChannels.ID_AUTOFILL_TOTP

        assertTrue(unlocked > 0)
        assertTrue(totp > 0)
        assertNotEquals(unlocked, totp)
    }

    @Test
    fun `通知小图标资源已配置`() {
        assertNotEquals(0, NotificationChannels.SMALL_ICON_RES)
    }

    private companion object {
        /** 通道 id 前缀：避免与系统/其它应用通道命名碰撞 */
        const val CHANNEL_ID_PREFIX = "keepasskey_"
    }
}
