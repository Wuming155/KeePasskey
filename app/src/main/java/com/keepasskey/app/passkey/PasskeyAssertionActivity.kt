package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import com.keepasskey.crypto.passkey.PasskeyPrf
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Arrays
import java.util.Base64
import javax.inject.Inject

/**
 * 通行密钥断言 (Passkey Assertion / Get) 认证落地 Activity (对齐 P0-3 要求)。
 * 在独立受保护窗口中：
 * 1. 从密码库检索目标 Passkey 条目并二次校验「调用方 ⇄ 凭据」绑定；
 * 2. **ISSUE-P0-03 (ZT-03)**：签名前先执行「本次实际发生」的用户验证门控
 *    （RP 声明 `userVerification: "required"` 时强制系统级强验证，绝不降级为手动确认）；
 * 3. 组装 AuthenticatorData 二进制块（BE / BS 取**该凭据的持久化值**）；
 * 4. 构造 ClientDataJSON 并哈希（特权调用方下发 `clientDataHash` 时**直接对其签名**）；
 * 5. 调用 PasskeyCryptoEngine 签名 (私钥敏感内存即用即清)；
 * 6. 请求携带 `extensions.prf` 时按该凭据的 PRF 秘密回传 `clientExtensionResults.prf`；
 * 7. 组装并返回 PublicKeyCredential 响应，原子递增签名计数器防重放。
 */
@AndroidEntryPoint
class PasskeyAssertionActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var fillVerifier: CredentialFillVerifier

    /** ISSUE-P2-83：CM 通道调用方签名绑定存储（`android://` 维度签发前二次校验） */
    @Inject
    lateinit var callerTrustStore: CredentialManagerCallerTrustStore

    /** 防止验证回调 / 取消回调 / 重复 finish 交错产生多重签发或重复收尾 */
    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ISSUE-P3-188：`onCreate` 收敛为「解析调用方 → 解析请求 → 派发」三段编排，
        // 请求面解析下沉同包协作对象；判定顺序与 fail-closed 口径**逐字不变**
        val caller = PasskeyAssertionRequestParser.resolveAttestedCaller(intent, TAG)
        if (caller.rejected) {
            failAndFinish()
            return
        }
        val context = PasskeyAssertionRequestParser.buildAssertionContext(intent, TAG, caller)
        if (context == null) {
            failAndFinish()
            return
        }
        lifecycleScope.launch {
            try {
                performAssertion(context)
            } catch (t: Throwable) {
                AppLog.e(TAG, "Passkey 认证执行失败", t)
                failAndFinish()
            }
        }
    }

    /** 库状态 / 条目 / Passkey 字段核对，通过后进入 origin 绑定门控与用户验证 */
    private suspend fun performAssertion(context: PasskeyAssertionContext) {
        if (vaultRepository.isLocked()) {
            AppLog.w(TAG, "密码库处于锁定状态，无法执行 Passkey 认证")
            failAndFinish()
            return
        }

        val allEntries = vaultRepository.getKdbxEntries()
        val entry = allEntries.firstOrNull { it.id.toHexString() == context.entryId }
        if (entry == null) {
            // ISSUE-P1-10：日志不得携带 entryId 等敏感标识
            AppLog.e(TAG, "未找到目标条目")
            failAndFinish()
            return
        }

        val passkeyData = PasskeyData.fromCustomFields(entry.customFields)
        if (passkeyData == null) {
            AppLog.e(TAG, "条目不含有效的 Passkey 自定义字段")
            failAndFinish()
            return
        }

        // F4 整改：origin 缺失一律拒绝签发（fail-closed），不得以 RP ID 冒充 web origin
        if (context.origin.isBlank()) {
            AppLog.e(TAG, "缺少调用来源 origin，拒绝签发断言")
            failAndFinish()
            return
        }
        if (!passesOriginBinding(context, passkeyData, entry.url)) {
            failAndFinish()
            return
        }
        requestAssertionUserVerification(context, passkeyData)
    }

    /**
     * H1 整改：签名前二次校验 origin 与凭据 RP-ID / 绑定包名的关系。
     * 包名维度还须通过调用方**签名绑定**门控（ISSUE-P2-83）。
     */
    private fun passesOriginBinding(
        context: PasskeyAssertionContext,
        passkeyData: PasskeyData,
        entryUrl: String
    ): Boolean {
        if (CallingOriginResolver.isBrowserOrigin(context.origin)) {
            val originHost = DomainMatcher.extractDomain(context.origin)
            if (originHost.isEmpty() ||
                !DomainMatcher.isDomainMatch(passkeyData.relyingPartyId, originHost)
            ) {
                AppLog.e(TAG, "origin 与凭据 RP-ID 不匹配，拒绝签发断言")
                return false
            }
            return true
        }
        val boundPackage = DomainMatcher.extractAndroidBoundPackage(entryUrl)
        val packageDimensionAllowed = CredentialManagerPackageBindingGate.allowsPackageDimension(
            callingPackage = context.expectedPackage,
            certDigests = context.providerReq?.callingAppInfo
                ?.let { CallingOriginResolver.certDigests(it) }
                ?: CallerCertDigests.EMPTY,
            isTrusted = callerTrustStore::isTrusted,
            hasAnyBinding = callerTrustStore::hasAnyBindingFor
        )
        if (context.expectedPackage.isBlank() || !packageDimensionAllowed ||
            boundPackage != context.expectedPackage.trim().lowercase()
        ) {
            AppLog.e(TAG, "调用包名与凭据绑定包名不一致或签名未绑定，拒绝签发断言")
            return false
        }
        return true
    }

    /**
     * ISSUE-P0-03 (ZT-03) + 本次整改：进入签名前执行「本次实际发生」的用户验证门控。
     * RP 要求 `userVerification: required` 时强制强验证（不降级为手动确认）。
     */
    private fun requestAssertionUserVerification(
        context: PasskeyAssertionContext,
        passkeyData: PasskeyData
    ) {
        val rpLabel = passkeyData.relyingPartyId
        requestCredentialUserVerification(
            biometricAuthManager = biometricAuthManager,
            fillVerifier = fillVerifier,
            title = getString(R.string.cred_passkey_assert_title),
            biometricSubtitle = getString(R.string.passkey_assert_biometric_subtitle, rpLabel),
            manualHint = getString(R.string.passkey_assert_manual_hint, rpLabel),
            confirmText = getString(R.string.passkey_confirm_ok),
            cancelText = getString(R.string.passkey_confirm_cancel),
            requireBiometric = context.requireBiometric,
            onVerified = { verification ->
                if (settled) return@requestCredentialUserVerification
                settled = true
                signAndReturn(
                    entryId = context.entryId,
                    passkeyData = passkeyData,
                    origin = context.origin,
                    challenge = context.challenge,
                    clientDataPackage = context.clientDataPackage,
                    verification = verification,
                    providedClientDataHash = context.providedClientDataHash,
                    prfEval = context.prfEval
                )
            },
            onRejected = {
                if (settled) return@requestCredentialUserVerification
                settled = true
                AppLog.w(TAG, "用户验证未通过，拒绝签发 Passkey 断言")
                failAndFinish()
            }
        )
    }

    /**
     * 用户验证通过后执行签名与回传。
     *
     * [verification] 已被门控裁决为满足要求，此处将其实话实说地投影为 flags：
     * 强验证 → [PasskeyAuthFlags] 置 `UV=1`；仅手动确认 → 如实 `UV=0`。
     */
    private fun signAndReturn(
        entryId: String,
        passkeyData: PasskeyData,
        origin: String,
        challenge: String,
        clientDataPackage: String?,
        verification: CredentialUserVerification,
        providedClientDataHash: ByteArray?,
        prfEval: WebAuthnRequest.PrfEval?
    ) {
        lifecycleScope.launch {
            try {
                // fail-closed 兜底：凡不可签发的验证结果（理论不可达）一律拒绝产出断言
                val flags = PasskeyAuthFlags.forAssertion(
                    verification = verification,
                    backupEligible = passkeyData.backupEligible,
                    backupState = passkeyData.backupState
                )
                if (flags == null) {
                    AppLog.e(TAG, "用户验证结果不可签发断言: verification=$verification")
                    failAndFinish()
                    return@launch
                }

                // ISSUE-P3-27 子项 2：先把签名计数器**原子递增并落库**，再据其返回值签名。
                val signCount = vaultRepository.incrementPasskeySignCount(entryId)
                if (signCount == null) {
                    AppLog.e(TAG, "签名计数器未能原子递增并落库，拒绝签发断言")
                    failAndFinish()
                    return@launch
                }

                // 密码学运算调度至 Default，杜绝在系统回调线程上执行 CPU 密集签名
                val assertionJson = withContext(Dispatchers.Default) {
                    buildAssertionJson(
                        passkeyData = passkeyData,
                        origin = origin,
                        challenge = challenge,
                        clientDataPackage = clientDataPackage,
                        flags = flags,
                        signCount = signCount,
                        providedClientDataHash = providedClientDataHash,
                        prfEval = prfEval
                    )
                }

                // ISSUE-P3-27 子项 2：计数器已落库后才回传 RP
                val resultIntent = Intent()
                val response = GetCredentialResponse(PublicKeyCredential(assertionJson))
                PendingIntentHandler.setGetCredentialResponse(resultIntent, response)
                setResult(RESULT_OK, resultIntent)

                finish()
            } catch (t: Throwable) {
                AppLog.e(TAG, "Passkey 认证执行失败", t)
                failAndFinish()
            }
        }
    }

    /**
     * 在 [Dispatchers.Default] 上构造完整断言响应 JSON（authData || clientDataHash 签名）。
     * 私钥仅经字节流解码、签名后立即清零，全程不产生不可变私钥 String。
     */
    private fun buildAssertionJson(
        passkeyData: PasskeyData,
        origin: String,
        challenge: String,
        clientDataPackage: String?,
        flags: Byte,
        signCount: Int,
        providedClientDataHash: ByteArray?,
        prfEval: WebAuthnRequest.PrfEval?
    ): String {
        // 派生中间量统一在 finally 中擦除（ISSUE-P1-02：签名会话材料用毕即清零）
        var authData: ByteArray? = null
        var clientDataBytes: ByteArray? = null
        var dataToSign: ByteArray? = null
        var signingKeyBytes: ByteArray? = null
        try {
            // 1. 构造 AuthenticatorData (flags 由本次用户验证结果与凭据 BE/BS 驱动，无 AT)
            val authDataLocal = PasskeyCryptoEngine.buildAuthenticatorData(
                rpId = passkeyData.relyingPartyId,
                flags = flags,
                signCount = signCount
            )
            authData = authDataLocal

            // 2. 构造 ClientDataJSON（响应体回传内容）与签名用摘要
            val clientDataJson = JSONObject().apply {
                put(WebAuthnJson.TYPE, WebAuthnJson.CLIENT_DATA_TYPE_GET)
                put(WebAuthnJson.CHALLENGE, challenge)
                put(WebAuthnJson.ORIGIN, origin)
                // ISSUE-P2-72：只写系统认证的调用方包名；取不到即省略——绝不再回退为本应用包名
                clientDataPackage?.let { put(WebAuthnJson.ANDROID_PACKAGE_NAME, it) }
            }.toString()
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

            // 4. 读取私钥并执行签名，全流程保护敏感内存（PEM 优先，历史 v1 形态回退）
            val material = passkeyData.usePrivateKeyBytes { raw -> decodePrivateKeyMaterial(passkeyData, raw) }
            signingKeyBytes = material.keyBytes
            if (material.algorithmId != passkeyData.algorithmId) {
                // 私钥形态（PKCS#8 OID）是算法的权威来源；仓库字段仅是缓存，不一致时如实留痕
                AppLog.w(TAG, "凭据算法字段与私钥形态不一致，以私钥解析结果为准签发")
            }
            val signature = PasskeyCryptoEngine.signAssertion(
                material.algorithmId, material.keyBytes, dataToSignLocal
            )

            // 5. 构造最终 WebAuthn 断言响应 JSON
            val b64Url = Base64.getUrlEncoder().withoutPadding()
            val assertionJson = JSONObject().apply {
                put(WebAuthnJson.ID, passkeyData.credentialId)
                put(WebAuthnJson.RAW_ID, passkeyData.credentialId)
                put(WebAuthnJson.TYPE, WebAuthnJson.CREDENTIAL_TYPE_PUBLIC_KEY)
                put(WebAuthnJson.AUTHENTICATOR_ATTACHMENT, WebAuthnJson.ATTACHMENT_PLATFORM)
                put(WebAuthnJson.CLIENT_EXTENSION_RESULTS, buildPrfClientExtensionResults(prfEval, passkeyData.prfSecret))
                put(WebAuthnJson.RESPONSE, JSONObject().apply {
                    put(WebAuthnJson.CLIENT_DATA_JSON, b64Url.encodeToString(clientDataBytesLocal))
                    put(WebAuthnJson.AUTHENTICATOR_DATA, b64Url.encodeToString(authDataLocal))
                    put(WebAuthnJson.SIGNATURE, b64Url.encodeToString(signature))
                    put(WebAuthnJson.USER_HANDLE, passkeyData.userHandle)
                })
            }
            return assertionJson.toString()
        } finally {
            authData?.fill(0)
            clientDataBytes?.fill(0)
            dataToSign?.fill(0)
            signingKeyBytes?.fill(0)
        }
    }

    /**
     * 组装断言响应的 `clientExtensionResults.prf`（WebAuthn Level 3 §10.1）：
     * 请求携带 `eval` 且凭据持有 PRF 秘密时回传 `results.first` / `results.second`；
     * 否则返回空对象（绝不伪造输出，由 RP 自行判定）。
     */
    private fun buildPrfClientExtensionResults(
        prfEval: WebAuthnRequest.PrfEval?,
        prfSecret: ProtectedString?
    ): JSONObject {
        if (prfEval == null) return JSONObject()
        if (prfSecret == null) {
            AppLog.w(TAG, "请求要求 PRF，但该凭据未持有 PRF 秘密，不返回 prf 结果")
            return JSONObject()
        }
        val b64Url = Base64.getUrlEncoder().withoutPadding()
        return try {
            val first = PasskeyPrf.computeValue(prfSecret, prfEval.first)
            try {
                val results = JSONObject().put(WebAuthnJson.FIRST, b64Url.encodeToString(first))
                prfEval.second?.let { secondInput ->
                    val second = PasskeyPrf.computeValue(prfSecret, secondInput)
                    try {
                        results.put(WebAuthnJson.SECOND, b64Url.encodeToString(second))
                    } finally {
                        second.fill(0)
                    }
                }
                JSONObject().put(WebAuthnJson.PRF, JSONObject().put(WebAuthnJson.RESULTS, results))
            } finally {
                first.fill(0)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "PRF 输出计算失败，不返回 prf 结果", t)
            JSONObject()
        }
    }

    /** 签名侧私钥材料（算法标识 + 该算法的签名入参字节） */
    private class PrivateKeyMaterial(val algorithmId: Int, val keyBytes: ByteArray)

    /**
     * 从私钥受控字节流解码为签名引擎可用的原始私钥字节（ISSUE-P1-02 纯字节通道）。
     * 兼容驻留形态：
     * - **PKCS#8 PEM**（新写入口径，与 KeePassXC `KPEX_PASSKEY_PRIVATE_KEY_PEM` 一致）：由
     *   [PasskeyCryptoEngine.decodePemPrivateKeyText] 权威解析出算法与签名材料；
     * - 历史 v1：定长（≤64 字符）hex 文本（ES256）、Base64（Ed25519 种子 / RS256 PKCS#8 DER）。
     * 输出为独立副本，调用方负责用毕清零。
     */
    private fun decodePrivateKeyMaterial(passkeyData: PasskeyData, raw: ByteArray): PrivateKeyMaterial {
        PasskeyCryptoEngine.decodePemPrivateKeyText(raw)?.let {
            return PrivateKeyMaterial(it.algorithmId, it.keyBytes)
        }
        return PrivateKeyMaterial(passkeyData.algorithmId, decodeLegacyPrivateKeyBytes(raw))
    }

    /** 历史 v1 私钥文本（hex 标量 / Base64 种子 / Base64 PKCS#8 DER）→ 原始字节 */
    private fun decodeLegacyPrivateKeyBytes(raw: ByteArray): ByteArray {
        val isHex = raw.size in 2..64 && raw.all { b ->
            b.toInt() in '0'.code..'9'.code ||
                    b.toInt() in 'a'.code..'f'.code ||
                    b.toInt() in 'A'.code..'F'.code
        }
        if (isHex) {
            // 奇数长度左对齐补零半字节（等价 BigInteger(String,16) 的无符号解析语义）
            val out = ByteArray((raw.size + 1) / 2)
            val offset = out.size * 2 - raw.size
            for (i in out.indices) {
                val hiIndex = 2 * i - offset
                val hi = if (hiIndex >= 0) hexNibble(raw[hiIndex]) else 0
                out[i] = ((hi shl 4) or hexNibble(raw[hiIndex + 1])).toByte()
            }
            return out
        }
        // 先剔除两侧 ASCII 空白再 Base64 解码（与历史 trim() 语义一致，但不物化 String）
        var start = 0
        var end = raw.size
        while (start < end && raw[start].toInt() <= ASCII_SPACE) start++
        while (end > start && raw[end - 1].toInt() <= ASCII_SPACE) end--
        val slice = raw.copyOfRange(start, end)
        try {
            return Base64.getDecoder().decode(slice)
        } finally {
            Arrays.fill(slice, 0.toByte())
        }
    }

    /** 单个 hex ASCII 字符 → 半字节值（非法字符 fail-closed 抛出） */
    private fun hexNibble(b: Byte): Int = when (b.toInt()) {
        in '0'.code..'9'.code -> b.toInt() - '0'.code
        in 'a'.code..'f'.code -> b.toInt() - 'a'.code + 10
        in 'A'.code..'F'.code -> b.toInt() - 'A'.code + 10
        else -> throw IllegalArgumentException("非法 hex 字符: ${b.toInt()}")
    }

    companion object {
        private const val TAG = "PasskeyAssertionActivity"
        const val EXTRA_ENTRY_ID = "com.keepasskey.extra.ENTRY_ID"
        const val EXTRA_REQUEST_JSON = "com.keepasskey.extra.REQUEST_JSON"
        const val EXTRA_CHALLENGE = "com.keepasskey.extra.CHALLENGE"
        const val EXTRA_ORIGIN = "com.keepasskey.extra.ORIGIN"
        const val EXTRA_EXPECTED_PACKAGE = "com.keepasskey.extra.EXPECTED_PACKAGE"
    }
}
