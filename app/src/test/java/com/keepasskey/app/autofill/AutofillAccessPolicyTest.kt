package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.security.IntegrityEnforcement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AutofillAccessPolicy 访问闸门单元测试（ISSUE-P2-07 / ZT-12、ISSUE-P2-08 / ZT-13、ISSUE-P2-226）。
 *
 * 覆盖「填充与保存两条路径」共用的拒绝决策，尤其：
 * - onSaveRequest 命中黑名单必须拒绝（等价于不落库）；
 * - 合法性无法判定的包名经 [AutofillBlocklistStore] fail-closed 后同样被拒绝；
 * - 完整性风险态优先拒绝，且仅在 disableAutofill 时生效（可疑级保留自动填充）；
 * - 调用方即本应用自身时拒绝（ISSUE-P2-226，密码管理器不给自己填）。
 */
class AutofillAccessPolicyTest {

    private fun allowed(calling: String, self: String = SELF, blocked: Boolean = false): AutofillRejection? =
        AutofillAccessPolicy.rejectReason(IntegrityEnforcement.ALLOWED, calling, self) { blocked }

    @Test
    fun `无风险且未命中黑名单时放行`() {
        assertNull(allowed("com.example.bank"))
    }

    @Test
    fun `命中黑名单时拒绝保存与填充`() {
        val store = AutofillBlocklistStore(null)
        store.add("com.example.bank")

        // 保存侧：rejectReason 非空即代表不落库
        val rejection = AutofillAccessPolicy.rejectReason(
            IntegrityEnforcement.ALLOWED,
            "com.example.bank",
            SELF,
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
            AutofillAccessPolicy.rejectReason(IntegrityEnforcement.ALLOWED, "", SELF) { store.isBlocked(it) }
        )
    }

    @Test
    fun `完整性风险态禁用自动填充时拒绝`() {
        assertEquals(
            AutofillRejection.INTEGRITY_RISK,
            AutofillAccessPolicy.rejectReason(
                IntegrityEnforcement.UNDETERMINED,
                "com.example.bank",
                SELF
            ) { false }
        )
    }

    @Test
    fun `完整性风险优先于黑名单并被优先报告`() {
        assertEquals(
            AutofillRejection.INTEGRITY_RISK,
            AutofillAccessPolicy.rejectReason(
                IntegrityEnforcement.UNDETERMINED,
                "com.example.bank",
                SELF
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
            AutofillAccessPolicy.rejectReason(elevated, "com.example.bank", SELF) { false }
        )
    }

    // ========== ISSUE-P2-226：自我排除 ==========

    @Test
    fun `调用方即本应用时拒绝下发`() {
        assertEquals(AutofillRejection.SELF_APP, allowed(SELF))
    }

    @Test
    fun `自身包名比较对大小写与首尾空白不敏感`() {
        assertEquals(AutofillRejection.SELF_APP, allowed("  Com.KeepAssKey  "))
        assertTrue(AutofillAccessPolicy.isSelfApp("com.example.app", "COM.EXAMPLE.APP"))
    }

    @Test
    fun `自我排除优先于黑名单判定`() {
        // 同一判定只有一处归属：自身包即便被误写进黑名单也报 SELF_APP，不改语义
        assertEquals(AutofillRejection.SELF_APP, allowed(SELF, blocked = true))
    }

    @Test
    fun `外部包名不受自我排除影响`() {
        assertNull(allowed("com.example.bank", self = "com.keepasskey"))
    }

    @Test
    fun `空白包名与空白自身包名不得互相误判`() {
        assertFalse("空调用包名不得判为自身", AutofillAccessPolicy.isSelfApp("", SELF))
        assertFalse("空自身包名（异常装配）不得吞掉全部调用方", AutofillAccessPolicy.isSelfApp("com.example.bank", ""))
    }

    private companion object {
        /** 与 `app/build.gradle.kts` 的 applicationId 同值（生产侧实际取 Context.packageName） */
        const val SELF = "com.keepasskey"
    }
}
