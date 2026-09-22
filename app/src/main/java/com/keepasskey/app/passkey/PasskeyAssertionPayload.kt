package com.keepasskey.app.passkey

import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import com.keepasskey.crypto.passkey.PasskeyPrf
import java.security.MessageDigest
import java.util.Base64

/**
 * 通行密钥**断言（Get）响应材料**的组装与私钥解码（`ISSUE-P3-188` §173 自
 * [PasskeyAssertionActivity] 逐字搬入，与 §162 的 `PasskeyRegistrationPayload` 对称）。
 *
 * 之所以能离开通用 Activity：这一族只做「凭据字段 + 本次会话参数 → 响应 JSON」的纯计算，
 * 不碰 Activity 的 `Intent` / `PendingIntentHandler` / 门控状态；但**安全语义一并随迁且未放宽**：
 *
 * 1. 私钥只经 `usePrivateKeyBytes` 的受控字节流取用，解码为独立副本后交给
 *    `signAssertionConsumingKey` —— 该副本由加密引擎在 `finally` 中**单点清零**（ISSUE-P3-212），
 *    活动层不再自行承担私钥副本的生命周期；
 * 2. 派生中间量（authData / clientDataBytes / dataToSign）统一在 `finally` 擦除（ISSUE-P1-02）；
 * 3. `clientDataJSON` 的归属字段只写**系统背书**的调用方包名，取不到即省略（ISSUE-P2-72）；
 * 4. PRF 输出算不出时返回空对象，**绝不伪造**（WebAuthn Level 3 §10.1）。
 *
 * 组装口径（`ISSUE-P3-188` 第 4 项 §174 路线②）：JSON 组装走 [WebAuthnJsonWriter]
 * （宿主可用、序列化语义与平台 `org.json` 一致且由设备侧对拍锁定），使本文件的四条安全判据
 * 可被宿主单测离线断言（`PasskeyAssertionPayloadBuildTest`）。
 *
 * 日志 tag 沿用 `"PasskeyAssertionActivity"`，使迁移前后的 logcat 归因保持不变。
 */
internal object PasskeyAssertionPayload {

    /**
     * 构造完整断言响应 JSON（authData || clientDataHash 签名）。
     * 私钥仅经字节流解码，签名后由引擎单点清零；全程不产生不可变私钥 String。
     */
    fun build(
        passkeyData: PasskeyData,
        origin: String,
        challenge: String,
        clientDataPackage: String?,
        flags: Byte,
        signCount: Int,
        providedClientDataHash: ByteArray?,
        prfEval: WebAuthnRequest.PrfEval?
    ): String {
        // 派生中间量统一在 finally 中擦除（ISSUE-P1-02：签名会话材料用毕即清零）；
        // 私钥副本自 ISSUE-P3-212 起由消费式签名入口单点擦除，不再列于此处
        var authData: ByteArray? = null
        var clientDataBytes: ByteArray? = null
        var dataToSign: ByteArray? = null
        try {
            // 1. 构造 AuthenticatorData (flags 由本次用户验证结果与凭据 BE/BS 驱动，无 AT)
            val authDataLocal = PasskeyCryptoEngine.buildAuthenticatorData(
                rpId = passkeyData.relyingPartyId,
                flags = flags,
                signCount = signCount
            )
            authData = authDataLocal

            // 2. 构造 ClientDataJSON（响应体回传内容）与签名用摘要。
            //    ISSUE-P3-188 第 4 项 §174 路线②：组装走宿主可用的 [WebAuthnJsonWriter]
            //    （序列化语义与平台 org.json 一致，设备侧对拍用例锁定），使本函数可被宿主单测离线断言。
            val clientDataJson = WebAuthnJsonWriter.obj {
                str(WebAuthnJson.TYPE, WebAuthnJson.CLIENT_DATA_TYPE_GET)
                str(WebAuthnJson.CHALLENGE, challenge)
                str(WebAuthnJson.ORIGIN, origin)
                // ISSUE-P2-72：只写系统认证的调用方包名；取不到即省略——绝不再回退为本应用包名
                clientDataPackage?.let { str(WebAuthnJson.ANDROID_PACKAGE_NAME, it) }
                // ISSUE-P2-265：显式声明非跨源上下文（与注册侧、与 Monica 写法一致）。
                // 注意：本字段参与 clientDataJSON 的字节，签名与哈希由本函数**同时**产出，
                // 二者恒自洽；不影响既有凭据（每次断言都重新构造并重新签名）。
                bool(WebAuthnJson.CROSS_ORIGIN, false)
            }
            val clientDataBytesLocal = clientDataJson.toByteArray(Charsets.UTF_8)
            clientDataBytes = clientDataBytesLocal
            // 特权调用方自带 clientDataJSON：直接对其摘要签名；否则自建 JSON 取 SHA-256
            val clientDataHash = providedClientDataHash
                ?: MessageDigest.getInstance("SHA-256").digest(clientDataBytesLocal)

            // 3. 构造待签名数据包 (authData || clientDataHash)
            val dataToSignLocal = ByteArray(authDataLocal.size + clientDataHash.size)
            System.arraycopy(authDataLocal, 0, dataToSignLocal, 0, authDataLocal.size)
            System.arraycopy(clientDataHash, 0, dataToSignLocal, authDataLocal.size, clientDataHash.size)
            dataToSign = dataToSignLocal

            // 4. 读取私钥并执行签名，全流程保护敏感内存（PEM 优先，历史 v1 形态回退）。
            //    ISSUE-P3-212：签名后调用方缓冲的清零由加密引擎的消费式入口单点承担。
            val material = passkeyData.usePrivateKeyBytes { raw -> decodePrivateKeyMaterial(passkeyData, raw) }
            if (material.algorithmId != passkeyData.algorithmId) {
                // 私钥形态（PKCS#8 OID）是算法的权威来源；仓库字段仅是缓存，不一致时如实留痕
                AppLog.w(TAG, "凭据算法字段与私钥形态不一致，以私钥解析结果为准签发")
            }
            val signature = PasskeyCryptoEngine.signAssertionConsumingKey(
                material.algorithmId, material.keyBytes, dataToSignLocal
            )

            // 5. 构造最终 WebAuthn 断言响应 JSON（组装口径同上：WebAuthnJsonWriter）
            val b64Url = Base64.getUrlEncoder().withoutPadding()
            val assertionJson = WebAuthnJsonWriter.obj {
                str(WebAuthnJson.ID, passkeyData.credentialId)
                str(WebAuthnJson.RAW_ID, passkeyData.credentialId)
                str(WebAuthnJson.TYPE, WebAuthnJson.CREDENTIAL_TYPE_PUBLIC_KEY)
                str(WebAuthnJson.AUTHENTICATOR_ATTACHMENT, WebAuthnJson.ATTACHMENT_PLATFORM)
                obj(
                    WebAuthnJson.CLIENT_EXTENSION_RESULTS,
                    buildPrfClientExtensionResultsObj(prfEval, passkeyData.prfSecret)
                )
                obj(WebAuthnJson.RESPONSE, WebAuthnJsonWriter.Obj().apply {
                    str(WebAuthnJson.CLIENT_DATA_JSON, b64Url.encodeToString(clientDataBytesLocal))
                    str(WebAuthnJson.AUTHENTICATOR_DATA, b64Url.encodeToString(authDataLocal))
                    str(WebAuthnJson.SIGNATURE, b64Url.encodeToString(signature))
                    str(WebAuthnJson.USER_HANDLE, passkeyData.userHandle)
                })
            }
            return assertionJson
        } finally {
            authData?.fill(0)
            clientDataBytes?.fill(0)
            dataToSign?.fill(0)
        }
    }

    /**
     * 组装断言响应的 `clientExtensionResults.prf`（WebAuthn Level 3 §10.1）：
     * 请求携带 `eval` 且凭据持有 PRF 秘密时回传 `results.first` / `results.second`；
     * 否则返回**空对象**（绝不伪造输出，由 RP 自行判定）。
     */
    private fun buildPrfClientExtensionResultsObj(
        prfEval: WebAuthnRequest.PrfEval?,
        prfSecret: ProtectedString?
    ): WebAuthnJsonWriter.Obj {
        if (prfEval == null) return WebAuthnJsonWriter.Obj()
        if (prfSecret == null) {
            AppLog.w(TAG, "请求要求 PRF，但该凭据未持有 PRF 秘密，不返回 prf 结果")
            return WebAuthnJsonWriter.Obj()
        }
        val b64Url = Base64.getUrlEncoder().withoutPadding()
        return try {
            val first = PasskeyPrf.computeValue(prfSecret, prfEval.first)
            try {
                val results = WebAuthnJsonWriter.Obj().apply {
                    str(WebAuthnJson.FIRST, b64Url.encodeToString(first))
                }
                prfEval.second?.let { secondInput ->
                    val second = PasskeyPrf.computeValue(prfSecret, secondInput)
                    try {
                        results.str(WebAuthnJson.SECOND, b64Url.encodeToString(second))
                    } finally {
                        second.fill(0)
                    }
                }
                WebAuthnJsonWriter.Obj().apply {
                    obj(WebAuthnJson.PRF, WebAuthnJsonWriter.Obj().apply {
                        obj(WebAuthnJson.RESULTS, results)
                    })
                }
            } finally {
                first.fill(0)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "PRF 输出计算失败，不返回 prf 结果", t)
            WebAuthnJsonWriter.Obj()
        }
    }

    /** 签名侧私钥材料（算法标识 + 该算法的签名入参字节） */
    private class PrivateKeyMaterial(val algorithmId: Int, val keyBytes: ByteArray)

    /**
     * 从私钥受控字节流解码为签名引擎可用的原始私钥字节（ISSUE-P1-02 纯字节通道）。
     * 兼容驻留形态：
     * - **PKCS#8 PEM**（新写入口径，与 KeePassXC `KPEX_PASSKEY_PRIVATE_KEY_PEM` 一致）：由
     *   [PasskeyCryptoEngine.decodePemPrivateKeyText] 权威解析出算法与签名材料；
     * - 历史 v1：hex 标量（ES256）、Base64 种子 / PKCS#8 DER（Ed25519 / RS256），
     *   解码口径收敛在 [PasskeyCryptoEngine.decodeLegacyPrivateKeyBytes] 单点
     *   （`ISSUE-P3-213`，与写路径迁移共用同一实现）。
     * 输出为独立副本，其清零由消费式签名入口负责（`ISSUE-P3-212`）。
     */
    private fun decodePrivateKeyMaterial(passkeyData: PasskeyData, raw: ByteArray): PrivateKeyMaterial {
        PasskeyCryptoEngine.decodePemPrivateKeyText(raw)?.let {
            return PrivateKeyMaterial(it.algorithmId, it.keyBytes)
        }
        return PrivateKeyMaterial(passkeyData.algorithmId, PasskeyCryptoEngine.decodeLegacyPrivateKeyBytes(raw))
    }

    /** 与迁移前同名同值，保证日志归因不变 */
    private const val TAG = "PasskeyAssertionActivity"
}
