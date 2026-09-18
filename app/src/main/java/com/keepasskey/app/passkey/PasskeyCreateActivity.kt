package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.cbor.CborEncoder
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import com.keepasskey.crypto.passkey.PasskeyPrf
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.inject.Inject

/**
 * 通行密钥创建与注册落地 Activity (对齐 P0-3 要求)。
 * 在受保护的独立生命周期中完成：
 * 1. 确定调用方来源并校验（H1 / L1）；
 * 2. 库锁定时在**同一受保护窗口内**呈现解锁页（[CredentialUnlockPresenter]）——
 *    整改前此处直接 `failAndFinish()`，用户点选系统「创建通行密钥」后静默失败；
 * 3. **ISSUE-P0-03 (ZT-03)**：生成密钥并落库前，先执行「本次实际发生」的用户验证门控
 *    （RP 声明 `userVerification: required` 时强制系统级强验证，绝不降级为手动确认）；
 * 4. **按 RP 的 `pubKeyCredParams` 协商算法**生成密钥对（此前写死 ES256）；
 * 5. **原样采用 RP 下发的 `user.id` 作为 userHandle**（此前自造随机值，破坏无用户名登录）；
 * 6. `excludeCredentials` 命中库内既有凭据即 fail-closed 拒绝（防重复注册）；
 * 7. 请求携带 `extensions.prf` 时生成并持久化 PRF 秘密，并在响应中回传 `prf` 结果；
 * 8. 写入 KDBX 密码库并原子落盘（同 rpId + 用户名已有条目则**原地替换**，不产生重复条目）；
 * 9. 构造标准 WebAuthn W3C 证明对象 (AttestationObject) 与确定性 CBOR 编码；
 * 10. 通过 PendingIntentHandler 回传 CreatePublicKeyCredentialResponse。
 */
@AndroidEntryPoint
class PasskeyCreateActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var fillVerifier: CredentialFillVerifier

    @Inject
    lateinit var unlockPresenter: CredentialUnlockPresenter

    /** ISSUE-P2-02：DAL 远程资产声明校验器（普通应用注册的 RP ID 强绑定门控） */
    @Inject
    lateinit var dalVerifier: DigitalAssetLinksVerifier

    /** ISSUE-P2-02：读取「跳过 DAL 校验」显式用户授权开关 */
    @Inject
    lateinit var extendedSettingsStore: com.keepasskey.app.data.repository.ExtendedSettingsStore

    /** ISSUE-P2-83：CM 通道调用方「包名 + 主签名摘要」首次绑定存储（`android://` 维度放行依据） */
    @Inject
    lateinit var callerTrustStore: CredentialManagerCallerTrustStore

    /** 防止验证回调 / 取消回调 / 重复 finish 交错产生重复创建或重复收尾 */
    private var settled = false

    /** 系统注入的创建请求（解锁后仍需使用其中经平台背书的调用方信息与请求 JSON） */
    private var providerReq: ProviderCreateCredentialRequest? = null

    /** **平台下发**的创建请求选项（`pubKeyCredParams` / `user.id` / `excludeCredentials` / `prf`） */
    private var request: WebAuthnRequest? = null

    private var rpId = ""
    private var userName = ""
    private var userDisplayName = ""
    private var challenge = ""
    private var origin = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rpId = intent.getStringExtra(EXTRA_RP_ID).orEmpty()
        userName = intent.getStringExtra(EXTRA_USER_NAME).orEmpty()
        userDisplayName = intent.getStringExtra(EXTRA_USER_DISPLAY_NAME).orEmpty()
        challenge = intent.getStringExtra(EXTRA_CHALLENGE).orEmpty()
        origin = intent.getStringExtra(EXTRA_ORIGIN).orEmpty()

        providerReq = try {
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        } catch (_: Exception) {
            null
        }
        // 系统请求 JSON 是**平台下发**的权威来源，优先于本应用写入 base Intent 的副本
        val callingReq = providerReq?.callingRequest
        if (callingReq is CreatePublicKeyCredentialRequest) {
            request = WebAuthnRequest.parse(callingReq.requestJson)
            if (rpId.isBlank()) rpId = request?.rpId.orEmpty()
            if (userName.isBlank()) userName = request?.userName.orEmpty()
            if (userDisplayName.isBlank()) userDisplayName = request?.userDisplayName.orEmpty()
            if (challenge.isBlank()) challenge = request?.challenge.orEmpty()
        }
        // H1 整改：不再从 candidateQueryData 读取调用方可控 origin（不可信）。
        // origin 缺省留空，clientDataJSON 回退为 https://<rpId> 标准值。

        if (rpId.isBlank() || userName.isBlank()) {
            // ISSUE-P1-10：日志不得携带 rpId / userName 等敏感标识
            AppLog.e(TAG, "缺少必要注册参数（rpId 或 userName 为空）")
            failAndFinish()
            return
        }

        // 锁库时在受保护窗口内先解锁，解锁成功后从同一入口继续注册流程
        unlockPresenter.requireUnlocked(this) { startCreation() }
    }

    /** 解锁后（或库本就解锁）的注册主流程 */
    private fun startCreation() {
        lifecycleScope.launch {
            try {
                if (vaultRepository.isLocked()) {
                    AppLog.w(TAG, "密码库仍未解锁，无法注册新 Passkey")
                    failAndFinish()
                    return@launch
                }

                // 普通应用（apk-key-hash origin）创建的凭据额外记录调用包绑定（android://<包名>）。
                // ISSUE-P2-72：仅接受**系统背书**的 CallingAppInfo 包名，取不到即返回 null。
                val callerPackage = CallingOriginResolver.systemAttestedPackageName(providerReq?.callingAppInfo)

                // ISSUE-P2-02：普通应用注册的 DAL 远程资产声明强绑定校验。
                // 浏览器委派调用豁免（rp.id ↔ web origin 归属已由 DomainMatcher 严格点号边界强制）。
                if (!CallingOriginResolver.isBrowserOrigin(origin)) {
                    val pkg = callerPackage ?: run {
                        AppLog.e(TAG, "无法确定调用应用包名，拒绝创建应用内 Passkey")
                        failAndFinish()
                        return@launch
                    }
                    val skipDal = extendedSettingsStore.load().skipDalVerification
                    if (skipDal) {
                        AppLog.w(TAG, "用户已显式开启「跳过 DAL 校验」，本次注册不执行远程声明验证")
                    } else {
                        val callingAppInfo = providerReq?.callingAppInfo
                        // ISSUE-P3-93：以调用方**全部**签名摘要参与 DAL 校验（签名轮换期任一命中即通过）
                        val certDigests = callingAppInfo?.let { CallingOriginResolver.certDigests(it) }
                            ?: CallerCertDigests.EMPTY
                        if (callingAppInfo == null || certDigests.isEmpty) {
                            AppLog.e(TAG, "无法获取调用方签名证书，DAL 校验 fail-closed，拒绝创建")
                            failAndFinish()
                            return@launch
                        }
                        when (dalVerifier.verify(rpId, pkg, certDigests)) {
                            DigitalAssetLinksVerifier.DalResult.VERIFIED -> Unit
                            DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED -> {
                                AppLog.w(TAG, "DAL 声明校验未通过（无匹配授权声明或格式错误），拒绝创建")
                                failAndFinish()
                                return@launch
                            }
                            DigitalAssetLinksVerifier.DalResult.NETWORK_UNAVAILABLE -> {
                                AppLog.w(TAG, "DAL 校验网络不可用，fail-closed 拒绝创建")
                                failAndFinish()
                                return@launch
                            }
                        }
                    }
                }

                // ISSUE：`excludeCredentials` 查重（WebAuthn 规范要求认证器拒绝创建已排除的凭据）
                if (!ensureNotExcluded()) {
                    failAndFinish()
                    return@launch
                }

                // RP 要求 `userVerification: "required"` 时强制系统级强验证（不得降级为手动确认）
                val requireBiometric = request?.authenticatorSelectionUserVerification ==
                    WebAuthnRequest.UserVerification.REQUIRED

                val rpLabel = rpId.ifBlank { userName }
                requestCredentialUserVerification(
                    biometricAuthManager = biometricAuthManager,
                    fillVerifier = fillVerifier,
                    title = getString(R.string.cred_passkey_create_title),
                    biometricSubtitle = getString(R.string.passkey_create_biometric_subtitle, rpLabel),
                    manualHint = getString(R.string.passkey_create_manual_hint, rpLabel),
                    confirmText = getString(R.string.passkey_confirm_ok),
                    cancelText = getString(R.string.passkey_confirm_cancel),
                    requireBiometric = requireBiometric,
                    onVerified = { verification ->
                        if (settled) return@requestCredentialUserVerification
                        settled = true
                        // ISSUE-P2-83：注册流程是「用户在受保护窗口内把凭据显式交给该调用方」的
                        // 两个入口之一（另一个是保存），故在验证通过后、落库前写入 CM 通道绑定。
                        // fail-closed：包名不可得或摘要不可读一律**不写入**（保持未绑定）。
                        val attestedPkg = callerPackage
                        val callerDigests = providerReq?.callingAppInfo
                            ?.let { CallingOriginResolver.certDigests(it) }
                            ?: CallerCertDigests.EMPTY
                        if (attestedPkg.isNullOrBlank() || callerDigests.isEmpty) {
                            AppLog.w(TAG, "调用方包名或签名摘要不可读，CM 通道保持未绑定（fail-closed）")
                        } else {
                            callerTrustStore.trust(attestedPkg, callerDigests.primary)
                        }
                        createAndReturn(callerPackage = callerPackage, verification = verification)
                    },
                    onRejected = {
                        if (settled) return@requestCredentialUserVerification
                        settled = true
                        AppLog.w(TAG, "用户验证未通过，拒绝创建 Passkey")
                        failAndFinish()
                    }
                )
            } catch (t: Throwable) {
                AppLog.e(TAG, "Passkey 注册异常", t)
                failAndFinish()
            }
        }
    }

    /**
     * `excludeCredentials` 查重：请求排除的凭据 id 若已存在于库中，说明该 RP + 该凭据已注册过，
     * 按 WebAuthn 规范 fail-closed 拒绝创建（避免同一凭据被重复登记为多条条目）。
     */
    private suspend fun ensureNotExcluded(): Boolean {
        val excludedIds = request?.excludeCredentialIds ?: return true
        if (excludedIds.isEmpty()) return true
        val existing = vaultRepository.findExistingPasskeyCredentialIds(excludedIds)
        if (existing.isNotEmpty()) {
            // ISSUE-P1-10：日志不得携带 credentialId 等敏感标识
            AppLog.w(TAG, "命中 excludeCredentials：库中已存在该凭据，拒绝重复创建")
            return false
        }
        return true
    }

    /**
     * 用户验证通过后执行密钥生成、落库与响应回传（唯一允许 `RESULT_OK` 的路径）。
     *
     * [verification] 已被门控裁决为满足要求，此处将其实话实说地投影为 flags：
     * 强验证 → [PasskeyAuthFlags] 置 `UV=1`；仅手动确认 → 如实 `UV=0`。
     */
    private fun createAndReturn(
        callerPackage: String?,
        verification: CredentialUserVerification
    ) {
        lifecycleScope.launch {
            try {
                // fail-closed 兜底：凡不可签发的验证结果（理论不可达）一律拒绝创建
                val flags = PasskeyAuthFlags.forRegistration(verification)
                if (flags == null) {
                    AppLog.e(TAG, "用户验证结果不可用于注册: verification=$verification")
                    failAndFinish()
                    return@launch
                }

                val requestedAlgorithms = request?.pubKeyCredParams ?: emptyList()
                // 原样采用 RP 下发的 user.id（Base64URL 文本）；缺失时才由引擎生成随机句柄
                val rpUserId = request?.userId.orEmpty()

                // PRF 扩展：注册请求携带 `extensions.prf` 时生成每凭据秘密；规范禁止注册携带
                // `evalByCredential`（出现即 fail-closed，KeePassDX 同口径）
                val prfEval = request?.prfEval
                if (prfEval?.evalByCredentialPresent == true) {
                    AppLog.w(TAG, "注册请求携带 evalByCredential（规范禁止），拒绝创建")
                    failAndFinish()
                    return@launch
                }
                val prfSecret: ProtectedString? = if (prfEval != null) PasskeyPrf.newSecretProtected() else null

                // 1. 按 pubKeyCredParams 协商算法并生成密钥对（CPU 密集调度至 Default）
                val generated = withContext(Dispatchers.Default) {
                    PasskeyCryptoEngine.generateKeyPairForAlgorithms(
                        requestedAlgorithms = requestedAlgorithms,
                        relyingPartyId = rpId,
                        userName = userName,
                        userHandle = rpUserId,
                        userDisplayName = userDisplayName
                    )
                }.let { if (prfSecret == null) it else it.copy(prfSecret = prfSecret) }

                // 2. 存储至 KDBX 密码库（原子落盘）；同 rpId + 用户名已有条目则原地替换
                vaultRepository.saveOrReplacePasskeyEntry(
                    data = generated,
                    boundPackage = if (CallingOriginResolver.isBrowserOrigin(origin)) null else callerPackage
                )

                // 3. 构建证明数据 (Attestation) 并回传（确定性 CBOR 编码在 Default 执行）
                val regResponseJson = withContext(Dispatchers.Default) {
                    buildRegistrationJson(
                        passkeyData = generated,
                        challenge = challenge,
                        callerPackage = callerPackage,
                        flags = flags,
                        prfEval = prfEval
                    )
                }

                val resultIntent = Intent()
                val response = CreatePublicKeyCredentialResponse(regResponseJson)
                PendingIntentHandler.setCreateCredentialResponse(resultIntent, response)
                setResult(RESULT_OK, resultIntent)
                finish()
            } catch (t: Throwable) {
                AppLog.e(TAG, "Passkey 注册异常", t)
                failAndFinish()
            }
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
     */
    private fun buildRegistrationJson(
        passkeyData: PasskeyData,
        challenge: String,
        callerPackage: String?,
        flags: Byte,
        prfEval: WebAuthnRequest.PrfEval?
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
                "fmt" to "none",
                "attStmt" to emptyMap<String, Any>(),
                "authData" to authData
            )
            attestationObjectBytes = CborEncoder.encodeMap(attestationMap)

            val clientDataJson = JSONObject().apply {
                put("type", "webauthn.create")
                put("challenge", challenge)
                put("origin", origin.ifBlank { "https://$rpId" })
                // ISSUE-P2-72：归属字段只写**系统背书**的调用方包名；取不到即省略该字段——
                // 绝不回退为本应用包名（那会把 RP 收到的归属伪造成我们）。
                CallingOriginResolver.clientDataAndroidPackageName(callerPackage)?.let {
                    put("androidPackageName", it)
                }
            }.toString()

            val b64Url = Base64.getUrlEncoder().withoutPadding()
            val clientDataBase64 = b64Url.encodeToString(clientDataJson.toByteArray(Charsets.UTF_8))
            val attestationBase64 = b64Url.encodeToString(attestationObjectBytes)

            return JSONObject().apply {
                put("id", passkeyData.credentialId)
                put("rawId", passkeyData.credentialId)
                put("type", "public-key")
                put("authenticatorAttachment", "platform")
                put(
                    "clientExtensionResults",
                    buildPrfClientExtensionResults(prfEval, passkeyData.prfSecret, isRegistration = true)
                )
                put("response", JSONObject().apply {
                    put("clientDataJSON", clientDataBase64)
                    put("attestationObject", attestationBase64)
                    put("transports", JSONArray().put("internal"))
                })
            }.toString()
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
    private fun buildPrfClientExtensionResults(
        prfEval: WebAuthnRequest.PrfEval?,
        prfSecret: ProtectedString?,
        isRegistration: Boolean
    ): JSONObject {
        if (prfEval == null) return JSONObject()
        val b64Url = Base64.getUrlEncoder().withoutPadding()
        val prf = JSONObject()
        if (prfSecret == null) {
            if (!isRegistration) return JSONObject()
            prf.put("enabled", true)
            return JSONObject().put("prf", prf)
        }
        prf.put("enabled", true)
        val first = PasskeyPrf.computeValue(prfSecret, prfEval.first)
        try {
            val results = JSONObject().put("first", b64Url.encodeToString(first))
            val secondInput = prfEval.second
            if (secondInput != null) {
                val second = PasskeyPrf.computeValue(prfSecret, secondInput)
                try {
                    results.put("second", b64Url.encodeToString(second))
                } finally {
                    second.fill(0)
                }
            }
            prf.put("results", results)
        } finally {
            first.fill(0)
        }
        return JSONObject().put("prf", prf)
    }

    companion object {
        private const val TAG = "PasskeyCreateActivity"
        const val EXTRA_RP_ID = "com.keepasskey.extra.RP_ID"
        const val EXTRA_USER_NAME = "com.keepasskey.extra.USER_NAME"
        const val EXTRA_USER_DISPLAY_NAME = "com.keepasskey.extra.USER_DISPLAY_NAME"
        const val EXTRA_CHALLENGE = "com.keepasskey.extra.CHALLENGE"
        const val EXTRA_ORIGIN = "com.keepasskey.extra.ORIGIN"
    }
}
