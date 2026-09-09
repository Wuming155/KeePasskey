package com.keepasskey.app.passkey

import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
