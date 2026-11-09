package com.keepasskey.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * ENG-20261028-07：长度门槛按 **Unicode 码点**计（与 crypto 强度模型同一口径）——
     * 代理对只算 1 个字符。4 个 emoji 在 UTF-16 下是 8 码元，旧计长会误判达标；按码点必须阻断。
     * 强度确认阈值（40 bits）保持不变。
     */
    @Test
    fun `长度门槛按 Unicode 码点计而非 UTF-16 码元`() {
        val fourEmoji = "🔐🔑🧩🛡".toCharArray()
        val eightEmoji = "🔐🔑🧩🛡🔒🔓🚀🧪".toCharArray()

        assertEquals("4 个 emoji 在 UTF-16 下是 8 个码元（旧计长会误判达标）", 8, fourEmoji.size)
        assertEquals(4, MasterPasswordPolicy.lengthInCodePoints(fourEmoji))
        assertEquals(8, MasterPasswordPolicy.lengthInCodePoints(eightEmoji))
        assertEquals(5, MasterPasswordPolicy.lengthInCodePoints("aB3\$🔑".toCharArray()))
        assertEquals(0, MasterPasswordPolicy.lengthInCodePoints(CharArray(0)))

        assertFalse("4 个字符低于 8 个字符下限", MasterPasswordPolicy.meetsMinLength(fourEmoji))
        assertTrue("8 个 emoji 恰达 8 个字符下限", MasterPasswordPolicy.meetsMinLength(eightEmoji))
        assertEquals(
            MasterPasswordPolicy.Verdict.TOO_SHORT,
            MasterPasswordPolicy.verdictOf(fourEmoji, strengthBits = 128)
        )
        assertEquals(
            MasterPasswordPolicy.Verdict.OK,
            MasterPasswordPolicy.verdictOf(eightEmoji, strengthBits = 128)
        )
        // 40 bits 确认阈值不变：达标长度下 39 仍需二次确认，40 直通
        assertEquals(
            MasterPasswordPolicy.Verdict.WEAK_REQUIRES_CONFIRM,
            MasterPasswordPolicy.verdictOf(eightEmoji, strengthBits = 39)
        )
        assertEquals(
            MasterPasswordPolicy.Verdict.OK,
            MasterPasswordPolicy.verdictOf(eightEmoji, strengthBits = 40)
        )
    }
}
