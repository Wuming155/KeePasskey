package com.keepasskey.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutofillSessionGrantStore] 与 [AutofillAuthenticationPolicy] 单元测试（ISSUE-P3-42）。
 *
 * 以注入的假时钟覆盖 TTL 语义（无需 Android `SystemClock`）。
 */
class AutofillSessionGrantStoreTest {

    private var now = 0L

    private fun newStore(ttlMillis: Long = 1_000L) =
        AutofillSessionGrantStore(ttlMillis = ttlMillis, elapsedRealtime = { now })

    @Test
    fun `授权后同上下文命中`() {
        val store = newStore()
        val context = AutofillGrantContext("com.example.app", "github.com")

        store.grant(context)

        assertTrue(store.isGranted(context))
    }

    @Test
    fun `TTL 到期后失效`() {
        val store = newStore(ttlMillis = 1_000L)
        val context = AutofillGrantContext("com.example.app", "github.com")
        store.grant(context)

        now = 999L
        assertTrue(store.isGranted(context))

        now = 1_000L
        assertFalse(store.isGranted(context))
    }

    @Test
    fun `包名或域不匹配即失效`() {
        val store = newStore()
        store.grant(AutofillGrantContext("com.example.app", "github.com"))

        assertFalse(store.isGranted(AutofillGrantContext("com.other.app", "github.com")))
        assertFalse(store.isGranted(AutofillGrantContext("com.example.app", "gitlab.com")))
        assertFalse(store.isGranted(AutofillGrantContext("com.example.app", null)))
    }

    @Test
    fun `上下文归一化后可命中`() {
        val store = newStore()
        store.grant(AutofillGrantContext("  COM.Example.App  ", "https://WWW.GitHub.com/login"))

        assertTrue(store.isGranted(AutofillGrantContext("com.example.app", "github.com")))
    }

    @Test
    fun `clear 后失效`() {
        val store = newStore()
        val context = AutofillGrantContext("com.example.app", "github.com")
        store.grant(context)

        store.clear()

        assertFalse(store.isGranted(context))
    }

    @Test
    fun `未授权时任何上下文都不命中`() {
        val store = newStore()
        assertFalse(store.isGranted(AutofillGrantContext("com.example.app", "github.com")))
    }

    @Test
    fun `仅当开关开启且库已解锁且有授权时才跳过重复确认`() {
        assertTrue(
            AutofillAuthenticationPolicy.skipRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = false, grantActive = true
            )
        )
        // 开关关闭：不跳过（保持每次强制确认）
        assertFalse(
            AutofillAuthenticationPolicy.skipRepeatConfirmation(
                sessionGrantEnabled = false, vaultLocked = false, grantActive = true
            )
        )
        // 库锁定：授权一律不适用（必须先解锁）
        assertFalse(
            AutofillAuthenticationPolicy.skipRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = true, grantActive = true
            )
        )
        // 无有效授权：不跳过
        assertFalse(
            AutofillAuthenticationPolicy.skipRepeatConfirmation(
                sessionGrantEnabled = true, vaultLocked = false, grantActive = false
            )
        )
    }
}
