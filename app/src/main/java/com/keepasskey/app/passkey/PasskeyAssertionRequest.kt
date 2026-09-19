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
     *
     * ISSUE-P2-199：origin 同样以**本次**系统背书派生值为权威，与组装期写入 base Intent 的
     * 副本交叉核对（两者均可得且不一致 ⇒ 该记录已被另一路请求就地覆写，fail-closed）。
     * 覆盖判定与包名交叉核对同级：只做「能红的判别」，不改变可取到系统请求时的既有放行口径。
     *
     * @param browserAllowlistJson 特权浏览器白名单（由调用方注入的
     *   [com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore] 提供），
     *   用于把本次的 CallingAppInfo 解析为可信 origin
     */
    fun resolveAttestedCaller(
        intent: Intent,
        tag: String,
        browserAllowlistJson: String
    ): PasskeyAssertionCaller {
        val expectedPackage = intent.getStringExtra(PasskeyAssertionActivity.EXTRA_EXPECTED_PACKAGE).orEmpty()
        val providerReq: ProviderGetCredentialRequest? = try {
            PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        } catch (_: Exception) {
            null
        }
        val attestedPackage = CallingOriginResolver.systemAttestedPackageName(providerReq?.callingAppInfo)
        val cachedOrigin = intent.getStringExtra(PasskeyAssertionActivity.EXTRA_ORIGIN).orEmpty()
        // ISSUE-P2-199：origin 由**本次** CallingAppInfo 派生；派生不出（签名不可读等）时
        // 保留组装期副本，使降级路径的既有行为不变（该降级在 `passesOriginBinding` 处仍受门控）。
        val attestedOrigin = providerReq?.callingAppInfo
            ?.let { CallingOriginResolver.resolveTrustedOrigin(it, browserAllowlistJson) }
            .orEmpty()
        val origin = attestedOrigin.ifBlank { cachedOrigin }

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
                origin = origin,
                providerReq = providerReq,
                clientDataPackage = null
            )
        }
        if (attestedOrigin.isNotBlank() && cachedOrigin.isNotBlank() && attestedOrigin != cachedOrigin) {
            AppLog.e(tag, "系统背书 origin 与组装期副本不一致（疑似 PendingIntent 记录被跨请求覆写），拒绝签发断言")
            return PasskeyAssertionCaller(
                rejected = true,
                expectedPackage = expectedPackage,
                origin = attestedOrigin,
                providerReq = providerReq,
                clientDataPackage = null
            )
        }
        return PasskeyAssertionCaller(
            rejected = false,
            expectedPackage = expectedPackage,
            origin = origin,
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
            // ISSUE-P2-199：challenge 以系统请求 JSON 为准（权威），组装期副本仅在系统侧缺失时兜底
            challenge = request?.challenge?.takeIf { it.isNotBlank() }
                ?: intent.getStringExtra(PasskeyAssertionActivity.EXTRA_CHALLENGE).orEmpty(),
            // ISSUE-P2-199：origin 取 [resolveAttestedCaller] 的派生结果，不再直接读组装期副本
            origin = caller.origin,
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
    /**
     * ISSUE-P2-199：本次调用的可信 origin（优先由系统背书 CallingAppInfo 派生，
     * 派生不出时回落组装期副本）。[PasskeyAssertionCaller.rejected] 时不参与签发。
     */
    val origin: String,
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
