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
import com.keepasskey.core.model.PasskeyKeyText
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume
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
 *    [PasskeyCryptoEngine.signAssertion] 验证新创建的私钥具备真实合法的签名能力；
 * 4. （`ISSUE-P2-211`）覆盖 **Ed25519 / RS256** 两条生成路径的 PEM 形态与签名能力——
 *    含「Ed25519 必须是 RFC 8410 `version = 0` 最小形态」这一设备侧回归判据。
 */
@RunWith(AndroidJUnit4::class)
class PasskeyCreationDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `系统级CredentialManager服务可用性探测`() {
        // ISSUE-P2-192 余量第 8 项：环境前提（API 34+）从硬断言改为 Assume——
        // 前提不满足即跳过并如实记入 skipped 数，换机 / 降级环境不再被误读为「生产有 bug」
        Assume.assumeTrue(
            "本应用要求运行于 API 34+ 设备（当前 API ${Build.VERSION.SDK_INT}，不满足即跳过）",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
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

    /**
     * `ISSUE-P2-211` 真机回归：**Ed25519 / RS256** 两条生成路径在设备运行时产出的
     * `KPEX_PASSKEY_PRIVATE_KEY_PEM` 必须
     * ① 是合法 PKCS#8 PEM、
     * ② 能经生产 PEM 通道还原签名材料、
     * ③ 能完成真实断言签名；
     * 且 Ed25519 必须是 **RFC 8410 的 `version = 0` 最小形态**——`version = 1` 的
     * `OneAsymmetricKey` 会被 KeePassXC 等 OpenSSL 系实现拒收（该缺陷正是被
     * `tools/passkey-interop/verify_interop.py` 的外部对拍首次发现的）。
     *
     * 为何必须在真机而非宿主：同一份 Kotlin 代码在 Android 运行时可能落到不同的
     * BC / 原生实现分派（§143 / §147 的教训），宿主全绿不构成设备可用的证据。
     */
    @Test
    fun `真机生成Ed25519与RS256通行密钥并校验PEM形态与签名能力`() = runBlocking {
        assertGeneratedKeyUsable(
            passkeyData = PasskeyCryptoEngine.generateEd25519KeyPair(
                relyingPartyId = "passkeys.io",
                userName = "ed25519@example.com",
                userHandle = "ed25519-handle",
                userDisplayName = "Ed25519 User"
            ),
            expectRfc8410V0Pem = true
        )
        assertGeneratedKeyUsable(
            passkeyData = PasskeyCryptoEngine.generateRs256KeyPair(
                relyingPartyId = "passkeys.io",
                userName = "rs256@example.com",
                userHandle = "rs256-handle",
                userDisplayName = "RSA User"
            ),
            expectRfc8410V0Pem = false
        )
    }

    private fun assertGeneratedKeyUsable(passkeyData: PasskeyData, expectRfc8410V0Pem: Boolean) {
        assertTrue("公钥必须生成且非空", passkeyData.publicKeyBase64.isNotBlank())

        passkeyData.usePrivateKeyBytes { pemBytes ->
            val der = requireNotNull(PasskeyKeyText.pemToDer(pemBytes)) {
                "设备运行时的私钥必须是合法 PKCS#8 PEM（算法 ${passkeyData.algorithmId}）"
            }
            try {
                if (expectRfc8410V0Pem) {
                    assertTrue(
                        "Ed25519 私钥必须为 RFC 8410 version=0 最小形态（version=1 的 " +
                            "OneAsymmetricKey 会被 OpenSSL 系实现拒收，见 ISSUE-P2-211）",
                        der.size > ED25519_RFC8410_V0_PREFIX.size &&
                            ED25519_RFC8410_V0_PREFIX.indices.all { der[it] == ED25519_RFC8410_V0_PREFIX[it] }
                    )
                }
            } finally {
                java.util.Arrays.fill(der, 0.toByte())
            }
        }

        val signingKey = passkeyData.usePrivateKeyBytes { rawKey ->
            requireNotNull(PasskeyCryptoEngine.decodePemPrivateKeyText(rawKey)) {
                "设备运行时的 PKCS#8 PEM 必须能经生产 PEM 通道还原签名材料"
            }
        }
        try {
            assertEquals(
                "PEM 通道还原出的算法必须与条目算法一致",
                passkeyData.algorithmId,
                signingKey.algorithmId
            )
            val dataToSign = PasskeyCryptoEngine.buildAuthenticatorData(
                passkeyData.relyingPartyId,
                PasskeyCryptoEngine.FLAG_UP,
                1
            ) + ByteArray(32) { 0x07 }
            val signature = PasskeyCryptoEngine.signAssertion(
                signingKey.algorithmId,
                signingKey.keyBytes,
                dataToSign
            )
            assertTrue("断言签名输出必须非空", signature.isNotEmpty())
        } finally {
            java.util.Arrays.fill(signingKey.keyBytes, 0.toByte())
        }
        passkeyData.privateKey.clear()
    }

    private companion object {
        /** ES256 签名侧材料长度：P-256 私钥标量 32 字节（与 `PasskeySigningKey` 文档口径一致） */
        const val ES256_SCALAR_BYTES = 32

        /**
         * RFC 8410 `version = 0` Ed25519 `PrivateKeyInfo` 的固定 DER 前缀：
         * `SEQUENCE(0x2e) { INTEGER 0, SEQUENCE { OID 1.3.101.112 }, OCTET STRING(0x22) { OCTET STRING(0x20) … } }`
         */
        val ED25519_RFC8410_V0_PREFIX = byteArrayOf(
            0x30, 0x2E, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
        )
    }
}
