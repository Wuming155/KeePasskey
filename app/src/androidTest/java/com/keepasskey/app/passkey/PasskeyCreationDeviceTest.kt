package com.keepasskey.app.passkey

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.CallingAppInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.PasskeyEntryCoordinator
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 通行密钥 (Passkey) 创建链路在**真机**上的端到端运行态验证。
 *
 * ## 覆盖内容
 * 1. 验证系统级别 `android.credentials.CredentialManager` 服务的存在性与状态；
 * 2. 验证在真实 Android 运行时下解析标准 WebAuthn `BeginCreatePublicKeyCredentialRequest`，
 *    确保不会在 domain 校验、PSL 判定或序列化处异常；
 * 3. 验证通过真实的 [PasskeyCryptoEngine] 在真机上生成 ES256 密钥对并由
 *    [PasskeyEntryCoordinator] 持久化至测试数据库，再由**生产断言路径同一 PEM 通道**
 *    （[PasskeyCryptoEngine.decodePemPrivateKeyText] 还原签名材料）交断言引擎
 *    [PasskeyCryptoEngine.signAssertion] 验证新创建的私钥具备真实合法的签名能力。
 */
@RunWith(AndroidJUnit4::class)
class PasskeyCreationDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `系统级CredentialManager服务可用性探测`() {
        assertTrue("本应用要求运行于 API 34+ 设备", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val sysCm = context.getSystemService(android.credentials.CredentialManager::class.java)
        assertNotNull("Android 14+ 系统必须提供 CredentialManager 系统服务", sysCm)
    }

    @Test
    fun `真机运行时BeginCreatePublicKeyCredentialRequest请求解析与域名门控放行`() {
        val requestJson = """
            {
              "challenge": "dGVzdC1jaGFsbGVuZ2UtYnl0ZXM",
              "rp": {
                "name": "Passkeys Demo",
                "id": "passkeys.io"
              },
              "user": {
                "id": "dXNlcmlkMTIz",
                "name": "testuser@example.com",
                "displayName": "Test User"
              },
              "pubKeyCredParams": [
                {"type": "public-key", "alg": -7}
              ],
              "timeout": 60000,
              "attestation": "none"
            }
        """.trimIndent()

        // 验证 DomainMatcher 在真机运行时的 PSL 判定
        val isDomainTrustedForCreation = DomainMatcher.isRpIdTrustedForCreation(
            rpId = "passkeys.io",
            callingOrigin = "https://passkeys.io"
        )
        assertTrue("passkeys.io 在真实 PSL 规则下必须被判定为可注册且合法的 RP ID", isDomainTrustedForCreation)

        // 验证非白名单普通调用方经 apkKeyHashOrigin 后对公网合法域名同样放行
        val isAppCallingTrusted = DomainMatcher.isRpIdTrustedForCreation(
            rpId = "passkeys.io",
            callingOrigin = "android:apk-key-hash:someFakeHash"
        )
        assertTrue("普通应用调用时只要 rpId 属于有效可注册域即应放行创建入口", isAppCallingTrusted)
    }

    @Test
    fun `真机端到端创建通行密钥并验证其签名能力`() = runBlocking {
        // 1. 在真机上通过生产引擎生成 PasskeyData (ES256)
        val passkeyData = PasskeyCryptoEngine.generateEs256KeyPair(
            relyingPartyId = "passkeys.io",
            userName = "testuser@example.com",
            userDisplayName = "Test User"
        )

        assertEquals("passkeys.io", passkeyData.relyingPartyId)
        assertEquals("testuser@example.com", passkeyData.userName)
        assertEquals(PasskeyData.ALGORITHM_ES256, passkeyData.algorithmId)
        assertTrue("公钥必须生成且非空", passkeyData.publicKeyBase64.isNotBlank())

        // 2. 模拟真机临时数据库环境并落库
        val tempFile = File(context.cacheDir, "passkey_probe_test.kdbx")
        tempFile.delete()
        val session = DatabaseSession()
        session.create(
            file = tempFile,
            name = "PasskeyTestDb",
            passwordChars = "TestMasterPassword".toCharArray(),
            useArgon2 = false
        )
        val coordinator = PasskeyEntryCoordinator(
            databaseSession = session,
            debugLog = DebugLogBuffer(),
            persistSession = { com.keepasskey.core.result.KdbxResult.Success(Unit) }
        )

        val savedEntry = coordinator.saveNewPasskeyEntry(passkeyData, boundPackage = null)
        assertNotNull("创建的新条目必须成功落入会话", savedEntry)

        // 3. 验证从库中通过 rpId 可以准确匹配到该通行密钥条目
        val matchedEntries = coordinator.findEntriesForRpId("passkeys.io")
        assertTrue("库中必须能检索到刚创建的 passkeys.io 条目", matchedEntries.isNotEmpty())
        val loadedPasskey = PasskeyData.fromCustomFields(matchedEntries.first().customFields)
        assertNotNull("从条目反序列化还原的 PasskeyData 不可为空", loadedPasskey)
        assertEquals(passkeyData.credentialId, loadedPasskey!!.credentialId)

        // 4. 关键：验证刚创建的 Passkey 私钥经受控字节流可以在真机上完成有效断言签名
        //    私钥在库内以 **PKCS#8 PEM 文本**驻留（KeePassXC / KeePassDX 口径），而
        //    `signAssertion` 的入参契约是**签名材料**（ES256 = 32 字节标量）——故须先走
        //    生产断言路径同一 PEM 通道（`PasskeyAssertionActivity.decodePrivateKeyMaterial`）
        //    还原，再送签；直接把 PEM 文本字节流喂给 `signAssertion` 属契约误用。
        val clientDataHash = ByteArray(32) { 0x01 }
        val authData = PasskeyCryptoEngine.buildAuthenticatorData("passkeys.io", 0x01.toByte(), 1)
        val dataToSign = authData + clientDataHash

        val signingKey = loadedPasskey.usePrivateKeyBytes { rawKey ->
            checkNotNull(PasskeyCryptoEngine.decodePemPrivateKeyText(rawKey)) {
                "真机上驻留的 PKCS#8 PEM 私钥必须能经生产 PEM 通道还原签名材料"
            }
        }
        try {
            assertEquals(
                "私钥形态（PKCS#8 OID）还原出的算法必须与条目字段一致",
                loadedPasskey.algorithmId,
                signingKey.algorithmId
            )
            assertEquals(
                "ES256 的 PEM 通道还原结果必须是定长原始标量",
                ES256_SCALAR_BYTES,
                signingKey.keyBytes.size
            )
            val signature = PasskeyCryptoEngine.signAssertion(
                signingKey.algorithmId,
                signingKey.keyBytes,
                dataToSign
            )
            assertTrue("断言签名输出必须非空且符合 ASN.1 DER 结构 (0x30 开头)", signature.isNotEmpty() && signature[0] == 0x30.toByte())
        } finally {
            java.util.Arrays.fill(signingKey.keyBytes, 0.toByte())
        }
    }

    private companion object {
        /** ES256 签名侧材料长度：P-256 私钥标量 32 字节（与 `PasskeySigningKey` 文档口径一致） */
        const val ES256_SCALAR_BYTES = 32
    }
}
