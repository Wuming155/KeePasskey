package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段签名级屏蔽仓库单元测试（ISSUE-P3-43 ② 验收标准 3：非法输入 fail-closed）。
 *
 * 覆盖内存语义下的完整生命周期：按「包名 + 域 + 角色」三维键屏蔽 → 命中 → 解除 → 清空，
 * 以及非法包名在**判定与写入两个方向**上的 fail-closed 语义。
 * （签名不可逆，故列表断言只能对**条数**进行——这本身就是设计约束的体现。）
 */
class AutofillFieldBlocklistStoreTest {

    private fun store() = AutofillFieldBlocklistStore(null)

    @Test
    fun `初始为空且任意上下文均未屏蔽`() {
        val store = store()

        assertEquals(0, store.blockedSignatures.value.size)
        assertFalse(store.isBlocked("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD))
        assertFalse(store.isBlocked("com.example.bank", null, AutofillFieldRole.USERNAME))
    }

    @Test
    fun `屏蔽后同包名同域同角色命中且计数为一`() {
        val store = store()

        assertTrue(store.block("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD))

        assertTrue(store.isBlocked("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD))
        assertEquals(1, store.blockedSignatures.value.size)
    }

    @Test
    fun `同包名下不同角色或不同域互不影响`() {
        val store = store()
        store.block("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertFalse(store.isBlocked("com.example.bank", "accounts.example.com", AutofillFieldRole.USERNAME))
        assertFalse(store.isBlocked("com.example.bank", "login.example.org", AutofillFieldRole.PASSWORD))
        assertFalse(store.isBlocked("com.other.app", "accounts.example.com", AutofillFieldRole.PASSWORD))
    }

    @Test
    fun `屏蔽判定沿用大小写与空白归一`() {
        val store = store()
        store.block("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertTrue(store.isBlocked("COM.EXAMPLE.BANK", "  ACCOUNTS.Example.COM.  ", AutofillFieldRole.PASSWORD))
    }

    @Test
    fun `重复屏蔽同一上下文返回失败且不产生重复条目`() {
        val store = store()
        store.block("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertFalse(store.block("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD))
        assertEquals(1, store.blockedSignatures.value.size)
    }

    @Test
    fun `非法包名写入失败但判定保持 fail-closed 为已屏蔽`() {
        val store = store()

        // 写入方向：拒绝入库，不产生条目
        val invalid = listOf("", "   ", "bank", "1com.example.bank", "com..example")
        invalid.forEach { pkg ->
            assertFalse("非法包名不应写入: $pkg", store.block(pkg, "a.com", AutofillFieldRole.PASSWORD))
        }
        assertEquals(0, store.blockedSignatures.value.size)

        // 判定方向：fail-closed——不可识别即按已屏蔽处理（宁可少填，不下发到无法识别的目标）
        invalid.forEach { pkg ->
            assertTrue("非法包名必须 fail-closed: $pkg", store.isBlocked(pkg, "a.com", AutofillFieldRole.PASSWORD))
        }
    }

    @Test
    fun `解除屏蔽后恢复填充`() {
        val store = store()
        store.block("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertTrue(store.unblock("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD))

        assertFalse(store.isBlocked("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD))
        assertEquals(0, store.blockedSignatures.value.size)
    }

    @Test
    fun `解除未屏蔽的上下文返回失败`() {
        val store = store()

        assertFalse(store.unblock("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD))
    }

    @Test
    fun `全部清除返回清除条数且归零`() {
        val store = store()
        store.block("com.example.bank", "a.example.com", AutofillFieldRole.PASSWORD)
        store.block("com.example.bank", "a.example.com", AutofillFieldRole.USERNAME)
        store.block("com.other.app", null, AutofillFieldRole.PASSWORD)

        assertEquals(3, store.clearAll())
        assertEquals(0, store.blockedSignatures.value.size)
        assertFalse(store.isBlocked("com.example.bank", "a.example.com", AutofillFieldRole.PASSWORD))

        // 空库清除是无害 no-op
        assertEquals(0, store.clearAll())
    }
}
