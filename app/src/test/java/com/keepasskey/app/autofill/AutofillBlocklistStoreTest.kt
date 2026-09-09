package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.AutofillBlocklistStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动填充黑名单仓库单元测试（TASK-44）。
 *
 * 覆盖黑名单完整生命周期的数据底座：新增 → 命中判定 → 重复拒绝 → 删除，
 * 以及非法包名 fail-closed 拒绝与无持久化层（纯 JVM 注入 null Context）的内存语义。
 * 进程内 SharedPreferences 依赖 Android 框架，真实持久化由端到端/真机回归覆盖，
 * 本用例聚焦契约与边界（与 `ExtendedSettingsStore` 的可测性取舍一致）。
 */
class AutofillBlocklistStoreTest {

    private fun store() = AutofillBlocklistStore(null)

    @Test
    fun `初始黑名单为空且任何包名均未命中`() {
        val store = store()

        assertTrue(store.blockedPackages.value.isEmpty())
        assertFalse(store.isBlocked("com.example.bank"))
        assertFalse(store.isBlocked(""))
    }

    @Test
    fun `新增合法包名后命中且进入列表`() {
        val store = store()

        assertTrue(store.add("com.example.bank"))

        assertTrue(store.isBlocked("com.example.bank"))
        assertEquals(listOf("com.example.bank"), store.blockedPackages.value)
    }

    @Test
    fun `重复新增同一包名返回失败且不产生重复条目`() {
        val store = store()
        store.add("com.example.bank")

        assertFalse(store.add("com.example.bank"))
        assertEquals(1, store.blockedPackages.value.size)
    }

    @Test
    fun `包名大小写与首尾空白归一`() {
        val store = store()

        assertTrue(store.add("  Com.Example.Bank  "))

        assertTrue(store.isBlocked("com.example.bank"))
        assertEquals(listOf("com.example.bank"), store.blockedPackages.value)
    }

    @Test
    fun `删除命中项后不再屏蔽`() {
        val store = store()
        store.add("com.example.bank")

        assertTrue(store.remove("com.example.bank"))

        assertFalse(store.isBlocked("com.example.bank"))
        assertTrue(store.blockedPackages.value.isEmpty())
    }

    @Test
    fun `删除不存在的包名返回失败`() {
        val store = store()

        assertFalse(store.remove("com.example.bank"))
    }

    @Test
    fun `非法包名一律拒绝入库以防假屏蔽`() {
        val store = store()

        // 空串、单段、以数字开头、含连字符、含路径、纯点号
        val invalid = listOf(
            "",
            "   ",
            "bank",
            "1com.example.bank",
            "com-example-bank",
            "com.example.bank/login",
            "..",
            "com..example"
        )
        invalid.forEach { pkg ->
            assertFalse("非法包名不应入库: $pkg", store.add(pkg))
        }

        assertTrue(store.blockedPackages.value.isEmpty())
    }

    @Test
    fun `黑名单按包名升序输出`() {
        val store = store()

        store.add("com.zebra.app")
        store.add("com.alpha.app")
        store.add("com.middle.app")

        assertEquals(
            listOf("com.alpha.app", "com.middle.app", "com.zebra.app"),
            store.blockedPackages.value
        )
    }
}
