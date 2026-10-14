package com.keepasskey.app.passkey

import com.keepasskey.app.testutil.InMemorySharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-603：保存请求单次消费台账（`SaveRequestLedger`）。
 */
class SaveRequestLedgerTest {

    private val prefs = InMemorySharedPreferences()
    private val ledger = SaveRequestLedger(prefs.context())

    @Test
    fun `未消费令牌判定为未消费`() {
        assertFalse(ledger.isConsumed("token-a"))
    }

    @Test
    fun `登记后同令牌判定为已消费而其它令牌不受影响`() {
        ledger.markConsumed("token-a")
        assertTrue(ledger.isConsumed("token-a"))
        assertFalse(ledger.isConsumed("token-b"))
    }

    @Test
    fun `落盘的是摘要而非令牌明文`() {
        ledger.markConsumed("plain-token-value")
        assertTrue(
            "台账不得以令牌明文为键",
            prefs.storage.keys.none { it.contains("plain-token-value") }
        )
    }

    @Test
    fun `超过有效期后重新判定为未消费并清理陈旧记录`() {
        ledger.markConsumed("token-a")
        val key = prefs.storage.keys.single()
        // 回拨记录时间至超过 TTL
        prefs.storage[key] = System.currentTimeMillis() - SaveRequestLedger.TTL_MS - 1

        assertFalse(ledger.isConsumed("token-a"))
        assertTrue("过期记录应被就地清理", prefs.storage.isEmpty())
    }
}
