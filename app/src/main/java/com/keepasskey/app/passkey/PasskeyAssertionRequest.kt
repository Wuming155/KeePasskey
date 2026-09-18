package com.keepasskey.app.passkey

import android.content.Intent
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
import com.keepasskey.core.log.AppLog

/**
 * Passkey 断言的**请求面解析**（ISSUE-P3-188 从 [PasskeyAssertionActivity] 下沉为同包协作对象）。
 *
 * 只承担「系统背书调用方核对」与「断言请求装配」两件事，不做任何签发决策与门控：
 * 归属校验不通过时以 [PasskeyAssertionCaller.rejected] 如实上报，fail-closed 收尾仍由
 * Activity 执行（日志沿用调用方 TAG，便于与签发侧留痕串读）。
 */
internal object PasskeyAssertionRequestParser {

    /**
     * ISSUE-P2-72：归属字段取**系统认证**的 CallingAppInfo 包名——系统把原始
     * BeginGetCredentialRequest 注入本窗口的 PendingIntent，其中 `callingAppInfo.packageName`
     * 由平台背书（凭据组装阶段亦用它做严格匹配）。原实现用 `Activity.getCallingPackage()`，
     * 在 PendingIntent 拉起场景为 `"android"`/null，随后 `?: packageName` 又回退成
     * **本应用包名**——等于向 RP 谎报调用方。
     */
    fun resolveAttestedCaller(intent: Intent, tag: String): PasskeyAssertionCaller {
        val expectedPackage = intent.getStringExtra(PasskeyAssertionActivity.EXTRA_EXPECTED_PACKAGE).orEmpty()
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
            AppLog.e(tag, "系统认证调用方与预期包名不一致，拒绝签发断言")
            return PasskeyAssertionCaller(
                rejected = true,
                expectedPackage = expectedPackage,
                providerReq = providerReq,
                clientDataPackage = null
            )
        }
        return PasskeyAssertionCaller(
            rejected = false,
            expectedPackage = expectedPackage,
            providerReq = providerReq,
            clientDataPackage = CallingOriginResolver.clientDataAndroidPackageName(
                attestedPackage,
                expectedPackage
            )
        )
    }

    /** 装配断言入参；缺少 `entryId` 时返回 null（由调用方 fail-closed 收尾） */
    fun buildAssertionContext(
        intent: Intent,
        tag: String,
        caller: PasskeyAssertionCaller
    ): PasskeyAssertionContext? {
        val entryId = intent.getStringExtra(PasskeyAssertionActivity.EXTRA_ENTRY_ID).orEmpty()
        if (entryId.isBlank()) {
            AppLog.e(tag, "缺少通行密钥 entryId")
            return null
        }
        // 系统下发的断言请求（权威来源）：请求 JSON 与**特权调用方自带的 clientDataJSON 摘要**
        val option = caller.providerReq?.credentialOptions
            ?.firstOrNull { it is GetPublicKeyCredentialOption } as? GetPublicKeyCredentialOption
        val requestJson = option?.requestJson
            ?: intent.getStringExtra(PasskeyAssertionActivity.EXTRA_REQUEST_JSON).orEmpty()
        // 特权调用方（自带 clientDataJSON 的浏览器）下发摘要时必须直接对其签名——自建 JSON
        // 的哈希与其预期不一致会让 RP 侧验签失败（KeePassDX 同口径）
        val request = WebAuthnRequest.parse(requestJson)
        return PasskeyAssertionContext(
            entryId = entryId,
            challenge = intent.getStringExtra(PasskeyAssertionActivity.EXTRA_CHALLENGE).orEmpty(),
            origin = intent.getStringExtra(PasskeyAssertionActivity.EXTRA_ORIGIN).orEmpty(),
            expectedPackage = caller.expectedPackage,
            clientDataPackage = caller.clientDataPackage,
            providerReq = caller.providerReq,
            providedClientDataHash = option?.clientDataHash?.takeIf { it.isNotEmpty() },
            prfEval = request?.prfEval,
            requireBiometric = request?.userVerification == WebAuthnRequest.UserVerification.REQUIRED
        )
    }
}

/** 系统背书调用方的解析产物（ISSUE-P3-188：仅收敛参数传递，字段口径不变） */
internal data class PasskeyAssertionCaller(
    val rejected: Boolean,
    val expectedPackage: String,
    val providerReq: ProviderGetCredentialRequest?,
    val clientDataPackage: String?
)

/**
 * 断言签发的请求面入参（ISSUE-P3-188：把原 `onCreate` 的局部量一次性装配，
 * 取值来源与省略语义与拆分前完全一致）。
 */
internal data class PasskeyAssertionContext(
    val entryId: String,
    val challenge: String,
    val origin: String,
    val expectedPackage: String,
    val clientDataPackage: String?,
    val providerReq: ProviderGetCredentialRequest?,
    val providedClientDataHash: ByteArray?,
    val prfEval: WebAuthnRequest.PrfEval?,
    val requireBiometric: Boolean
)
