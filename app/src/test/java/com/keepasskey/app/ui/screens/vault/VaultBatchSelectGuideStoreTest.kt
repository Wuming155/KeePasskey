package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.testutil.InMemorySharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-360 AC④b：首次长按进入批量模式的一次性引导标记。
 *
 * 失效形态：标记不持久（每次进列表页都弹引导 = 骚扰）或首点不弹（引导永远看不到）。
 * 持久面为 SharedPreferences 布尔（无敏感数据），用内存替身验证消费语义与持久化落位。
 */
class VaultBatchSelectGuideStoreTest {

    @Test
    fun `首次消费返回 true，之后恒 false（持久化置位）`() {
        val prefs = InMemorySharedPreferences()
        val store = BatchSelectGuideStore(prefs.context())

        assertTrue("首次长按必须返回 true（弹出引导）", store.consumeFirstGuide())
        assertFalse("第二次起不得重复弹", store.consumeFirstGuide())
        assertFalse("同实例连续消费仍为 false", store.consumeFirstGuide())
        assertTrue(
            "标记必须持久化到 SharedPreferences（跨 ViewModel / 重启仍生效）",
            prefs.storage["batch_select_guided"] == true
        )
    }

    @Test
    fun `新实例读取既有标记不再引导（跨实例持久）`() {
        val prefs = InMemorySharedPreferences()
        assertTrue(BatchSelectGuideStore(prefs.context()).consumeFirstGuide())

        val secondStore = BatchSelectGuideStore(prefs.context())
        assertFalse("新实例必须读到已置位标记", secondStore.consumeFirstGuide())
    }
}
