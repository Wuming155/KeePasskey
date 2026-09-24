package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CredentialLastUsedStore] 单元测试（ISSUE-P3-298 ⑥）。
 *
 * `context = null` 的纯 JVM 注入走进程内存回退路径（与 `AutofillLastFilledStoreTest`
 * 同一口径）；断言记录 / 读取 / 空白忽略与跨实例可见（@Singleton 语义）。
 */
class CredentialLastUsedStoreTest {

    @Test
    fun `记录后可读取上次使用时刻`() {
        val store = CredentialLastUsedStore(null)
        assertNull(store.lastUsedMillis("entry-a"))
        store.record("entry-a")
        assertNotNull(store.lastUsedMillis("entry-a"))
    }

    @Test
    fun `同一实例跨写入点共享（单例语义）`() {
        val store = CredentialLastUsedStore(null)
        store.record("entry-b")
        // 记录点（交付 Activity）与读取点（候选组装器）持有同一 @Singleton 实例
        assertNotNull(store.lastUsedMillis("entry-b"))
    }

    @Test
    fun `空白条目标识忽略`() {
        val store = CredentialLastUsedStore(null)
        store.record("   ")
        assertNull(store.lastUsedMillis(""))
        store.record("entry-c")
        // 记录时去除首尾空白，读取时按同一归一口径命中
        assertNotNull(store.lastUsedMillis("  entry-c "))
    }

    @Test
    fun `未记录的条目返回 null`() {
        val store = CredentialLastUsedStore(null)
        store.record("entry-d")
        assertNull(store.lastUsedMillis("entry-e"))
    }

    @Test
    fun `时间戳随再记录单调更新`() {
        val store = CredentialLastUsedStore(null)
        store.record("entry-f")
        val first = store.lastUsedMillis("entry-f")
        store.record("entry-f")
        val second = store.lastUsedMillis("entry-f")
        assertNotNull(first)
        assertNotNull(second)
        // 同进程内两次记录，时刻戳不得回退（毫秒精度下允许相等）
        assertTrue(second!! >= first!!)
    }
}
