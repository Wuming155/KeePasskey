package com.keepasskey.app.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断言响应材料 `PasskeyAssertionPayload.build(...)` 的**宿主离线断言**（`ISSUE-P3-188` 剩余清单
 * 第 4 项的主验收，§173 打开的新验证面）。
 *
 * 此前该组装逻辑在 `PasskeyAssertionActivity`（或下沉后的 `org.json` 组装）里只能靠静态接线守卫 +
 * 设备侧用例——`org.json` 在宿主 JVM 是 Android 桩（调用即抛）。组装改走
 * [WebAuthnJsonWriter]（§174 路线②）后，下列四条安全判据第一次变为**可离线断言**：
 *
 * 1. `clientDataPackage` 为 null 时 `clientDataJSON` **不得出现** `androidPackageName` 字段，
 *    且**绝不**回退为本应用包名（ISSUE-P2-72 的宿主回归）；
 * 2. `prfEval == null` 时 `clientExtensionResults` 必须是**空对象**（不伪造 prf 输出）；
 * 3. `authenticatorData` 为 37 字节、前 32 字节等于 `SHA-256(rpId)`、第 33 字节等于 flags、
 *    第 34~37 字节等于 `signCount`（大端）——即 WebAuthn 标准布局
 *    （条目原文「前 16 字节 / 第 5~8 字节」系笔误，按标准口径执行）；
 * 4. 签名可用该凭据公钥对 `authData || SHA-256(clientDataJSON)` 验过。
 *
 * **不做**（条目明示）：不覆盖 Credential Manager 交互（该部分证据仍在设备侧）。
 * 密钥对由宿主 JCE 现场生成（secp256r1），私钥按仓内 PKCS#8 PEM 契约（64 列折行，
 * 与 KeePassXC / OpenSSL 产物同排版）包入 [PasskeyData]——解析 / 签名链路仍走**生产代码**
 * （`PasskeyCryptoEngine.decodePemPrivateKeyText` → `signAssertion`）；验签用 JCE 公钥。
 * JSON 解析一律走本仓 [SimpleJson]（严格、无隐式强转，宿主可执行）。
 */
class PasskeyAssertionPayloadBuildTest {

    /** 宿主 JCE 生成的 secp256r1 密钥对（私钥经 PEM 契约喂给生产解析链路，公钥供验签） */
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val passkeyData: PasskeyData = buildPasskeyData()

    private fun buildPasskeyData(): PasskeyData {
        val der = requireNotNull(keyPair.private.encoded) { "JCE 私钥必须可编码为 PKCS#8" }
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        val pem = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
        return PasskeyData(
            relyingPartyId = RP_ID,
            userHandle = BASE64URL.encodeToString("payload-user".toByteArray(Charsets.UTF_8)),
            userName = "payload-build-test",
            credentialId = BASE64URL.encodeToString(ByteArray(16) { (it + 1).toByte() }),
            algorithmId = PasskeyData.ALGORITHM_ES256,
            // 本测试的公钥消费走 [keyPair.public]（JCE），该字段按仓内数据契约仍必填
            publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            privateKey = ProtectedString(isProtected = false, pem.toByteArray(Charsets.US_ASCII))
        )
    }

    private fun buildAssertion(
        clientDataPackage: String? = null,
        prfEval: WebAuthnRequest.PrfEval? = null,
        signCount: Int = 7,
        flags: Byte = 0x05
    ): String = PasskeyAssertionPayload.build(
        passkeyData = passkeyData,
        origin = "https://$RP_ID/session",
        challenge = BASE64URL.encodeToString(ByteArray(32) { it.toByte() }),
        clientDataPackage = clientDataPackage,
        flags = flags,
        signCount = signCount,
        providedClientDataHash = null,
        prfEval = prfEval
    )

    private fun parseClientDataJson(assertionJson: String): Map<String, Any?> {
        val root = SimpleJson.asObject(SimpleJson.parse(assertionJson))
            ?: throw AssertionError("断言响应不是 JSON 对象")
        val response = requireNotNull(SimpleJson.objectAt(root, WebAuthnJson.RESPONSE))
        val clientDataBase64 = requireNotNull(SimpleJson.string(response, WebAuthnJson.CLIENT_DATA_JSON))
        val clientDataBytes = Base64.getUrlDecoder().decode(clientDataBase64)
        return SimpleJson.asObject(SimpleJson.parse(String(clientDataBytes, Charsets.UTF_8)))
            ?: throw AssertionError("clientDataJSON 不是 JSON 对象")
    }

    private fun decodeB64Url(container: Map<String, Any?>, key: String): ByteArray =
        Base64.getUrlDecoder().decode(requireNotNull(SimpleJson.string(container, key)))

    @Test
    fun `一 clientDataPackage 为 null 时归属字段省略且绝不回退为本应用包名`() {
        // null 形态：clientDataJSON 不得含归属字段
        val clientData = parseClientDataJson(buildAssertion(clientDataPackage = null))
        assertFalse(
            "clientDataJSON 不得出现 androidPackageName 字段（系统背书包名取不到即省略）",
            clientData.containsKey(WebAuthnJson.ANDROID_PACKAGE_NAME)
        )

        // 正确归属形态（对照）：传入调用方包名时按原样写入
        val withCaller = parseClientDataJson(buildAssertion(clientDataPackage = "com.other.rp.app"))
        assertEquals(
            "传入调用方包名时必须原样写入归属字段",
            "com.other.rp.app",
            SimpleJson.string(withCaller, WebAuthnJson.ANDROID_PACKAGE_NAME)
        )

        // 绝不回退为本应用包名：整份响应中不得出现本应用包名字符串
        val raw = buildAssertion(clientDataPackage = null)
        assertFalse(
            "clientDataPackage 为 null 的响应不得出现本应用包名（归属伪造就地拒绝）",
            raw.contains("com.keepasskey")
        )
    }

    @Test
    fun `二 prfEval 为 null 时 clientExtensionResults 必须是空对象`() {
        val root = SimpleJson.asObject(SimpleJson.parse(buildAssertion(prfEval = null)))
            ?: throw AssertionError("断言响应不是 JSON 对象")
        val extensions = SimpleJson.objectAt(root, WebAuthnJson.CLIENT_EXTENSION_RESULTS)
        assertTrue(
            "clientExtensionResults 必须存在（不得缺席——RP 侧按字段存在性判兼容）",
            extensions != null
        )
        assertEquals(
            "prfEval == null 时 clientExtensionResults 必须为空对象（绝不伪造 prf 输出）",
            emptyMap<String, Any?>(),
            extensions
        )
    }

    @Test
    fun `三 authenticatorData 布局符合 WebAuthn 标准（37 字节）`() {
        val root = SimpleJson.asObject(SimpleJson.parse(buildAssertion(signCount = 7, flags = 0x05)))
            ?: throw AssertionError("断言响应不是 JSON 对象")
        val response = requireNotNull(SimpleJson.objectAt(root, WebAuthnJson.RESPONSE))
        val authData = decodeB64Url(response, WebAuthnJson.AUTHENTICATOR_DATA)

        assertEquals("无 AT 位时 authenticatorData 必须为 37 字节", 37, authData.size)

        val rpIdHash = MessageDigest.getInstance("SHA-256")
            .digest(RP_ID.toByteArray(Charsets.UTF_8))
        assertTrue(
            "前 32 字节必须等于 SHA-256(rpId)",
            rpIdHash.contentEquals(authData.copyOfRange(0, 32))
        )
        assertEquals("第 33 字节必须等于传入 flags", 0x05.toByte(), authData[32])
        val signCountBigEndian = ((authData[33].toInt() and 0xFF) shl 24) or
            ((authData[34].toInt() and 0xFF) shl 16) or
            ((authData[35].toInt() and 0xFF) shl 8) or
            (authData[36].toInt() and 0xFF)
        assertEquals("第 34~37 字节必须等于传入 signCount（大端）", 7, signCountBigEndian)
    }

    @Test
    fun `四 签名可用该凭据公钥对 authData 与 clientDataHash 之拼接验过`() {
        val assertionJson = buildAssertion(signCount = 9)
        val root = SimpleJson.asObject(SimpleJson.parse(assertionJson))!!
        val response = requireNotNull(SimpleJson.objectAt(root, WebAuthnJson.RESPONSE))

        val authData = decodeB64Url(response, WebAuthnJson.AUTHENTICATOR_DATA)
        val clientDataBytes = decodeB64Url(response, WebAuthnJson.CLIENT_DATA_JSON)
        val signature = decodeB64Url(response, WebAuthnJson.SIGNATURE)

        val dataToSign = authData + MessageDigest.getInstance("SHA-256").digest(clientDataBytes)

        val verified = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(keyPair.public as ECPublicKey)
            update(dataToSign)
        }.verify(signature)

        assertTrue(
            "ES256 签名必须可由该凭据公钥对 authData || SHA-256(clientDataJSON) 验过（生产解析与签名链路）",
            verified
        )
    }

    private companion object {
        const val RP_ID = "rp.example"
        val BASE64URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
