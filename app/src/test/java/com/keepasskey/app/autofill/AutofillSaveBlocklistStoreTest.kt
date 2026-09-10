package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.AutofillBlocklistStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存侧独立黑名单单元测试（ISSUE-P3-43 ③ 验收标准 3：非法输入 fail-closed）。
 *
 * 覆盖内存语义下的完整生命周期与 fail-closed 判定，并显式断言
 * **保存侧名单与填充侧名单相互独立**（同一包名在两侧状态可不同——这正是分离的动机）。
 */
class AutofillSaveBlocklistStoreTest {

    private fun saveStore() = AutofillSaveBlocklistStore(null)

    @Test
    fun `初始为空且合法包名未命中`() {
        val store = saveStore()

        assertTrue(store.blockedPackages.value.isEmpty())
        assertFalse(store.isSaveBlocked("com.example.app"))
    }

    @Test
    fun `加入后命中且进入列表`() {
        val store = saveStore()

        assertTrue(store.add("com.example.app"))

        assertTrue(store.isSaveBlocked("com.example.app"))
        assertEquals(listOf("com.example.app"), store.blockedPackages.value)
    }

    @Test
    fun `重复加入返回失败且不产生重复条目`() {
        val store = saveStore()
        store.add("com.example.app")

        assertFalse(store.add("com.example.app"))
        assertEquals(1, store.blockedPackages.value.size)
    }

    @Test
    fun `包名大小写与首尾空白归一`() {
        val store = saveStore()

        assertTrue(store.add("  Com.Example.App  "))

        assertTrue(store.isSaveBlocked("com.example.app"))
        assertEquals(listOf("com.example.app"), store.blockedPackages.value)
    }

    @Test
    fun `移除后不再命中`() {
        val store = saveStore()
        store.add("com.example.app")

        assertTrue(store.remove("com.example.app"))

        assertFalse(store.isSaveBlocked("com.example.app"))
        assertTrue(store.blockedPackages.value.isEmpty())
    }

    @Test
    fun `非法包名写入失败但判定保持 fail-closed 为已禁止`() {
        val store = saveStore()

        val invalid = listOf("", "   ", "app", "1com.example.app", "com..example")
        invalid.forEach { pkg ->
            assertFalse("非法包名不应写入: $pkg", store.add(pkg))
            assertTrue("非法包名必须 fail-closed: $pkg", store.isSaveBlocked(pkg))
        }
        assertTrue(store.blockedPackages.value.isEmpty())
    }

    @Test
    fun `保存侧名单与填充侧名单相互独立`() {
        val saveStore = saveStore()
        val fillStore = AutofillBlocklistStore(null)

        // 只禁保存，不禁填充：这是与包级填充黑名单分离的动机，必须锁定
        saveStore.add("com.example.app")
        assertTrue(saveStore.isSaveBlocked("com.example.app"))
        assertFalse(fillStore.isBlocked("com.example.app"))

        // 反向亦然：填充黑名单不影响保存提示
        fillStore.add("com.other.app")
        assertFalse(saveStore.isSaveBlocked("com.other.app"))
    }
}
