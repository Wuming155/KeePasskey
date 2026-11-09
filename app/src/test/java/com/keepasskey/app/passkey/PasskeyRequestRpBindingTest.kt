package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Test

class PasskeyRequestRpBindingTest {
    // ── 三值对照：缺失 RP 必须与「相等」区分开（ENG-20261028-06） ──────────

    @Test
    fun `显式RP相等归一化后判为匹配`() {
        assertEquals(
            RequestRpBinding.MATCHED,
            PasskeyRequestRpBinding.evaluate("example.com", "example.com")
        )
        assertEquals(
            "大小写、尾点、scheme 与端口都不得改变结论",
            RequestRpBinding.MATCHED,
            PasskeyRequestRpBinding.evaluate("https://EXAMPLE.COM.:443", "example.com")
        )
    }

    @Test
    fun `显式RP不相等判为不匹配`() {
        listOf(
            "login.example.com" to "example.com",
            "example.com" to "login.example.com",
            "other.example" to "example.com",
            "example.com" to ""
        ).forEach { (requested, stored) ->
            assertEquals(
                "$requested vs $stored",
                RequestRpBinding.MISMATCHED,
                PasskeyRequestRpBinding.evaluate(requested, stored)
            )
        }
    }

    @Test
    fun `缺失RP是独立状态而不是匹配`() {
        assertEquals(
            "缺失 RP 没有请求侧参照，不得与 MATCHED 混同（旧布尔口径把两者都读作 true）",
            RequestRpBinding.MISSING,
            PasskeyRequestRpBinding.evaluate("", "example.com")
        )
        assertEquals(RequestRpBinding.MISSING, PasskeyRequestRpBinding.evaluate("   ", "example.com"))
        assertEquals(
            "凭据 RP 也为空时仍是缺失，不是匹配",
            RequestRpBinding.MISSING,
            PasskeyRequestRpBinding.evaluate("", "")
        )
    }
}
