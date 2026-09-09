package com.keepasskey.app.passkey

import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P0-03 (ZT-03) AuthenticatorData flags 单测。
 *
 * 背景：Passkey 断言与注册此前无条件硬编码 `UP|UV|BE|BS`，向依赖方谎报
 * 「用户已验证 (UV=1)」——无可用强认证器设备上，RP 仍会据 UV 放宽风控。
 *
 * 本测锁定 W3C WebAuthn §6.1 语义：
 * 1. 无生物识别等强验证、仅受保护窗口内手动确认通过 → 断言必须如实 `UV=0`（UP 仍在场）；
 * 2. 系统级生物识别 / 锁屏凭据（二次确认）通过 → 断言方可 `UV=1`；
 * 3. 未验证 / 失败 / 取消 → fail-closed 拒绝签发（返回 null）；
 * 4. 注册路径额外携带 AT（Attested Credential Data）位。
 */
class PasskeyAuthFlagsTest {

    private fun hasBit(flags: Byte, bit: Byte): Boolean =
        (flags.toInt() and bit.toInt()) != 0

    // ---------- 验收分支 1：无生物 → UV=0 ----------

    @Test
    fun `无生物识别时仅手动确认通过则断言 UV 位为 0 且 UP 仍置位`() {
        val flags = PasskeyAuthFlags.forAssertion(CredentialUserVerification.ManualConfirmed)
        assertNotNull("手动确认通过必须允许签发（如实降级为 UV=0）", flags)
        assertEquals("UV 位必须为 0", 0, flags!!.toInt() and PasskeyCryptoEngine.FLAG_UV.toInt())
        assertTrue("UP 位应置位", hasBit(flags, PasskeyCryptoEngine.FLAG_UP))
        assertTrue("BE 位应置位", hasBit(flags, PasskeyCryptoEngine.FLAG_BE))
        assertTrue("BS 位应置位", hasBit(flags, PasskeyCryptoEngine.FLAG_BS))
    }

    // ---------- 验收分支 2：二次确认通过 → UV=1 ----------

    @Test
    fun `生物识别或锁屏凭据二次确认通过则断言 UV 位为 1`() {
        val flags = PasskeyAuthFlags.forAssertion(CredentialUserVerification.BiometricSucceeded)
        assertNotNull(flags)
        assertTrue(
            "强验证通过时 UV 位必须置位",
            hasBit(flags!!, PasskeyCryptoEngine.FLAG_UV)
        )
        assertTrue("UP 位应置位", hasBit(flags, PasskeyCryptoEngine.FLAG_UP))
    }

    // ---------- fail-closed：任何非通过结果一律拒绝签发 ----------

    @Test
    fun `未发生任何验证时断言路径拒绝签发`() {
        assertNull(PasskeyAuthFlags.forAssertion(CredentialUserVerification.None))
    }

    @Test
    fun `认证失败或用户取消时断言路径拒绝签发`() {
        listOf(
            CredentialUserVerification.BiometricFailed,
            CredentialUserVerification.BiometricCancelled,
            CredentialUserVerification.ManualCancelled
        ).forEach { verification ->
            assertNull(
                "验证结果 $verification 不得产出断言 flags",
                PasskeyAuthFlags.forAssertion(verification)
            )
        }
    }

    @Test
    fun `未验证失败或取消时注册路径同样拒绝`() {
        listOf(
            CredentialUserVerification.None,
            CredentialUserVerification.BiometricFailed,
            CredentialUserVerification.BiometricCancelled,
            CredentialUserVerification.ManualCancelled
        ).forEach { verification ->
            assertNull(
                "验证结果 $verification 不得产出注册 flags",
                PasskeyAuthFlags.forRegistration(verification)
            )
        }
    }

    // ---------- 注册路径：AT 位与 UV 语义一致性 ----------

    @Test
    fun `注册路径强验证通过时携带 AT 与 UV 位`() {
        val flags = PasskeyAuthFlags.forRegistration(CredentialUserVerification.BiometricSucceeded)
        assertNotNull(flags)
        assertTrue("注册路径必须携带 AT 位", hasBit(flags!!, PasskeyCryptoEngine.FLAG_AT))
        assertTrue("强验证通过时注册 UV 位必须为 1", hasBit(flags, PasskeyCryptoEngine.FLAG_UV))
    }

    @Test
    fun `注册路径手动确认通过时携带 AT 位但 UV 位为 0`() {
        val flags = PasskeyAuthFlags.forRegistration(CredentialUserVerification.ManualConfirmed)
        assertNotNull(flags)
        assertTrue("注册路径必须携带 AT 位", hasBit(flags!!, PasskeyCryptoEngine.FLAG_AT))
        assertEquals("无强验证时注册 UV 位必须为 0", 0, flags.toInt() and PasskeyCryptoEngine.FLAG_UV.toInt())
    }
}
