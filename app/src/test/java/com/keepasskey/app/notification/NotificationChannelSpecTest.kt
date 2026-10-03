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
            setOf(
                NotificationChannelSpec.UNLOCKED_STATUS,
                NotificationChannelSpec.AUTOFILL_TOTP,
                // ISSUE-P3-298 ④：后台同步失败通知通道（低重要度静默，系统设置可关）
                NotificationChannelSpec.SYNC_FAILURE,
                // ISSUE-P3-324：旧版无障碍自动填充「检测到口令框」通知通道（本通道唯一用户入口）
                NotificationChannelSpec.LEGACY_AUTOFILL
            ),
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
    fun `解锁状态通道为默认重要度（动作按钮可直接点按，§428）`() {
        // §428 走查修订：原 IMPORTANCE_LOW 在 MIUI/HyperOS 上被归入「静默通知」，
        // 动作按钮在折叠态不渲染、须长按才出现。参考实现（KeePassDX / Monica）均以
        // DEFAULT 及以上承载带动作的通知；本通道不需要 heads-up，故取 DEFAULT（静音下沉到通道层）。
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationChannelSpec.UNLOCKED_STATUS.importance
        )
    }

    @Test
    fun `解锁状态通道使用迁移后的新 id`() {
        // 重要度不可程序化修改 ⇒ 必须换新 id；旧 id 由 ensureCreated 显式删除（见 NotificationChannels）
        assertEquals(
            "keepasskey_unlocked_status_v2",
            NotificationChannelSpec.UNLOCKED_STATUS.channelId
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
    fun `同步失败通道为低重要度静默通道（可静音）`() {
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            NotificationChannelSpec.SYNC_FAILURE.importance
        )
    }

    @Test
    fun `三条通知的通知 id 互不相同且为正数`() {
        val unlocked = NotificationChannels.ID_UNLOCKED_STATUS
        val totp = NotificationChannels.ID_AUTOFILL_TOTP
        val syncFailure = NotificationChannels.ID_SYNC_FAILURE

        assertTrue(unlocked > 0)
        assertTrue(totp > 0)
        assertTrue(syncFailure > 0)
        assertEquals(setOf(unlocked, totp, syncFailure).size, 3)
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
