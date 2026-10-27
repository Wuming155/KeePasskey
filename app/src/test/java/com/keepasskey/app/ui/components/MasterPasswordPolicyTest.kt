package com.keepasskey.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MasterPasswordPolicyTest {
    @Test
    fun `主口令长度与强度边界决定拒绝确认或通过`() {
        val cases = listOf(
            Triple(1, null, MasterPasswordPolicy.Verdict.TOO_SHORT),
            Triple(7, 128, MasterPasswordPolicy.Verdict.TOO_SHORT),
            Triple(8, 12, MasterPasswordPolicy.Verdict.WEAK_REQUIRES_CONFIRM),
            Triple(16, 39, MasterPasswordPolicy.Verdict.WEAK_REQUIRES_CONFIRM),
            Triple(8, 40, MasterPasswordPolicy.Verdict.OK),
            Triple(12, 96, MasterPasswordPolicy.Verdict.OK),
            Triple(8, null, MasterPasswordPolicy.Verdict.OK),
        )
        cases.forEach { (length, bits, expected) ->
            assertEquals(expected, MasterPasswordPolicy.verdictOf(passwordLength = length, strengthBits = bits))
        }
    }
}
