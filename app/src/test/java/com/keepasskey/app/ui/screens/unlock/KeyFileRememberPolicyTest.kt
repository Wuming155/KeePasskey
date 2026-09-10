package com.keepasskey.app.ui.screens.unlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-04 密钥文件记忆裁决纯函数单测：
 * 把「偏好开关 + 记录完整性 + 持久化授权有效性」的判定从 Android IO 中析出，
 * 使「偏好关闭不记 / 授权失效降级」两条关键分支无需 SAF 交互即可真实覆盖。
 */
class KeyFileRememberPolicyTest {

    private val record = RememberedKeyFile(URI, "vault.keyx")

    @Test
    fun `偏好开启且来源有效时登记来源并申请持久授权`() {
        assertTrue(KeyFileRememberPolicy.shouldTrackSource(true, URI))
    }

    @Test
    fun `偏好关闭时不申请持久授权`() {
        assertFalse(
            "偏好关闭仍申请持久授权会无谓扩大应用持久访问面",
            KeyFileRememberPolicy.shouldTrackSource(false, URI)
        )
    }

    @Test
    fun `来源缺失或空白时不登记`() {
        assertFalse(KeyFileRememberPolicy.shouldTrackSource(true, null))
        assertFalse(KeyFileRememberPolicy.shouldTrackSource(true, ""))
        assertFalse(KeyFileRememberPolicy.shouldTrackSource(true, "   "))
    }

    @Test
    fun `偏好开启且授权有效时可恢复记忆`() {
        assertTrue(KeyFileRememberPolicy.canRestore(true, record, permissionValid = true))
    }

    @Test
    fun `偏好关闭时不得恢复记忆`() {
        assertFalse(KeyFileRememberPolicy.canRestore(false, record, permissionValid = true))
    }

    @Test
    fun `持久授权失效时降级为未记住`() {
        assertFalse(
            "授权失效仍尝试恢复会反复失败并污染解锁页状态",
            KeyFileRememberPolicy.canRestore(true, record, permissionValid = false)
        )
    }

    @Test
    fun `无记忆记录时不恢复`() {
        assertFalse(KeyFileRememberPolicy.canRestore(true, null, permissionValid = true))
    }

    @Test
    fun `记录Uri为空白时不恢复`() {
        assertFalse(
            KeyFileRememberPolicy.canRestore(
                true,
                RememberedKeyFile("  ", "vault.keyx"),
                permissionValid = true
            )
        )
    }

    private companion object {
        const val URI = "content://test.docs/keyfile/vault.keyx"
    }
}
