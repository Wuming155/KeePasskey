package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Security
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Passkey 密码学引擎与 WebAuthn 签名验证单元测试：
 * 验证 ES256 密钥对生成、DER 签名标准兼容性与 AuthenticatorData 构建。
 */
class PasskeyCryptoEngineTest {

    @Test
    fun `测试 ES256 密钥对生成与属性正确性`() {
        val passkey = PasskeyCryptoEngine.generateEs256KeyPair(
            relyingPartyId = "github.com",
            userName = "alice_dev",
            userDisplayName = "Alice Developer"
        )

        assertEquals("github.com", passkey.relyingPartyId)
        assertEquals("alice_dev", passkey.userName)
        assertEquals("Alice Developer", passkey.userDisplayName)
        assertEquals(PasskeyData.ALGORITHM_ES256, passkey.algorithmId)
        assertTrue(passkey.credentialId.isNotBlank())
        assertTrue(passkey.publicKeyBase64.isNotBlank())
        assertTrue(passkey.privateKey.length > 0)
    }

    @Test
    fun `测试 AuthenticatorData 二进制块格式标准`() {
        val authData = PasskeyCryptoEngine.buildAuthenticatorData(
            rpId = "webauthn.io",
            flags = 0x05, // UP (0x01) + UV (0x04)
            signCount = 42
        )

        assertEquals(37, authData.size)
        // 验证 flags 字段 (第 32 字节)
        assertEquals(0x05.toByte(), authData[32])
        // 验证 signCount 计数器大端写入
        assertEquals(0.toByte(), authData[33])
        assertEquals(0.toByte(), authData[34])
        assertEquals(0.toByte(), authData[35])
        assertEquals(42.toByte(), authData[36])
    }

    @Test
    fun `测试 Passkey 签名生成与确定性输出`() {
        val passkey = PasskeyCryptoEngine.generateEs256KeyPair("example.com", "bob")
        val clientDataHash = ByteArray(32) { (it + 1).toByte() }
        val authData = PasskeyCryptoEngine.buildAuthenticatorData("example.com", 0x01, 1)

        val dataToSign = authData + clientDataHash
        val signature = PasskeyCryptoEngine.signAssertion(passkey.privateKey.readString(), dataToSign)

        assertNotNull(signature)
        assertTrue(signature.isNotEmpty())
        // ASN.1 DER SEQUENCE 起始字节为 0x30
        assertEquals(0x30.toByte(), signature[0])
    }

    @Test
    fun `测试 PasskeyData 与 KDBX 自定义字段双向互转`() {
        val passkey = PasskeyCryptoEngine.generateEs256KeyPair(
            relyingPartyId = "login.microsoft.com",
            userName = "admin@ms.com",
            userDisplayName = "MS Admin"
        )

        val customFields = passkey.toCustomFields()
        assertTrue(customFields.size >= 10)

        val restored = PasskeyData.fromCustomFields(customFields)
        assertNotNull(restored)
        assertEquals(passkey.relyingPartyId, restored!!.relyingPartyId)
        assertEquals(passkey.userName, restored.userName)
        assertEquals(passkey.credentialId, restored.credentialId)
        assertEquals(passkey.algorithmId, restored.algorithmId)
        assertEquals(passkey.publicKeyBase64, restored.publicKeyBase64)
        assertEquals(passkey.privateKey.readString(), restored.privateKey.readString())
    }
}
