package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.EntryTotpSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-03 (43b)：填充后复制 TOTP 的决策分支单元测试（`autofillCopyTotp`）。
 *
 * 逐条覆盖「偏好值 → 行为分支」：
 * 开关关闭绝不触碰剪贴板；开关开启也仅在该条目确有可用验证码时才复制。
 */
class AutofillTotpCopyPolicyTest {

    private val snapshot = EntryTotpSnapshot(
        code = "123456",
        periodSeconds = 30,
        digits = 6,
        algorithm = "SHA1"
    )

    @Test
    fun `开关关闭时不复制即使条目有 TOTP`() {
        assertFalse(AutofillTotpCopyPolicy.shouldCopy(copyTotpEnabled = false, snapshot = snapshot))
    }

    @Test
    fun `开关开启且条目有 TOTP 时复制`() {
        assertTrue(AutofillTotpCopyPolicy.shouldCopy(copyTotpEnabled = true, snapshot = snapshot))
    }

    @Test
    fun `开关开启但条目无 TOTP 时不复制`() {
        assertFalse(AutofillTotpCopyPolicy.shouldCopy(copyTotpEnabled = true, snapshot = null))
    }

    @Test
    fun `开关开启但验证码为空串时不复制`() {
        assertFalse(
            AutofillTotpCopyPolicy.shouldCopy(
                copyTotpEnabled = true,
                snapshot = snapshot.copy(code = "")
            )
        )
    }

    @Test
    fun `开关关闭且条目无 TOTP 时同样不复制`() {
        assertFalse(AutofillTotpCopyPolicy.shouldCopy(copyTotpEnabled = false, snapshot = null))
    }
}
