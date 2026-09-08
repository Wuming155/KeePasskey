package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 解锁通行密钥断言验证纯逻辑单测（TASK-18）：
 * 合法断言通过 / 签名篡改拒绝 / signCount 单调防克隆 / rpIdHash 归属校验 / AuthenticatorData 构建格式。
 * Keystore 硬件路径属 Instrumented 范畴，本测以软件 EC 密钥对驱动同一验证逻辑。
 */
class UnlockPasskeyAssertionTest {

    private fun softwareKeyPair(): java.security.KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun sign(keyPair: java.security.KeyPair, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(data)
        }.sign()

    @Test
    fun `合法断言通过验证`() {
        val keyPair = softwareKeyPair()
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, signCount = 7)
        val signature = sign(keyPair, authData)
        assertTrue(
            UnlockPasskeyManager.verifyAssertion(
                publicKeyEncoded = keyPair.public.encoded,
                authenticatorData = authData,
                signature = signature,
                expectedRpIdHash = UnlockPasskeyManager.rpIdHash(UnlockPasskeyManager.RP_ID),
                lastSignCount = 6
            )
        )
    }

    @Test
    fun `签名篡改拒绝`() {
        val keyPair = softwareKeyPair()
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, 1)
        val signature = sign(keyPair, authData)
        signature[signature.size / 2] = (signature[signature.size / 2].toInt() xor 0x41).toByte()
        assertFalse(
            UnlockPasskeyManager.verifyAssertion(
                keyPair.public.encoded, authData, signature,
                UnlockPasskeyManager.rpIdHash(UnlockPasskeyManager.RP_ID), 0
            )
        )
    }

    @Test
    fun `signCount 非单调（克隆信号）fail-closed 拒绝`() {
        val keyPair = softwareKeyPair()
        // 断言内 count=5，但持久化侧已达 6 → 计数回退，判克隆拒绝
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, 5)
        val signature = sign(keyPair, authData)
        assertFalse(
            UnlockPasskeyManager.verifyAssertion(
                keyPair.public.encoded, authData, signature,
                UnlockPasskeyManager.rpIdHash(UnlockPasskeyManager.RP_ID), 6
            )
        )
    }

    @Test
    fun `rpIdHash 归属不符拒绝`() {
        val keyPair = softwareKeyPair()
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, 1)
        val signature = sign(keyPair, authData)
        val wrongRpHash = UnlockPasskeyManager.rpIdHash("evil.example.com")
        assertFalse(
            UnlockPasskeyManager.verifyAssertion(
                keyPair.public.encoded, authData, signature, wrongRpHash, 0
            )
        )
    }

    @Test
    fun `AuthenticatorData 构建格式符合 37 字节规范`() {
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, 0x01020304)
        assertEquals(UnlockPasskeyManager.AUTHENTICATOR_DATA_LENGTH, authData.size)
        assertTrue(authData.copyOfRange(0, 32).contentEquals(UnlockPasskeyManager.rpIdHash(UnlockPasskeyManager.RP_ID)))
        assertEquals(0x01.toByte(), authData[32]) // UP flag
        assertEquals(0x01, authData[33].toInt() and 0xFF)
        assertEquals(0x02, authData[34].toInt() and 0xFF)
        assertEquals(0x03, authData[35].toInt() and 0xFF)
        assertEquals(0x04, authData[36].toInt() and 0xFF)
    }

    @Test
    fun `非 EC 公钥解码失败 fail-closed 返回 false`() {
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, 1)
        assertFalse(
            UnlockPasskeyManager.verifyAssertion(
                publicKeyEncoded = "not-a-real-key".toByteArray(),
                authenticatorData = authData,
                signature = ByteArray(8),
                expectedRpIdHash = UnlockPasskeyManager.rpIdHash(UnlockPasskeyManager.RP_ID),
                lastSignCount = 0
            )
        )
    }
}
