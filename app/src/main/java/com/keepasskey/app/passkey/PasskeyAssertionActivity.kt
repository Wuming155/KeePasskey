package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
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

        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty()
        val challenge = intent.getStringExtra(EXTRA_CHALLENGE).orEmpty()
        val origin = intent.getStringExtra(EXTRA_ORIGIN).orEmpty()
        val expectedPackage = intent.getStringExtra(EXTRA_EXPECTED_PACKAGE).orEmpty()

        // ISSUE-P2-72：归属字段取**系统认证**的 CallingAppInfo 包名——系统把原始
        // BeginGetCredentialRequest 注入本窗口的 PendingIntent，其中 `callingAppInfo.packageName`
        // 由平台背书（凭据组装阶段亦用它做严格匹配）。原实现用 `Activity.getCallingPackage()`，
        // 在 PendingIntent 拉起场景为 `"android"`/null，随后 `?: packageName` 又回退成
        // **本应用包名**——等于向 RP 谎报调用方。
        val providerReq: ProviderGetCredentialRequest? = try {
            PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        } catch (_: Exception) {
            null
        }
        val attestedPackage = CallingOriginResolver.systemAttestedPackageName(providerReq?.callingAppInfo)

        // 交叉核对：系统认证包名 vs 本应用在**候选组装阶段**写入 base Intent 的预期包名。
        // 两者均可得且不一致 → fail-closed（组装阶段与本窗口看到的调用方不是同一个，拒绝签发）。
        if (attestedPackage != null &&
            expectedPackage.isNotBlank() &&
            attestedPackage != expectedPackage.trim()
        ) {
            AppLog.e(TAG, "系统认证调用方与预期包名不一致，拒绝签发断言")
            failAndFinish()
            return
        }
        val clientDataPackage = CallingOriginResolver.clientDataAndroidPackageName(
            attestedPackage,
            expectedPackage
        )

        if (entryId.isBlank()) {
            AppLog.e(TAG, "缺少通行密钥 entryId")
            failAndFinish()
            return
        }

        // 系统下发的断言请求（权威来源）：请求 JSON 与**特权调用方自带的 clientDataJSON 摘要**
        val option = providerReq?.credentialOptions
            ?.firstOrNull { it is GetPublicKeyCredentialOption } as? GetPublicKeyCredentialOption
        val requestJson = option?.requestJson
            ?: intent.getStringExtra(EXTRA_REQUEST_JSON).orEmpty()
        // 特权调用方（自带 clientDataJSON 的浏览器）下发摘要时必须直接对其签名——自建 JSON
        // 的哈希与其预期不一致会让 RP 侧验签失败（KeePassDX 同口径）
        val providedClientDataHash = option?.clientDataHash?.takeIf { it.isNotEmpty() }
        val request = WebAuthnRequest.parse(requestJson)
        val prfEval = request?.prfEval
        val requireBiometric = request?.userVerification == WebAuthnRequest.UserVerification.REQUIRED

        lifecycleScope.launch {
            try {
                if (vaultRepository.isLocked()) {
                    AppLog.w(TAG, "密码库处于锁定状态，无法执行 Passkey 认证")
                    failAndFinish()
                    return@launch
                }

                val allEntries = vaultRepository.getKdbxEntries()
                val entry = allEntries.firstOrNull { it.id.toHexString() == entryId }
                if (entry == null) {
                    // ISSUE-P1-10：日志不得携带 entryId 等敏感标识
                    AppLog.e(TAG, "未找到目标条目")
                    failAndFinish()
                    return@launch
                }

                val passkeyData = PasskeyData.fromCustomFields(entry.customFields)
                if (passkeyData == null) {
                    AppLog.e(TAG, "条目不含有效的 Passkey 自定义字段")
                    failAndFinish()
                    return@launch
                }

                // F4 整改：origin 缺失一律拒绝签发（fail-closed），不得以 RP ID 冒充 web origin
                if (origin.isBlank()) {
                    AppLog.e(TAG, "缺少调用来源 origin，拒绝签发断言")
                    failAndFinish()
                    return@launch
                }

                // H1 整改：签名前二次校验 origin 与凭据 RP-ID 的绑定关系。
                if (CallingOriginResolver.isBrowserOrigin(origin)) {
                    val originHost = DomainMatcher.extractDomain(origin)
                    if (originHost.isEmpty() ||
                        !DomainMatcher.isDomainMatch(passkeyData.relyingPartyId, originHost)
                    ) {
                        AppLog.e(TAG, "origin 与凭据 RP-ID 不匹配，拒绝签发断言")
                        failAndFinish()
                        return@launch
                    }
                } else {
                    val boundPackage = DomainMatcher.extractAndroidBoundPackage(entry.url)
                    // ISSUE-P2-83：包名维度还须通过调用方**签名绑定**门控
                    val packageDimensionAllowed = CredentialManagerPackageBindingGate.allowsPackageDimension(
                        callingPackage = expectedPackage,
                        certDigests = providerReq?.callingAppInfo
                            ?.let { CallingOriginResolver.certDigests(it) }
                            ?: CallerCertDigests.EMPTY,
                        isTrusted = callerTrustStore::isTrusted,
                        hasAnyBinding = callerTrustStore::hasAnyBindingFor
                    )
                    if (expectedPackage.isBlank() || !packageDimensionAllowed ||
                        boundPackage != expectedPackage.trim().lowercase()
                    ) {
                        AppLog.e(TAG, "调用包名与凭据绑定包名不一致或签名未绑定，拒绝签发断言")
                        failAndFinish()
                        return@launch
                    }
                }

                // ISSUE-P0-03 (ZT-03) + 本次整改：进入签名前执行「本次实际发生」的用户验证门控。
                // RP 要求 `userVerification: required` 时强制强验证（不降级为手动确认）。
                val rpLabel = passkeyData.relyingPartyId
                requestCredentialUserVerification(
                    biometricAuthManager = biometricAuthManager,
                    fillVerifier = fillVerifier,
                    title = getString(R.string.cred_passkey_assert_title),
                    biometricSubtitle = getString(R.string.passkey_assert_biometric_subtitle, rpLabel),
                    manualHint = getString(R.string.passkey_assert_manual_hint, rpLabel),
                    confirmText = getString(R.string.passkey_confirm_ok),
                    cancelText = getString(R.string.passkey_confirm_cancel),
                    requireBiometric = requireBiometric,
                    onVerified = { verification ->
                        if (settled) return@requestCredentialUserVerification
                        settled = true
                        signAndReturn(
                            entryId = entryId,
                            passkeyData = passkeyData,
                            origin = origin,
                            challenge = challenge,
                            clientDataPackage = clientDataPackage,
                            verification = verification,
                            providedClientDataHash = providedClientDataHash,
                            prfEval = prfEval
                        )
                    },
                    onRejected = {
                        if (settled) return@requestCredentialUserVerification
                        settled = true
                        AppLog.w(TAG, "用户验证未通过，拒绝签发 Passkey 断言")
                        failAndFinish()
                    }
                )
            } catch (t: Throwable) {
                AppLog.e(TAG, "Passkey 认证执行失败", t)
                failAndFinish()
            }
        }
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
                put("type", "webauthn.get")
                put("challenge", challenge)
                put("origin", origin)
                // ISSUE-P2-72：只写系统认证的调用方包名；取不到即省略——绝不再回退为本应用包名
                clientDataPackage?.let { put("androidPackageName", it) }
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
                put("id", passkeyData.credentialId)
                put("rawId", passkeyData.credentialId)
                put("type", "public-key")
                put("authenticatorAttachment", "platform")
                put("clientExtensionResults", buildPrfClientExtensionResults(prfEval, passkeyData.prfSecret))
                put("response", JSONObject().apply {
                    put("clientDataJSON", b64Url.encodeToString(clientDataBytesLocal))
                    put("authenticatorData", b64Url.encodeToString(authDataLocal))
                    put("signature", b64Url.encodeToString(signature))
                    put("userHandle", passkeyData.userHandle)
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
                val results = JSONObject().put("first", b64Url.encodeToString(first))
                prfEval.second?.let { secondInput ->
                    val second = PasskeyPrf.computeValue(prfSecret, secondInput)
                    try {
                        results.put("second", b64Url.encodeToString(second))
                    } finally {
                        second.fill(0)
                    }
                }
                JSONObject().put("prf", JSONObject().put("results", results))
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
        while (start < end && raw[start].toInt() <= 0x20) start++
        while (end > start && raw[end - 1].toInt() <= 0x20) end--
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
