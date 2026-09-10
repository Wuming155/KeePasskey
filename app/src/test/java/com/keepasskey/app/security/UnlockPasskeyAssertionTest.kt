package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 解锁通行密钥断言验证纯逻辑单测（TASK-18 / ISSUE-P1-09）：
 * 合法断言通过 / 签名篡改拒绝 / signCount 单调防克隆 / rpIdHash 归属校验 /
 * challenge 绑定与 clientDataJSON 规范性 / AuthenticatorData 构建格式。
 * Keystore 硬件路径属 Instrumented 范畴，本测以软件 EC 密钥对驱动同一验证逻辑。
 */
class UnlockPasskeyAssertionTest {

    private fun softwareKeyPair(): java.security.KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun clientData(challenge: ByteArray): ByteArray =
        UnlockPasskeyManager.buildClientDataJson(challenge)

    /** 对 AuthenticatorData || SHA-256(clientDataJSON) 签名（与硬件断言同一覆盖范围） */
    private fun signAssertion(
        keyPair: java.security.KeyPair,
        authData: ByteArray,
        clientDataJSON: ByteArray
    ): ByteArray = Signature.getInstance("SHA256withECDSA").apply {
        initSign(keyPair.private)
        update(authData)
        update(MessageDigest.getInstance("SHA-256").digest(clientDataJSON))
    }.sign()

    private fun verify(
        keyPair: java.security.KeyPair,
        authData: ByteArray,
        signature: ByteArray,
        lastSignCount: Int,
        clientDataJSON: ByteArray,
        challenge: ByteArray,
        expectedRpHash: ByteArray = UnlockPasskeyManager.rpIdHash(UnlockPasskeyManager.RP_ID)
    ): Boolean = UnlockPasskeyManager.verifyAssertion(
        publicKeyEncoded = keyPair.public.encoded,
        authenticatorData = authData,
        signature = signature,
        expectedRpIdHash = expectedRpHash,
        lastSignCount = lastSignCount,
        clientDataJSON = clientDataJSON,
        expectedChallenge = challenge
    )

    @Test
    fun `合法断言通过验证`() {
        val keyPair = softwareKeyPair()
        val challenge = ByteArray(UnlockPasskeyManager.CHALLENGE_LENGTH) { it.toByte() }
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, signCount = 7)
        val signature = signAssertion(keyPair, authData, clientData(challenge))
        assertTrue(verify(keyPair, authData, signature, 6, clientData(challenge), challenge))
    }

    @Test
    fun `签名篡改拒绝`() {
        val keyPair = softwareKeyPair()
        val challenge = ByteArray(UnlockPasskeyManager.CHALLENGE_LENGTH)
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, 1)
        val signature = signAssertion(keyPair, authData, clientData(challenge))
        signature[signature.size / 2] = (signature[signature.size / 2].toInt() xor 0x41).toByte()
        assertFalse(verify(keyPair, authData, signature, 0, clientData(challenge), challenge))
    }

    @Test
    fun `signCount 非单调（克隆信号）fail-closed 拒绝`() {
        val keyPair = softwareKeyPair()
        val challenge = ByteArray(UnlockPasskeyManager.CHALLENGE_LENGTH)
        // 断言内 count=5，但持久化侧已达 6 → 计数回退，判克隆拒绝
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, 5)
        val signature = signAssertion(keyPair, authData, clientData(challenge))
        assertFalse(verify(keyPair, authData, signature, 6, clientData(challenge), challenge))
    }

    @Test
    fun `rpIdHash 归属不符拒绝`() {
        val keyPair = softwareKeyPair()
        val challenge = ByteArray(UnlockPasskeyManager.CHALLENGE_LENGTH)
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, 1)
        val signature = signAssertion(keyPair, authData, clientData(challenge))
        val wrongRpHash = UnlockPasskeyManager.rpIdHash("evil.example.com")
        assertFalse(verify(keyPair, authData, signature, 0, clientData(challenge), challenge, wrongRpHash))
    }

    @Test
    fun `challenge 不匹配（重放旧断言）fail-closed 拒绝`() {
        val keyPair = softwareKeyPair()
        val oldChallenge = ByteArray(UnlockPasskeyManager.CHALLENGE_LENGTH) { 0x11 }
        val newChallenge = ByteArray(UnlockPasskeyManager.CHALLENGE_LENGTH) { 0x22 }
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, 1)
        // 断言由旧 challenge 生成，验证侧要求新 challenge → clientDataJSON 重建不一致
        val signature = signAssertion(keyPair, authData, clientData(oldChallenge))
        assertFalse(verify(keyPair, authData, signature, 0, clientData(oldChallenge), newChallenge))
    }

    @Test
    fun `clientDataJSON 篡改（type 或 origin 变更）拒绝`() {
        val keyPair = softwareKeyPair()
        val challenge = ByteArray(UnlockPasskeyManager.CHALLENGE_LENGTH)
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, 1)
        val clientDataJSON = clientData(challenge)
        val signature = signAssertion(keyPair, authData, clientDataJSON)

        // 仅篡改 clientDataJSON 一个字节（模拟 type/origin 字段被改写）
        val tampered = clientDataJSON.copyOf().also { it[it.size - 2] = (it[it.size - 2].toInt() xor 0x01).toByte() }
        assertFalse(verify(keyPair, authData, signature, 0, tampered, challenge))

        // 篡改签名对以匹配被改写的 clientDataJSON：签名覆盖其摘要，仍应拒绝
        val tamperedSignature = signAssertion(keyPair, authData, tampered)
        assertFalse(verify(keyPair, authData, tamperedSignature, 0, tampered, challenge))
    }

    @Test
    fun `clientDataJSON 构建包含 type challenge 与 origin 且 challenge 为 Base64URL 无填充`() {
        val challenge = ByteArray(32) { 0xAB.toByte() }
        val json = String(UnlockPasskeyManager.buildClientDataJson(challenge), Charsets.UTF_8)
        assertTrue(json.contains("\"type\":\"webauthn.get\""))
        assertTrue(json.contains("\"origin\":\"${UnlockPasskeyManager.CLIENT_DATA_ORIGIN}\""))
        // 0xAB.. 连续 32 字节的 Base64URL 无填充编码不含 '=' 或 '+' '/'
        val challengeField = json.substringAfter("\"challenge\":\"").substringBefore('"')
        assertFalse(challengeField.contains('='))
        assertFalse(challengeField.contains('+'))
        assertFalse(challengeField.contains('/'))
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
        val challenge = ByteArray(UnlockPasskeyManager.CHALLENGE_LENGTH)
        val authData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, 1)
        assertFalse(
            UnlockPasskeyManager.verifyAssertion(
                publicKeyEncoded = "not-a-real-key".toByteArray(),
                authenticatorData = authData,
                signature = ByteArray(8),
                expectedRpIdHash = UnlockPasskeyManager.rpIdHash(UnlockPasskeyManager.RP_ID),
                lastSignCount = 0,
                clientDataJSON = clientData(challenge),
                expectedChallenge = challenge
            )
        )
    }
}
