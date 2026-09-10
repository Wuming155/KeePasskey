package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AutofillLastFilledStore] 单元测试（ISSUE-P3-39）。
 *
 * 以 null 上下文注入，覆盖内存回退语义（记录 / 读回 / 归一 / 清除），
 * 生产环境走 SharedPreferences（同一读写契约）。
 */
class AutofillLastFilledStoreTest {

    @Test
    fun `记录后可读回`() {
        val store = AutofillLastFilledStore(null)
        assertNull(store.lastFilledEntryId())

        store.record("0000000000000000000000000000000A")

        assertEquals("0000000000000000000000000000000A", store.lastFilledEntryId())
    }

    @Test
    fun `空白输入被忽略`() {
        val store = AutofillLastFilledStore(null)
        store.record("   ")
        assertNull(store.lastFilledEntryId())
    }

    @Test
    fun `记录时去除首尾空白`() {
        val store = AutofillLastFilledStore(null)
        store.record("  ABC  ")
        assertEquals("ABC", store.lastFilledEntryId())
    }

    @Test
    fun `clear 后清空`() {
        val store = AutofillLastFilledStore(null)
        store.record("ABC")
        store.clear()
        assertNull(store.lastFilledEntryId())
    }
}
