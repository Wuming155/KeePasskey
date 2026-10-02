package com.keepasskey.app.ui.components

import com.keepasskey.app.testutil.InMemorySharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-445 AC②：提示关闭态持久化语义。
 *
 * 失效形态：关闭态不持久（每次进页都复现 = 骚扰）或各提示位互相牵连（关一个全消失）。
 * 持久面为 SharedPreferences 布尔（无敏感数据），用内存替身验证读写与独立性。
 */
class HelpTipStoreTest {

    @Test
    fun `未关闭的提示位默认呈现`() {
        val prefs = InMemorySharedPreferences()
        val store = HelpTipStore(prefs.context())

        HelpTip.entries.forEach { tip ->
            assertTrue("新安装必须呈现 $tip（引导看不到等于没做）", store.shouldShow(tip))
        }
    }

    @Test
    fun `关闭即持久化且幂等`() {
        val prefs = InMemorySharedPreferences()
        val store = HelpTipStore(prefs.context())

        store.dismiss(HelpTip.UNLOCK_KEYFILE)
        store.dismiss(HelpTip.UNLOCK_KEYFILE)

        assertFalse("关闭后同实例不得再呈现", store.shouldShow(HelpTip.UNLOCK_KEYFILE))
        assertTrue(
            "关闭标记必须持久化到 SharedPreferences（跨进程重启仍生效）",
            prefs.storage["help_tip_unlock_keyfile"] == true
        )
    }

    @Test
    fun `各提示位相互独立`() {
        val prefs = InMemorySharedPreferences()
        val store = HelpTipStore(prefs.context())

        store.dismiss(HelpTip.CLOUD_SYNC_TEST_CONNECTION)

        assertFalse(store.shouldShow(HelpTip.CLOUD_SYNC_TEST_CONNECTION))
        assertTrue("关闭一个提示位不得牵连其余", store.shouldShow(HelpTip.AUTOFILL_CHANNEL))
        assertTrue(store.shouldShow(HelpTip.UNLOCK_KEYFILE))
    }

    @Test
    fun `新实例读取既有关闭标记（跨实例持久）`() {
        val prefs = InMemorySharedPreferences()
        HelpTipStore(prefs.context()).dismiss(HelpTip.AUTOFILL_CHANNEL)

        val secondStore = HelpTipStore(prefs.context())
        assertFalse("新实例必须读到已关闭标记", secondStore.shouldShow(HelpTip.AUTOFILL_CHANNEL))
        assertTrue(secondStore.shouldShow(HelpTip.UNLOCK_KEYFILE))
    }
}
