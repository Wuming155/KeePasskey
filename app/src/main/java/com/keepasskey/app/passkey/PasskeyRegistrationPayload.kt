package com.keepasskey.app.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.cbor.CborEncoder
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import com.keepasskey.crypto.passkey.PasskeyPrf
import java.util.Base64

internal object PasskeyRegistrationPayload {

    /**
     * 组装标准 WebAuthn 注册响应 JSON（authData 中携带 AT 位与 UV 位）。
     * 无证明声明采用 `fmt="none"`；私钥不参与该路径，仅使用公钥构建 COSE 键。
     *
     * ISSUE-P1-02 内存脱敏边界声明：
     * - 私钥全程不进入本路径——生成侧已保证零 String 中间量，私钥唯一长期持有者是落库条目内
     *   的 [com.keepasskey.core.security.ProtectedString]（密文驻留，且经
     *   [PasskeyData.toCustomFields] 零拷贝别名共享，**不可 clear**）；
     * - credentialId / 公钥 / authData / attestationObject 均为 WebAuthn 规范定义的公开材料，
     *   派生字节数组仍统一 try/finally 擦除，保持防御一致性；
     * - 不可消解的 String 边界：系统 Credential Manager 契约要求响应为 JSON 字符串
     *   （[CreatePublicKeyCredentialResponse]），该不可变实例无法显式清零——其内容均为
     *   公开注册材料（不含私钥），属受控且可接受的驻留。
     */
    fun build(
        passkeyData: PasskeyData,
        challenge: String,
        callerPackage: String?,
        flags: Byte,
        prfEval: WebAuthnRequest.PrfEval?,
        rpId: String,
        origin: String
    ): String {
        var authData: ByteArray? = null
        var attestationObjectBytes: ByteArray? = null
        try {
            val credIdBytes = WebAuthnRequest.base64UrlDecode(passkeyData.credentialId)
                ?: Base64.getUrlDecoder().decode(passkeyData.credentialId)
            val pubBytes = Base64.getDecoder().decode(passkeyData.publicKeyBase64)
            val coseKeyBytes = PasskeyCryptoEngine.coseKeyFor(passkeyData.algorithmId, pubBytes)

            authData = PasskeyCryptoEngine.buildAuthenticatorData(
                rpId = rpId,
                flags = flags,
                signCount = 0,
                credentialId = credIdBytes,
                cosePublicKey = coseKeyBytes
            )

            val attestationMap = linkedMapOf<String, Any>(
                WebAuthnJson.FORMAT to WebAuthnJson.FORMAT_NONE,
                WebAuthnJson.ATTESTATION_STATEMENT to emptyMap<String, Any>(),
                WebAuthnJson.AUTHENTICATOR_DATA to authData
            )
            attestationObjectBytes = CborEncoder.encodeMap(attestationMap)

            // ISSUE-P3-188 第 4 项 §174 路线②：组装走宿主可用的 [WebAuthnJsonWriter]
            // （序列化语义与平台 org.json 一致，设备侧对拍用例锁定）
            val clientDataJson = WebAuthnJsonWriter.obj {
                str(WebAuthnJson.TYPE, WebAuthnJson.CLIENT_DATA_TYPE_CREATE)
                str(WebAuthnJson.CHALLENGE, challenge)
                str(WebAuthnJson.ORIGIN, origin.ifBlank { "https://$rpId" })
                // ISSUE-P2-72：归属字段只写**系统背书**的调用方包名；取不到即省略该字段——
                // 绝不回退为本应用包名（那会把 RP 收到的归属伪造成我们）。
                CallingOriginResolver.clientDataAndroidPackageName(callerPackage)?.let {
                    str(WebAuthnJson.ANDROID_PACKAGE_NAME, it)
                }
            }

            val b64Url = Base64.getUrlEncoder().withoutPadding()
            val clientDataBase64 = b64Url.encodeToString(clientDataJson.toByteArray(Charsets.UTF_8))
            val attestationBase64 = b64Url.encodeToString(attestationObjectBytes)

            return WebAuthnJsonWriter.obj {
                str(WebAuthnJson.ID, passkeyData.credentialId)
                str(WebAuthnJson.RAW_ID, passkeyData.credentialId)
                str(WebAuthnJson.TYPE, WebAuthnJson.CREDENTIAL_TYPE_PUBLIC_KEY)
                str(WebAuthnJson.AUTHENTICATOR_ATTACHMENT, WebAuthnJson.ATTACHMENT_PLATFORM)
                obj(
                    WebAuthnJson.CLIENT_EXTENSION_RESULTS,
                    buildPrfClientExtensionResultsObj(prfEval, passkeyData.prfSecret, isRegistration = true)
                )
                obj(WebAuthnJson.RESPONSE, WebAuthnJsonWriter.Obj().apply {
                    str(WebAuthnJson.CLIENT_DATA_JSON, clientDataBase64)
                    str(WebAuthnJson.ATTESTATION_OBJECT, attestationBase64)
                    strArray(WebAuthnJson.TRANSPORTS, listOf(WebAuthnJson.TRANSPORT_INTERNAL))
                })
            }
        } finally {
            authData?.fill(0)
            attestationObjectBytes?.fill(0)
        }
    }

    /**
     * 组装 `clientExtensionResults.prf`（WebAuthn Level 3 §10.1）：
     * - 注册：`enabled = true`；请求带 `eval` 时同时回传 `results`；
     * - 断言：仅当确能计算出结果时回传 `results`（否则空对象，绝不谎报）。
     */
    private fun buildPrfClientExtensionResultsObj(
        prfEval: WebAuthnRequest.PrfEval?,
        prfSecret: ProtectedString?,
        isRegistration: Boolean
    ): WebAuthnJsonWriter.Obj {
        if (prfEval == null) return WebAuthnJsonWriter.Obj()
        val b64Url = Base64.getUrlEncoder().withoutPadding()
        val prf = WebAuthnJsonWriter.Obj()
        if (prfSecret == null) {
            if (!isRegistration) return WebAuthnJsonWriter.Obj()
            prf.bool(WebAuthnJson.ENABLED, true)
            return WebAuthnJsonWriter.Obj().apply { obj(WebAuthnJson.PRF, prf) }
        }
        prf.bool(WebAuthnJson.ENABLED, true)
        val first = PasskeyPrf.computeValue(prfSecret, prfEval.first)
        try {
            val results = WebAuthnJsonWriter.Obj().apply {
                str(WebAuthnJson.FIRST, b64Url.encodeToString(first))
            }
            val secondInput = prfEval.second
            if (secondInput != null) {
                val second = PasskeyPrf.computeValue(prfSecret, secondInput)
                try {
                    results.str(WebAuthnJson.SECOND, b64Url.encodeToString(second))
                } finally {
                    second.fill(0)
                }
            }
            prf.obj(WebAuthnJson.RESULTS, results)
        } finally {
            first.fill(0)
        }
        return WebAuthnJsonWriter.Obj().apply { obj(WebAuthnJson.PRF, prf) }
    }
}
