package com.keepasskey.sync.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [SyncOpenResult.RemoteRejectedUsingCache] 的 `equals` / `hashCode` 契约（`ISSUE-P3-558`）。
 *
 * 整改前 `equals` 只比 `localBytes`（忽略 `cause`），而 `hashCode` 计入 `cause`
 * （`Throwable.hashCode()` 为身份哈希）⇒ 同字节、异 `cause` 的实例 `equals == true`
 * 而 `hashCode` 不同，`HashSet` / `HashMap` 查找会失配。
 */
class SyncOpenResultEqualityTest {

    @Test
    fun `同字节异 cause 的两实例 equals 与 hashCode 同口径`() {
        val first = SyncOpenResult.RemoteRejectedUsingCache(
            localBytes = byteArrayOf(1, 2, 3),
            cause = IllegalStateException("401")
        )
        val second = SyncOpenResult.RemoteRejectedUsingCache(
            localBytes = byteArrayOf(1, 2, 3),
            cause = IllegalArgumentException("503")
        )

        assertEquals("cause 非值语义：同字节即相等", first, second)
        assertEquals("equals 相等则 hashCode 必须相等", first.hashCode(), second.hashCode())
    }

    @Test
    fun `异字节的实例不相等`() {
        val first = SyncOpenResult.RemoteRejectedUsingCache(byteArrayOf(1, 2, 3), null)
        val second = SyncOpenResult.RemoteRejectedUsingCache(byteArrayOf(9), null)

        assertNotEquals(first, second)
        assertNotEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `与兄弟类型 RemoteUnreachableUsingCache 的本地字节口径一致`() {
        val bytes = byteArrayOf(4, 5, 6)
        assertEquals(
            "两者都只比 localBytes 的哈希",
            SyncOpenResult.RemoteUnreachableUsingCache(bytes).hashCode(),
            SyncOpenResult.RemoteRejectedUsingCache(bytes, null).hashCode()
        )
    }
}
