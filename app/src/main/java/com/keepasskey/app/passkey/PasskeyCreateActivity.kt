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
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import com.keepasskey.crypto.passkey.PasskeyPrf
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 通行密钥创建与注册落地 Activity (对齐 P0-3 要求)。
 * 在受保护的独立生命周期中完成：
 * 1. 确定调用方来源并校验（H1 / L1）；
 * 2. 库锁定时在**同一受保护窗口内**呈现解锁页（[CredentialUnlockPresenter]）——
 *    整改前此处直接 `failAndFinish()`，用户点选系统「创建通行密钥」后静默失败；
 * 3. **ISSUE-P2-220**：任一 fail-closed 拒绝分支都在受保护窗口内呈现**明确原因**
 *    （[rejectAndFinish] + [CredentialRejectionReason]），由用户确认后才收尾——
 *    整改前各分支静默 `finish()`，用户视角是「点了继续就断」；回传契约不变（仍取消）；
 * 4. **ISSUE-P0-03 (ZT-03)**：生成密钥并落库前，先执行「本次实际发生」的用户验证门控
 *    （RP 声明 `userVerification: required` 时强制系统级强验证，绝不降级为手动确认）；
 * 5. **按 RP 的 `pubKeyCredParams` 协商算法**生成密钥对（此前写死 ES256）；
 * 6. **原样采用 RP 下发的 `user.id` 作为 userHandle**（此前自造随机值，破坏无用户名登录）；
 * 7. `excludeCredentials` 命中库内既有凭据即 fail-closed 拒绝（防重复注册）；
 * 8. 请求携带 `extensions.prf` 时生成并持久化 PRF 秘密，并在响应中回传 `prf` 结果；
 * 9. 写入 KDBX 密码库并原子落盘（同 rpId + 用户名已有条目则**原地替换**，不产生重复条目）；
 * 10. 构造标准 WebAuthn W3C 证明对象 (AttestationObject) 与确定性 CBOR 编码；
 * 11. 通过 PendingIntentHandler 回传 CreatePublicKeyCredentialResponse。
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

    /** ISSUE-P2-02：读取设置页「跳过通行密钥站点归属校验」显式用户授权开关（ISSUE-P2-240 前文案漂移） */
    @Inject
    lateinit var extendedSettingsStore: com.keepasskey.app.data.repository.ExtendedSettingsStore

    /** ISSUE-P2-83：CM 通道调用方「包名 + 主签名摘要」首次绑定存储（`android://` 维度放行依据） */
    @Inject
    lateinit var callerTrustStore: CredentialManagerCallerTrustStore

    /**
     * ISSUE-P2-199：特权浏览器白名单——用于按**本次**系统背书的 [androidx.credentials.provider.CallingAppInfo]
     * 重新派生 origin，取代对组装期写入 base Intent 的 origin 副本的信任。
     */
    @Inject
    lateinit var privilegedBrowserStore: com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore

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

        // ISSUE-P2-199：base Intent 上的 extras 只是**组装期的展示缓存**（含设备侧可读的
        // 条目文案与请求摘要），不再参与任何安全判定——判定一律依据系统经 fillIn Intent
        // 注入的「本次」请求（`providerReq` / `callingRequest` / `callingAppInfo`）。
        val cachedRpId = intent.getStringExtra(EXTRA_RP_ID).orEmpty()
        val cachedUserName = intent.getStringExtra(EXTRA_USER_NAME).orEmpty()
        val cachedUserDisplayName = intent.getStringExtra(EXTRA_USER_DISPLAY_NAME).orEmpty()
        val cachedChallenge = intent.getStringExtra(EXTRA_CHALLENGE).orEmpty()

        providerReq = try {
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        } catch (_: Exception) {
            null
        }
        val injected = providerReq
        if (injected == null) {
            // 系统未注入创建请求（理论上仅当条目 PendingIntent 非 FLAG_MUTABLE 时发生）：
            // 没有系统背书的调用方与请求，一切字段都不可信 ⇒ fail-closed，绝不按缓存副本注册。
            AppLog.e(TAG, "缺少系统注入的创建请求（条目 PendingIntent 需 FLAG_MUTABLE），拒绝创建")
            rejectAndFinish(CredentialRejectionReason.MISSING_REQUEST)
            return
        }

        // 系统请求 JSON 是**平台下发**的权威来源，整体覆盖组装期缓存副本
        // （缓存仅在系统侧字段缺失 / 解析失败时兜底，见各行的 takeIf 判定）
        val callingReq = injected.callingRequest
        request = (callingReq as? CreatePublicKeyCredentialRequest)
            ?.let { WebAuthnRequest.parse(it.requestJson) }
        rpId = request?.rpId?.takeIf { it.isNotBlank() } ?: cachedRpId
        userName = request?.userName?.takeIf { it.isNotBlank() } ?: cachedUserName
        userDisplayName = request?.userDisplayName?.takeIf { it.isNotBlank() } ?: cachedUserDisplayName
        challenge = request?.challenge?.takeIf { it.isNotBlank() } ?: cachedChallenge

        // H1 整改：origin 只认系统背书——由**本次** CallingAppInfo 重新派生（特权浏览器走
        // 官方 getOrigin + 白名单，普通应用固定 apk-key-hash），派生不出即 fail-closed。
        // ISSUE-P2-199：组装期写入的 origin 副本曾是判定来源，被跨请求覆写后会让
        // `passesRegistrationGates` 整段跳过 DAL 校验，故该副本自此不再具备任何判定效力。
        //
        // 归属降级**一律** `android:apk-key-hash:`（与 KeePassDX / Monica 逐字一致），
        // 不做 `https://{rpId}` 兜底——该兜底曾被实测证伪，理由见 [CallingOriginResolver]。
        origin = CallingOriginResolver.resolveTrustedOrigin(
            injected.callingAppInfo,
            privilegedBrowserStore.allowlistJson()
        )

        // 归属诊断留痕（ISSUE-P1-10：只记布尔与类别，不记 rpId / 包名 / 指纹 / 站点域明文）。
        // 目的：区分三种归属来源——「非特权调用（未携带 origin）」「特权白名单未命中被降级」
        // 「已取得系统背书 origin」。三者在注册材料上表现为不同的 clientDataJSON.origin，
        // RP 侧据此判通过或拒绝；此前该决策链**全程静默**，是「本地成功、RP 报验证失败」无法归因的根因。
        val createPublicKeyRequest = callingReq as? CreatePublicKeyCredentialRequest
        AppLog.i(
            TAG,
            "注册归属诊断: originPopulated=${injected.callingAppInfo?.isOriginPopulated()}" +
                ", clientDataHashPresent=${createPublicKeyRequest?.clientDataHash != null}" +
                ", requestJsonHasOrigin=${hasOriginKey(createPublicKeyRequest?.requestJson)}" +
                ", originKind=${originKindOf(origin)}"
        )

        if (rpId.isBlank() || userName.isBlank()) {
            // ISSUE-P1-10：日志不得携带 rpId / userName 等敏感标识
            AppLog.e(TAG, "缺少必要注册参数（rpId 或 userName 为空）")
            rejectAndFinish(CredentialRejectionReason.MISSING_PARAMETERS)
            return
        }

        // 锁库时在受保护窗口内先解锁，解锁成功后从同一入口继续注册流程
        unlockPresenter.requireUnlocked(this) { startCreation() }
    }

    /** 解锁后（或库本就解锁）的注册主流程 */
    private fun startCreation() {
        lifecycleScope.launch {
            try {
                awaitRegistration()
            } catch (t: Throwable) {
                AppLog.e(TAG, "Passkey 注册异常", t)
                rejectAndFinish(CredentialRejectionReason.INTERNAL_ERROR)
            }
        }
    }

    /**
     * 注册门禁链（ISSUE-P3-188 拆分）：锁定态复核 → 注册门禁（DAL / 归属校验）→
     * `excludeCredentials` 查重 → 用户验证门控；判定顺序与 fail-closed 口径逐字不变。
     *
     * ISSUE-P2-220：每一处拒绝都携带**用户可见的原因**（[rejectAndFinish]）——整改前此处
     * 一律静默 `failAndFinish()`，被安全门控拒绝的用户只看到「点了继续就断」。
     */
    private suspend fun awaitRegistration() {
        if (vaultRepository.isLocked()) {
            AppLog.w(TAG, "密码库仍未解锁，无法注册新 Passkey")
            rejectAndFinish(CredentialRejectionReason.VAULT_LOCKED)
            return
        }

        // 普通应用（apk-key-hash origin）创建的凭据额外记录调用包绑定（android://<包名>）。
        // ISSUE-P2-72：仅接受**系统背书**的 CallingAppInfo 包名，取不到即返回 null。
        val callingAppInfo = providerReq?.callingAppInfo
        val callerPackage = CallingOriginResolver.systemAttestedPackageName(callingAppInfo)
        val certDigests = callingAppInfo?.let { CallingOriginResolver.certDigests(it) }
            ?: CallerCertDigests.EMPTY
        val skipDalVerification = extendedSettingsStore.load().skipDalVerification
        if (skipDalVerification) {
            AppLog.w(TAG, "用户已显式开启「跳过通行密钥站点归属校验」，本次注册不执行远程声明验证")
        }
        val gateRejection = PasskeyRegistrationGate.evaluate(
            origin = origin,
            callerPackage = callerPackage,
            skipDalVerification = skipDalVerification,
            callingAppInfoPresent = callingAppInfo != null,
            certDigests = certDigests
        ) { verifiedPackage ->
            // ISSUE-P3-93：以调用方**全部**签名摘要参与 DAL 校验（签名轮换期任一命中即通过）
            dalVerifier.verify(rpId, verifiedPackage, certDigests)
        }
        if (gateRejection != null) {
            // ISSUE-P1-10：日志只记拒绝类别，不得携带 rpId / 调用包名等敏感标识
            AppLog.w(TAG, "注册门禁拒绝（${gateRejection.name}），fail-closed 不创建 Passkey")
            // ISSUE-P3-221：调用方确为浏览器时给就地授权（窗口内闭环、无跳转）；
            // 资格不成立（原生 App / 包名不可得 / 已启用）时 builder 返回 null
            val remedy = BrowserRemedyBuilder.build(
                context = this@PasskeyCreateActivity,
                store = privilegedBrowserStore,
                reason = gateRejection,
                callerPackage = callerPackage,
                scope = lifecycleScope
            )
            rejectAndFinish(gateRejection, remedy)
            return
        }

        // ISSUE：`excludeCredentials` 查重（WebAuthn 规范要求认证器拒绝创建已排除的凭据）
        if (!ensureNotExcluded()) {
            rejectAndFinish(CredentialRejectionReason.CREDENTIAL_ALREADY_EXISTS)
            return
        }
        requestCreationUserVerification(callerPackage)
    }

    /** RP 要求 `userVerification: "required"` 时强制系统级强验证（不得降级为手动确认） */
    private fun requestCreationUserVerification(callerPackage: String?) {
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
                bindCallerForRegistration(callerPackage)
                createAndReturn(callerPackage = callerPackage, verification = verification)
            },
            onRejected = {
                if (settled) return@requestCredentialUserVerification
                settled = true
                AppLog.w(TAG, "用户验证未通过，拒绝创建 Passkey")
                failAndFinish()
            }
        )
    }

    /**
     * ISSUE-P2-83：注册流程是「用户在受保护窗口内把凭据显式交给该调用方」的两个入口之一
     * （另一个是保存），故在验证通过后、落库前写入 CM 通道绑定。
     * fail-closed：包名不可得或摘要不可读一律**不写入**（保持未绑定）。
     */
    private fun bindCallerForRegistration(callerPackage: String?) {
        val callerDigests = providerReq?.callingAppInfo
            ?.let { CallingOriginResolver.certDigests(it) }
            ?: CallerCertDigests.EMPTY
        if (callerPackage.isNullOrBlank() || callerDigests.isEmpty) {
            AppLog.w(TAG, "调用方包名或签名摘要不可读，CM 通道保持未绑定（fail-closed）")
        } else {
            callerTrustStore.trust(callerPackage, callerDigests.primary)
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
                    rejectAndFinish(CredentialRejectionReason.INTERNAL_ERROR)
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
                    rejectAndFinish(CredentialRejectionReason.REQUEST_INVALID)
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
                        prfEval = prfEval,
                        credPropsRequested = request?.credPropsRequested == true
                    )
                }

                val resultIntent = Intent()
                val response = CreatePublicKeyCredentialResponse(regResponseJson)
                PendingIntentHandler.setCreateCredentialResponse(resultIntent, response)
                setResult(RESULT_OK, resultIntent)
                finish()
            } catch (t: Throwable) {
                AppLog.e(TAG, "Passkey 注册异常", t)
                rejectAndFinish(CredentialRejectionReason.INTERNAL_ERROR)
            }
        }
    }

    /**
     * 组装注册响应 JSON（**§162 下沉**至 [PasskeyRegistrationPayload]：注册材料是纯函数产物，
     * 不读 Activity 状态，只用到 `rpId` / `origin` 两个入参）。行为与实现逐字保持，含
     * ISSUE-P2-72 的「归属字段只写系统背书的调用方包名」与两段缓冲的 `finally` 擦除。
     */
    private fun buildRegistrationJson(
        passkeyData: PasskeyData,
        challenge: String,
        callerPackage: String?,
        flags: Byte,
        prfEval: WebAuthnRequest.PrfEval?,
        credPropsRequested: Boolean
    ): String = PasskeyRegistrationPayload.build(
        passkeyData = passkeyData,
        challenge = challenge,
        callerPackage = callerPackage,
        flags = flags,
        prfEval = prfEval,
        rpId = rpId,
        origin = origin,
        credPropsRequested = credPropsRequested
    )

    /**
     * `requestJson` 是否自带 `origin` 键（**只判存在性、不读值**，避免把站点域写进日志）。
     *
     * 用途：非特权调用方的网页 origin 只可能来自请求本身；本仓当前忽略该键，
     * 若调用方（浏览器）实际提供了它，则该事实是恢复正确归属的直接依据。
     */
    private fun hasOriginKey(requestJson: String?): Boolean {
        if (requestJson.isNullOrBlank()) return false
        return try {
            JSONObject(requestJson).has(WebAuthnJson.ORIGIN)
        } catch (_: Exception) {
            false
        }
    }

    /** origin 的归属类别（**只记类别、不记明文**，`origin` 含站点域属敏感标识） */
    private fun originKindOf(origin: String): String = when {
        origin.startsWith("https://") -> "web"
        origin.startsWith(CallingOriginResolver.APK_KEY_HASH_PREFIX) -> "apk-key-hash"
        origin.isBlank() -> "blank"
        else -> "other"
    }

    companion object {
        private const val TAG = "PasskeyCreateActivity"

        /**
         * 组装期请求摘要的**展示缓存**（ISSUE-P2-199）。
         *
         * 这些 extras 由 [CredentialCreateEntries] 在**组装期**写入 base Intent；自
         * ISSUE-P2-199 起，它们**不再作为任何安全判定的来源**（origin 已彻底不再读取，
         * 其余字段仅在系统注入的 `callingRequest` 缺失该字段时兜底）。保留写入是为了
         * 抓包 / 日志中辨认「系统实际拉起了哪一路创建入口」，以及为系统侧字段缺失兜底。
         */
        const val EXTRA_RP_ID = "com.keepasskey.extra.RP_ID"
        const val EXTRA_USER_NAME = "com.keepasskey.extra.USER_NAME"
        const val EXTRA_USER_DISPLAY_NAME = "com.keepasskey.extra.USER_DISPLAY_NAME"
        const val EXTRA_CHALLENGE = "com.keepasskey.extra.CHALLENGE"

        /**
         * 组装期 origin 副本。**已无任何读取点**（ISSUE-P2-199 起 origin 一律由本次
         * 系统背书的 CallingAppInfo 重新派生）；保留常量仅为兼容既有设备侧匹配键用例
         * （`PendingIntentMatchKeyDeviceTest`）与将来可能的展示需求。
         */
        const val EXTRA_ORIGIN = "com.keepasskey.extra.ORIGIN"
    }
}
