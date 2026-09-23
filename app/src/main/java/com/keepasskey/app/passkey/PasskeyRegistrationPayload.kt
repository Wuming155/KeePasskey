package com.keepasskey.app.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.cbor.CborEncoder
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import com.keepasskey.crypto.passkey.PasskeyPrf
import java.util.Base64

internal object PasskeyRegistrationPayload {

    /** [build] 的中间产物：authData 与 attestationObject 均属公开注册材料，仍统一擦除。 */
    private class AttestationMaterials(
        val authData: ByteArray,
        val attestationObject: ByteArray,
        val coseKeyBytes: ByteArray,
        val spkiBytes: ByteArray
    ) {
        fun wipe() {
            authData.fill(0)
            attestationObject.fill(0)
            coseKeyBytes.fill(0)
            spkiBytes.fill(0)
        }
    }

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
     *
     * §280：组装步骤下沉 [buildAttestationMaterials] / [buildClientDataJson] / [buildRegistrationJson]。
     */
    fun build(
        passkeyData: PasskeyData,
        challenge: String,
        callerPackage: String?,
        flags: Byte,
        prfEval: WebAuthnRequest.PrfEval?,
        rpId: String,
        origin: String,
        /** 请求是否携带 `extensions.credProps`（决定是否回传 `credProps.rk`） */
        credPropsRequested: Boolean = false
    ): String {
        val materials = buildAttestationMaterials(passkeyData, flags, rpId)
        return try {
            val b64Url = Base64.getUrlEncoder().withoutPadding()
            val clientDataJson = buildClientDataJson(challenge, callerPackage, rpId, origin)
            val clientDataBase64 = b64Url.encodeToString(clientDataJson.toByteArray(Charsets.UTF_8))
            buildRegistrationJson(
                passkeyData = passkeyData,
                materials = materials,
                clientDataBase64 = clientDataBase64,
                b64Url = b64Url,
                prfEval = prfEval,
                credPropsRequested = credPropsRequested
            )
        } finally {
            materials.wipe()
        }
    }

    /**
     * 构造 `attestationObject` 与其同源材料（COSE 公钥 / SPKI DER / authData）。
     *
     * ⚠️ CBOR map 的第三个键必须是 **`authData`**（CTAP2 §6.5.4），而**不是**
     * `authenticatorData`——后者是响应 JSON 里 `response.authenticatorData` 的字段名。
     * 此处曾误用 `WebAuthnJson.AUTHENTICATOR_DATA`，使浏览器在把 CredMan 响应转成
     * WebAuthn 对象时报 `field missing or invalid: attestationObject`（详见 [WebAuthnJson.AUTH_DATA]）。
     */
    private fun buildAttestationMaterials(
        passkeyData: PasskeyData,
        flags: Byte,
        rpId: String
    ): AttestationMaterials {
        val credIdBytes = WebAuthnRequest.base64UrlDecode(passkeyData.credentialId)
            ?: Base64.getUrlDecoder().decode(passkeyData.credentialId)
        val pubBytes = Base64.getDecoder().decode(passkeyData.publicKeyBase64)
        // `attestationObject.authData.credentialPublicKey` 必须是 **COSE_Key CBOR**；
        // 而响应体的 `response.publicKey` 按规范必须是 **SPKI DER**——两者是同一公钥的
        // 不同编码，故此处各取所需（此前 `publicKey` 误用了 COSE，与参考实现 Monica 不一致）。
        val coseKeyBytes = PasskeyCryptoEngine.coseKeyFor(passkeyData.algorithmId, pubBytes)
        val spkiBytes = PasskeyCryptoEngine.publicKeySubjectInfoFor(passkeyData.algorithmId, pubBytes)
        val authDataBytes = PasskeyCryptoEngine.buildAuthenticatorData(
            rpId = rpId,
            flags = flags,
            signCount = 0,
            credentialId = credIdBytes,
            cosePublicKey = coseKeyBytes
        )
        val attestationMap = linkedMapOf<String, Any>(
            WebAuthnJson.FORMAT to WebAuthnJson.FORMAT_NONE,
            WebAuthnJson.ATTESTATION_STATEMENT to emptyMap<String, Any>(),
            WebAuthnJson.AUTH_DATA to authDataBytes
        )
        val attestationObjectBytes = CborEncoder.encodeMap(attestationMap)
        return AttestationMaterials(
            authData = authDataBytes,
            attestationObject = attestationObjectBytes,
            coseKeyBytes = coseKeyBytes,
            spkiBytes = spkiBytes
        )
    }

    /**
     * 组装 `clientDataJSON`。
     *
     * ISSUE-P2-72：归属字段只写**系统背书**的调用方包名；取不到即省略该字段——
     * 绝不回退为本应用包名（那会把 RP 收到的归属伪造成我们）。
     * ISSUE-P2-265：显式声明非跨源上下文（规范允许省略、默认 false），
     * 对齐 Monica 的写法，使 RP 不必依赖缺省语义。
     */
    private fun buildClientDataJson(
        challenge: String,
        callerPackage: String?,
        rpId: String,
        origin: String
    ): String = WebAuthnJsonWriter.obj {
        str(WebAuthnJson.TYPE, WebAuthnJson.CLIENT_DATA_TYPE_CREATE)
        str(WebAuthnJson.CHALLENGE, challenge)
        str(WebAuthnJson.ORIGIN, origin.ifBlank { "https://$rpId" })
        CallingOriginResolver.clientDataAndroidPackageName(callerPackage)?.let {
            str(WebAuthnJson.ANDROID_PACKAGE_NAME, it)
        }
        bool(WebAuthnJson.CROSS_ORIGIN, false)
    }

    /**
     * 组装最终注册响应 JSON。
     *
     * ISSUE-P3-188 第 4 项 §174 路线②：组装走宿主可用的 [WebAuthnJsonWriter]
     * （序列化语义与平台 org.json 一致，设备侧对拍用例锁定）。
     * ISSUE-P2-265：与两个参考实现对齐，补齐三项被消费方读取的字段——
     * `authenticatorData` / `publicKey` / `publicKeyAlgorithm`(COSE alg 号)。
     * `authenticatorData` 与 `authData` 同源；`publicKey` 按 W3C 规范下发
     * **SPKI DER**（`AuthenticatorAttestationResponse.getPublicKey()` 语义，
     * 对齐 Monica 的 `keyPair.public.encoded`），**不再是** COSE_Key CBOR——
     * COSE 编码只保留在 `attestationObject.authData.credentialPublicKey`。
     * 缺失时消费方需自行解 CBOR，补齐可省掉该解析并提升互操作性。
     * 参考实现均声明 `hybrid`（跨设备扫码），本仓此前只声明 `internal`。
     */
    private fun buildRegistrationJson(
        passkeyData: PasskeyData,
        materials: AttestationMaterials,
        clientDataBase64: String,
        b64Url: Base64.Encoder,
        prfEval: WebAuthnRequest.PrfEval?,
        credPropsRequested: Boolean
    ): String = WebAuthnJsonWriter.obj {
        str(WebAuthnJson.ID, passkeyData.credentialId)
        str(WebAuthnJson.RAW_ID, passkeyData.credentialId)
        str(WebAuthnJson.TYPE, WebAuthnJson.CREDENTIAL_TYPE_PUBLIC_KEY)
        str(WebAuthnJson.AUTHENTICATOR_ATTACHMENT, WebAuthnJson.ATTACHMENT_PLATFORM)
        obj(
            WebAuthnJson.CLIENT_EXTENSION_RESULTS,
            buildClientExtensionResults(
                prfEval = prfEval,
                prfSecret = passkeyData.prfSecret,
                credPropsRequested = credPropsRequested,
                isRegistration = true
            )
        )
        obj(WebAuthnJson.RESPONSE, WebAuthnJsonWriter.Obj().apply {
            str(WebAuthnJson.CLIENT_DATA_JSON, clientDataBase64)
            str(WebAuthnJson.ATTESTATION_OBJECT, b64Url.encodeToString(materials.attestationObject))
            str(WebAuthnJson.AUTHENTICATOR_DATA, b64Url.encodeToString(materials.authData))
            str(WebAuthnJson.PUBLIC_KEY, b64Url.encodeToString(materials.spkiBytes))
            int(WebAuthnJson.PUBLIC_KEY_ALGORITHM, passkeyData.algorithmId)
            strArray(
                WebAuthnJson.TRANSPORTS,
                listOf(WebAuthnJson.TRANSPORT_INTERNAL, WebAuthnJson.TRANSPORT_HYBRID)
            )
        })
    }

    /**
     * 组装注册响应的 `clientExtensionResults`（`ISSUE-P2-265`）。
     *
     * - **`credProps`**（WebAuthn L3 §10.2）：仅**注册**且请求携带 `extensions.credProps` 时
     *   回传 `{"credProps":{"rk":true}}`。`rk` 表示该凭据是否可被发现——本仓凭据断言时按
     *   rpId 全库匹配即可取出，**确实可发现**，故如实报 `true`（KeePassDX 恒报 `true`；
     *   Monica 依据请求的 `residentKey` 判定后回传）。此前本仓**从不回传**该扩展。
     * - **`prf`**（WebAuthn L3 §10.1）：见 [buildPrfObj]。
     *
     * 无任何内容可回传时返回**空对象**（绝不伪造输出）。
     */
    private fun buildClientExtensionResults(
        prfEval: WebAuthnRequest.PrfEval?,
        prfSecret: ProtectedString?,
        credPropsRequested: Boolean,
        isRegistration: Boolean
    ): WebAuthnJsonWriter.Obj {
        val out = WebAuthnJsonWriter.Obj()
        if (credPropsRequested && isRegistration) {
            out.obj(
                WebAuthnJson.CRED_PROPS,
                WebAuthnJsonWriter.Obj().apply { bool(WebAuthnJson.RK, true) }
            )
        }
        val prf = buildPrfObj(prfEval, prfSecret, isRegistration) ?: return out
        out.obj(WebAuthnJson.PRF, prf)
        return out
    }

    /**
     * 组装 `clientExtensionResults.prf`（WebAuthn Level 3 §10.1）：
     * - 注册：`enabled = true`；请求带 `eval` 时同时回传 `results`；
     * - 断言：仅当确能计算出结果时回传 `results`（否则返回 `null`，绝不谎报）。
     */
    private fun buildPrfObj(
        prfEval: WebAuthnRequest.PrfEval?,
        prfSecret: ProtectedString?,
        isRegistration: Boolean
    ): WebAuthnJsonWriter.Obj? {
        if (prfEval == null) return null
        val b64Url = Base64.getUrlEncoder().withoutPadding()
        val prf = WebAuthnJsonWriter.Obj()
        if (prfSecret == null) {
            if (!isRegistration) return null
            prf.bool(WebAuthnJson.ENABLED, true)
            return prf
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
        return prf
    }
}
