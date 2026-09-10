package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.security.IntegrityEnforcement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AutofillAccessPolicy 访问闸门单元测试（ISSUE-P2-07 / ZT-12、ISSUE-P2-08 / ZT-13）。
 *
 * 覆盖「填充与保存两条路径」共用的拒绝决策，尤其：
 * - onSaveRequest 命中黑名单必须拒绝（等价于不落库）；
 * - 合法性无法判定的包名经 [AutofillBlocklistStore] fail-closed 后同样被拒绝；
 * - 完整性风险态优先拒绝，且仅在 disableAutofill 时生效（可疑级保留自动填充）。
 */
class AutofillAccessPolicyTest {

    @Test
    fun `无风险且未命中黑名单时放行`() {
        assertNull(
            AutofillAccessPolicy.rejectReason(IntegrityEnforcement.ALLOWED, "com.example.bank") { false }
        )
    }

    @Test
    fun `命中黑名单时拒绝保存与填充`() {
        val store = AutofillBlocklistStore(null)
        store.add("com.example.bank")

        // 保存侧：rejectReason 非空即代表不落库
        val rejection = AutofillAccessPolicy.rejectReason(
            IntegrityEnforcement.ALLOWED,
            "com.example.bank",
            store::isBlocked
        )
        assertEquals(AutofillRejection.BLOCKLISTED, rejection)
        assertFalse("命中黑名单必须拒绝保存", rejection == null)
        assertTrue(store.isBlocked("com.example.bank"))
    }

    @Test
    fun `非法包名经黑名单 fail-closed 后同样拒绝`() {
        val store = AutofillBlocklistStore(null)

        assertEquals(
            AutofillRejection.BLOCKLISTED,
            AutofillAccessPolicy.rejectReason(IntegrityEnforcement.ALLOWED, "") { store.isBlocked(it) }
        )
    }

    @Test
    fun `完整性风险态禁用自动填充时拒绝`() {
        assertEquals(
            AutofillRejection.INTEGRITY_RISK,
            AutofillAccessPolicy.rejectReason(
                IntegrityEnforcement.UNDETERMINED,
                "com.example.bank"
            ) { false }
        )
    }

    @Test
    fun `完整性风险优先于黑名单并被优先报告`() {
        assertEquals(
            AutofillRejection.INTEGRITY_RISK,
            AutofillAccessPolicy.rejectReason(
                IntegrityEnforcement.UNDETERMINED,
                "com.example.bank"
            ) { true }
        )
    }

    @Test
    fun `可疑级仅禁用生物快速解锁时自动填充仍放行`() {
        val elevated = IntegrityEnforcement(
            disableBiometricQuickUnlock = true,
            disableAutofill = false,
            requireRiskNotice = true
        )
        assertNull(
            AutofillAccessPolicy.rejectReason(elevated, "com.example.bank") { false }
        )
    }
}
