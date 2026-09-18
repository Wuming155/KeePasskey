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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                    PasskeyAssertionPayload.build(
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


    companion object {
        private const val TAG = "PasskeyAssertionActivity"
        const val EXTRA_ENTRY_ID = "com.keepasskey.extra.ENTRY_ID"
        const val EXTRA_REQUEST_JSON = "com.keepasskey.extra.REQUEST_JSON"
        const val EXTRA_CHALLENGE = "com.keepasskey.extra.CHALLENGE"
        const val EXTRA_ORIGIN = "com.keepasskey.extra.ORIGIN"
        const val EXTRA_EXPECTED_PACKAGE = "com.keepasskey.extra.EXPECTED_PACKAGE"
    }
}
