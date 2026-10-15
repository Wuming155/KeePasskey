package com.keepasskey.app.passkey

import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Credential Manager 提供者 PendingIntent 契约回归锁（ISSUE-P1-01）。
 *
 * 背景：系统 Credential Manager 以 **fillIn Intent** 方式 send 条目上的 PendingIntent，
 * 请求本体在 send 阶段注入。`FLAG_IMMUTABLE` 会让注入的 extras 被静默忽略，导致
 * 链式解锁（[CredentialUnlockActivity]）与密码保存（[PasswordSaveActivity]）全链路失败，
 * 而 manifest / capabilities / 服务绑定均正常——属难以自测的静默握手故障。
 *
 * 全部断言基于编译期常量，`PendingIntent` 类在运行期不会被加载，无需 Robolectric。
 */
class CredentialPendingIntentsTest {

    @Test
    fun `条目 PendingIntent 必须可变更以允许系统注入最终请求`() {
        assertEquals(
            PendingIntent.FLAG_MUTABLE,
            CredentialPendingIntents.ENTRY_FLAGS and PendingIntent.FLAG_MUTABLE
        )
    }

    @Test
    fun `条目 PendingIntent 必须携带 UPDATE_CURRENT 以刷新 extras`() {
        assertEquals(
            PendingIntent.FLAG_UPDATE_CURRENT,
            CredentialPendingIntents.ENTRY_FLAGS and PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    @Test
    fun `条目 PendingIntent 严禁 IMMUTABLE`() {
        assertEquals(
            0,
            CredentialPendingIntents.ENTRY_FLAGS and PendingIntent.FLAG_IMMUTABLE
        )
        assertNotEquals(
            PendingIntent.FLAG_IMMUTABLE,
            CredentialPendingIntents.ENTRY_FLAGS and PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Test
    fun `条目 PendingIntent 严禁 ONE_SHOT（条目可被多次点选）`() {
        assertEquals(
            0,
            CredentialPendingIntents.ENTRY_FLAGS and PendingIntent.FLAG_ONE_SHOT
        )
    }

    @Test
    fun `随机入口身份不随计数器生命周期复用`() {
        val ids = (1..512).map { CredentialPendingIntents.newRequestId() }

        assertEquals("每条逻辑入口都应获得独立随机 ID", ids.size, ids.toSet().size)
        assertTrue("身份应是规范 UUID，不得编码调用方或条目数据", ids.all {
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                .matches(it)
        })
    }

    @Test
    fun `请求上下文按入口类型隔离并在短期后失效`() {
        val requestId = CredentialPendingIntents.newRequestId()
        val context = CredentialEntryRequestContext(
            requestType = CredentialEntryRequestType.PASSWORD_FILL,
            databaseId = "fake-vault-id",
            entryId = "fake-entry-id",
            expectedOrigin = "https://example.test",
            expectedPackage = "com.example.browser",
            expectedDomain = "example.test"
        )
        CredentialPendingIntents.registerRequestContext(requestId, context, nowMillis = 10_000L)

        assertEquals(
            context,
            CredentialPendingIntents.requestContext(
                requestId,
                CredentialEntryRequestType.PASSWORD_FILL,
                nowMillis = 10_001L
            )
        )
        assertNull(
            "密码填充请求不能被解释成另一种入口",
            CredentialPendingIntents.requestContext(
                requestId,
                CredentialEntryRequestType.PASSKEY_ASSERTION,
                nowMillis = 10_001L
            )
        )
        assertNull(
            "过期候选上下文必须安全失败，不能从新版 extras 补配",
            CredentialPendingIntents.requestContext(
                requestId,
                CredentialEntryRequestType.PASSWORD_FILL,
                nowMillis = 910_000L
            )
        )
    }

    @Test
    fun `密码交付要求最终系统 caller 同时匹配候选 origin 与 package`() {
        val context = CredentialEntryRequestContext(
            requestType = CredentialEntryRequestType.PASSWORD_FILL,
            databaseId = "fake-vault-id",
            entryId = "fake-entry-id",
            expectedOrigin = "https://a.example",
            expectedPackage = "com.example.browser"
        )

        assertTrue(context.matchesPasswordFillCaller("https://a.example", "com.example.browser"))
        assertFalse(
            "同一浏览器的另一域必须拒绝",
            context.matchesPasswordFillCaller("https://b.example", "com.example.browser")
        )
        assertFalse(
            "另一调用包必须拒绝",
            context.matchesPasswordFillCaller("https://a.example", "com.example.other")
        )
        assertFalse(
            "不可将创建等其它入口类型解释为密码填充",
            context.copy(requestType = CredentialEntryRequestType.PASSWORD_SAVE)
                .matchesPasswordFillCaller("https://a.example", "com.example.browser")
        )
    }
}
