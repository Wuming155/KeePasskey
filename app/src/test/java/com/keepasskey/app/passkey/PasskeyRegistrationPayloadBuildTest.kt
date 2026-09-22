package com.keepasskey.app.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注册响应材料 `PasskeyRegistrationPayload.build(...)` 的**宿主离线断言**（`ISSUE-P2-265`）。
 *
 * ## 为什么补这一组
 *
 * 核对参考实现时发现：本仓**注册材料此前没有任何宿主级断言**——`buildAuthenticatorData` /
 * `coseKeyFor` / `CborEncoder` 在 `app/src/test` 与 `crypto/src/test` 的命中数为 **0**，
 * 只能靠「自家 writer → 自家 reader」自证。而跨实现差异恰恰只能由外部口径暴露
 * （`ISSUE-P2-210` 的 Ed25519 PKCS#8 缺陷正是这样逃逸数月；见 `AGENTS.md` 规则 8）。
 *
 * ## 与两个参考实现的对照口径（`ISSUE-P2-265`）
 *
 * | 判据 | KeePassDX | Monica | 本测试断言 |
 * |---|---|---|---|
 * | `response.authenticatorData` | ✅ | ✅ | ✅ 存在且为同一份 authData |
 * | `response.publicKey` | ✅ (COSE) | ✅ (SPKI) | ✅ 存在（本仓取 COSE 口径） |
 * | `response.publicKeyAlgorithm` | ✅ 数字 | ✅ 数字 | ✅ 等于 COSE 编号 |
 * | `response.transports` | internal+hybrid | internal+hybrid | ✅ 含 `hybrid` |
 * | `credProps` | 注册恒回 | 请求才回 | ✅ **请求才回**（取更规范的一侧） |
 * | `clientDataJSON.crossOrigin` | 可选 | 显式 `false` | ✅ 显式 `false` |
 *
 * 离线可执行：组装走 [WebAuthnJsonWriter]、解析走 [SimpleJson]，均不依赖 Android 运行时。
 */
class PasskeyRegistrationPayloadBuildTest {

    private val rpId = "rp.example"
    private val challenge = "dGVzdC1jaGFsbGVuZ2U"
    private val callerPackage = "com.example.app"

    private fun es256Passkey(): PasskeyData =
        PasskeyCryptoEngine.generateEs256KeyPair(
            relyingPartyId = rpId,
            userName = "user@example.com",
            userHandle = "user-handle",
            userDisplayName = "Test User"
        )

    private fun build(
        passkeyData: PasskeyData,
        credPropsRequested: Boolean = false,
        caller: String? = callerPackage
    ): Map<String, Any?> {
        val flags = requireNotNull(
            PasskeyAuthFlags.forRegistration(CredentialUserVerification.BiometricSucceeded)
        ) { "强验证结果必须可投影为注册 flags" }
        val json = PasskeyRegistrationPayload.build(
            passkeyData = passkeyData,
            challenge = challenge,
            callerPackage = caller,
            flags = flags,
            prfEval = null,
            rpId = rpId,
            origin = "android:apk-key-hash:AAAA",
            credPropsRequested = credPropsRequested
        )
        return requireNotNull(SimpleJson.asObject(SimpleJson.parse(json))) {
            "注册响应必须是 JSON 对象"
        }
    }

    private fun responseOf(root: Map<String, Any?>): Map<String, Any?> =
        requireNotNull(SimpleJson.objectAt(root, WebAuthnJson.RESPONSE)) { "缺少 response 对象" }

    private fun requiredString(container: Map<String, Any?>, key: String): String =
        requireNotNull(SimpleJson.string(container, key)) { "缺少字段 $key" }

    private fun decodeB64Url(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    @Test
    fun `一 响应补齐参考实现要求的字段`() {
        val root = build(es256Passkey())
        val response = responseOf(root)

        assertNotNull(
            "缺少 response.clientDataJSON",
            SimpleJson.string(response, WebAuthnJson.CLIENT_DATA_JSON)
        )
        assertNotNull(
            "缺少 response.attestationObject",
            SimpleJson.string(response, WebAuthnJson.ATTESTATION_OBJECT)
        )
        assertNotNull(
            "缺少 response.authenticatorData（KeePassDX / Monica 均下发）",
            SimpleJson.string(response, WebAuthnJson.AUTHENTICATOR_DATA)
        )
        assertNotNull(
            "缺少 response.publicKey（KeePassDX / Monica 均下发）",
            SimpleJson.string(response, WebAuthnJson.PUBLIC_KEY)
        )

        val algorithm = SimpleJson.int(response, WebAuthnJson.PUBLIC_KEY_ALGORITHM)
        assertNotNull("缺少 response.publicKeyAlgorithm（须为数字）", algorithm)
        assertTrue(
            "publicKeyAlgorithm 必须是 COSE 编号（ES256 = -7），实际 $algorithm",
            algorithm == PasskeyData.ALGORITHM_ES256
        )

        val transports = requireNotNull(SimpleJson.arrayAt(response, WebAuthnJson.TRANSPORTS)) {
            "缺少 response.transports"
        }
        assertTrue("transports 必须含 internal", WebAuthnJson.TRANSPORT_INTERNAL in transports)
        assertTrue(
            "transports 必须含 hybrid（两个参考实现均声明）",
            WebAuthnJson.TRANSPORT_HYBRID in transports
        )

        // 信封顶层字段
        assertTrue(
            "type 必须是 public-key",
            SimpleJson.string(root, WebAuthnJson.TYPE) == WebAuthnJson.CREDENTIAL_TYPE_PUBLIC_KEY
        )
        assertNotNull("缺少 id", SimpleJson.string(root, WebAuthnJson.ID))
        assertNotNull("缺少 rawId", SimpleJson.string(root, WebAuthnJson.RAW_ID))
        assertNotNull(
            "clientExtensionResults 必须存在（RP 按字段存在性判兼容）",
            SimpleJson.objectAt(root, WebAuthnJson.CLIENT_EXTENSION_RESULTS)
        )
    }

    @Test
    fun `二 authenticatorData 与 attestationObject 内嵌 authData 同源且 AAGUID 非全零`() {
        val response = responseOf(build(es256Passkey()))
        val authData = decodeB64Url(requiredString(response, WebAuthnJson.AUTHENTICATOR_DATA))
        val attestation = decodeB64Url(requiredString(response, WebAuthnJson.ATTESTATION_OBJECT))

        // `attestationObject` 是 canonical CBOR 的 {fmt, attStmt, authData}；按编码字节序
        // authData 恒为最后一个值（`"fmt"`=0x63… < `"attStmt"`=0x67… < `"authData"`=0x68…），
        // 故可直接用「尾部相等」锁定「两处 authData 是同一份字节」，防止出现第二真相源。
        assertTrue(
            "attestationObject 必须比 authData 长（含 map 头与前两个字段）",
            attestation.size > authData.size
        )
        assertArrayEquals(
            "attestationObject 内嵌的 authData 必须与 response.authenticatorData 逐字节一致",
            authData,
            attestation.copyOfRange(attestation.size - authData.size, attestation.size)
        )

        // 注册材料必须声明 AT（含证明凭据数据），否则 RP 取不到公钥
        assertTrue(
            "flags 必须置 AT (0x40)",
            (authData[32].toInt() and PasskeyCryptoEngine.FLAG_AT.toInt()) != 0
        )
        // ISSUE-P2-265：AAGUID 不再是全零（全零语义为「该认证器没有 AAGUID」）
        val aaguid = authData.copyOfRange(37, 53)
        assertTrue(
            "authData 内的 AAGUID 不得为全零（参考实现均登记真实值）",
            aaguid.any { it != 0.toByte() }
        )
    }

    @Test
    fun `三 clientDataJSON 原样透传 challenge 且显式 crossOrigin=false`() {
        val response = responseOf(build(es256Passkey()))
        val clientDataBytes =
            decodeB64Url(requiredString(response, WebAuthnJson.CLIENT_DATA_JSON))
        val clientData = requireNotNull(
            SimpleJson.asObject(SimpleJson.parse(String(clientDataBytes, Charsets.UTF_8)))
        ) { "clientDataJSON 必须是 JSON 对象" }

        assertTrue(
            "type 必须是 webauthn.create",
            SimpleJson.string(clientData, WebAuthnJson.TYPE) == WebAuthnJson.CLIENT_DATA_TYPE_CREATE
        )
        assertTrue(
            "challenge 必须与请求值逐字一致（严禁解码再编码）",
            SimpleJson.string(clientData, WebAuthnJson.CHALLENGE) == challenge
        )
        assertTrue(
            "crossOrigin 必须显式下发",
            clientData.containsKey(WebAuthnJson.CROSS_ORIGIN)
        )
        assertTrue(
            "crossOrigin 必须为 false（非跨源上下文）",
            clientData[WebAuthnJson.CROSS_ORIGIN] == false
        )
        assertTrue(
            "有系统背书包名时必须写入 androidPackageName",
            SimpleJson.string(clientData, WebAuthnJson.ANDROID_PACKAGE_NAME) == callerPackage
        )

        // 取不到调用方包名时省略该字段，且绝不回退为本应用包名（ISSUE-P2-72 回归）
        val withoutCaller = responseOf(build(es256Passkey(), caller = null))
        val cd2 = requireNotNull(
            SimpleJson.asObject(
                SimpleJson.parse(
                    String(
                        decodeB64Url(requiredString(withoutCaller, WebAuthnJson.CLIENT_DATA_JSON)),
                        Charsets.UTF_8
                    )
                )
            )
        )
        assertFalse(
            "包名取不到时不得出现 androidPackageName",
            cd2.containsKey(WebAuthnJson.ANDROID_PACKAGE_NAME)
        )
    }

    @Test
    fun `四 credProps 仅在请求携带时才回传且 rk=true`() {
        // 未请求：不得凭空出现 credProps（绝不伪造扩展输出）
        val notRequested = build(es256Passkey(), credPropsRequested = false)
        val ext0 = requireNotNull(
            SimpleJson.objectAt(notRequested, WebAuthnJson.CLIENT_EXTENSION_RESULTS)
        )
        assertFalse(
            "未请求 credProps 时不得回传该扩展",
            ext0.containsKey(WebAuthnJson.CRED_PROPS)
        )

        // 请求：回传 credProps.rk = true（本仓凭据按 rpId 可发现，如实报 true）
        val requested = build(es256Passkey(), credPropsRequested = true)
        val ext1 = requireNotNull(
            SimpleJson.objectAt(requested, WebAuthnJson.CLIENT_EXTENSION_RESULTS)
        )
        val credProps = requireNotNull(
            SimpleJson.objectAt(ext1, WebAuthnJson.CRED_PROPS)
        ) { "请求 credProps 时必须回传该扩展" }
        assertTrue("credProps.rk 必须为 true", credProps[WebAuthnJson.RK] == true)
    }
}
