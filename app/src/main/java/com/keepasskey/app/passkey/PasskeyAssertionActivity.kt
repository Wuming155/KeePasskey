package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.R
import com.keepasskey.app.coroutines.safeLaunch
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

    /** ISSUE-P3-298 ⑥：条目上次使用时刻记录（断言回传成功即真实使用点） */
    @Inject
    lateinit var credentialLastUsedStore: CredentialLastUsedStore

    /**
     * ISSUE-P2-199：特权浏览器白名单——供请求面解析器按**本次**系统背书的 CallingAppInfo
     * 重新派生 origin，并与组装期副本交叉核对（覆盖判定即 fail-closed）。
     */
    @Inject
    lateinit var privilegedBrowserStore: com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore

    /**
     * ENG-20261028-05：通道开关在签发边界的复核来源。公钥断言同时受 CM 通道总开关与
     * 「通行密钥支持」开关约束（判定表见 [CredentialDeliveryChannelGate]）。
     */
    @Inject
    lateinit var settingsStore: com.keepasskey.app.data.repository.ExtendedSettingsStore

    /** 防止验证回调 / 取消回调 / 重复 finish 交错产生多重签发或重复收尾 */
    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!credentialSessionGate.bind(intent, CredentialEntryRequestType.PASSKEY_ASSERTION)) {
            failAndFinish()
            return
        }
        // ENG-20261028-05：通道开关前置复核（代价最低）——候选呈现后用户可能已关闭通道，
        // 此时既不该解析请求也不该拉起验证弹窗。签发前另有同判据复核（见 [signAndReturn]）。
        if (rejectsClosedChannel()) {
            failAndFinish()
            return
        }

        // ISSUE-P3-188：`onCreate` 收敛为「解析调用方 → 解析请求 → 派发」三段编排，
        // 请求面解析下沉同包协作对象；判定顺序与 fail-closed 口径**逐字不变**
        val caller = PasskeyAssertionRequestParser.resolveAttestedCaller(
            intent,
            TAG,
            privilegedBrowserStore.allowlistJson()
        )
        if (caller.rejected || caller.providerReq == null) {
            failAndFinish()
            return
        }
        val context = PasskeyAssertionRequestParser.buildAssertionContext(intent, TAG, caller)
        if (context == null) {
            failAndFinish()
            return
        }
        if (!credentialSessionGate.matchesEntry(context.entryId) ||
            !credentialSessionGate.matchesCaller(context.origin, context.expectedPackage)) {
            failAndFinish()
            return
        }
        lifecycleScope.safeLaunch("PasskeyAssertionActivity") {
            try {
                credentialSessionGate.withRequest { performAssertion(context) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(TAG, "Passkey 认证执行失败", t)
                failAndFinish()
            }
        }
    }

    /**
     * ENG-20261028-05：公钥通道的开关复核。
     *
     * 公钥交付同时受 CM 通道总开关与「通行密钥支持」开关约束（判定表见
     * [CredentialDeliveryChannelGate]）：总开关关闭时即使通行密钥开关仍开启也不得签发。
     */
    private fun rejectsClosedChannel(): Boolean {
        val closed = !CredentialDeliveryChannelGate.allowsPasskeyDelivery(
            credentialProviderEnabled = settingsStore.isCredentialProviderEnabled(),
            passkeySupportEnabled = settingsStore.isPasskeySupportEnabled()
        )
        if (closed) AppLog.w(TAG, "凭据管理器通道或通行密钥支持已在设置中关闭，拒绝签发 Passkey 断言")
        return closed
    }

    /** 库状态 / 条目 / Passkey 字段核对，通过后进入 origin 绑定门控与用户验证 */
    private suspend fun performAssertion(context: PasskeyAssertionContext) {
        if (vaultRepository.isLocked()) {
            AppLog.w(TAG, "密码库处于锁定状态，无法执行 Passkey 认证")
            failAndFinish()
            return
        }

        // `ENG-20261028-11`：读租约**只覆盖「取条目 → 读字段 → 复制私钥」这一段**（见
        // [copyPasskeySecretsUnderLease]）。私钥 / PRF 在租约内被复制成本流程独占的
        // `ProtectedString` 后立即撤租——用户验证可挂起任意久，持租约会把全进程的下线实例
        // 回收一起拖住；签名与计数器写入都只依赖该独占副本，不再读库内实例。
        val owned = copyPasskeySecretsUnderLease(context) ?: return
        requestAssertionUserVerification(context, owned)
    }

    /**
     * 在一条读租约内完成「取条目 → 校验 → 复制私钥 / PRF 独占副本」，块结束即释放租约。
     *
     * 返回 `null` 表示本次断言已按既有口径收尾（失败 / 拒绝），调用方直接返回即可。
     * 之所以不把租约延到用户验证之后：验证可挂起任意久，持租约会把全进程的下线实例回收
     * 一起拖住；私钥已复制为独占副本，此后的签名不再依赖库内实例。
     */
    private suspend fun copyPasskeySecretsUnderLease(context: PasskeyAssertionContext): PasskeyData? {
        val lease = vaultRepository.acquireEntriesReadLease()
        try {
            // ISSUE-P2-341：断言执行侧同样只认「可用条目」——候选列出了却不给签是困惑，
            // 但反过来"删掉了还能签"是越出用户意图；本行保证两侧口径一致。
            val allEntries = vaultRepository.getUsableKdbxEntries()
            val entry = allEntries.firstOrNull { it.id.toHexString() == context.entryId }
            if (entry == null) {
                // ISSUE-P1-10：日志不得携带 entryId 等敏感标识
                AppLog.e(TAG, "未找到目标条目")
                failAndFinish()
                return null
            }

            val passkeyData = PasskeyData.fromCustomFields(entry.customFields)
            if (passkeyData == null ||
                (context.allowedCredentialIds.isNotEmpty() &&
                    passkeyData.credentialId !in context.allowedCredentialIds)
            ) {
                AppLog.e(TAG, "条目不含有效的 Passkey 或不在最终请求允许列表内")
                failAndFinish()
                return null
            }

            // F4 整改：origin 缺失一律拒绝签发（fail-closed），不得以 RP ID 冒充 web origin
            if (context.origin.isBlank()) {
                AppLog.e(TAG, "缺少调用来源 origin，拒绝签发断言")
                failAndFinish()
                return null
            }
            if (!authorizeAssertion(context, passkeyData, entry.url)) {
                return null
            }
            // 私钥 / PRF 秘密复制为独占副本：此后即使库内实例被回收，签名仍可用本副本完成
            return passkeyData.withOwnedSecrets()
        } finally {
            lease.close()
        }
    }

    /**
     * 把私钥与 PRF 秘密复制为**本流程独占**的 `ProtectedString`（按字节通道读出即封存，不留明文中间量）。
     *
     * 用途：用户验证可挂起任意久，签名时不能再依赖库内实例（租约已在复制后释放）。
     * 本副本只由本流程持有，签名结束 / 拒签时经 [releaseOwnedSecrets] 显式擦除。
     */
    private fun PasskeyData.withOwnedSecrets(): PasskeyData = copy(
        privateKey = usePrivateKeyBytes { bytes -> ProtectedString(bytes = bytes) },
        prfSecret = prfSecret?.let { secret -> secret.useUtf8 { bytes -> ProtectedString(bytes = bytes) } },
        prfNoUvSecret = prfNoUvSecret?.let { secret ->
            secret.useUtf8 { bytes -> ProtectedString(bytes = bytes) }
        }
    )

    /** 擦除 [withOwnedSecrets] 复制的独占副本（不影响库内条目实例）。 */
    private fun releaseOwnedSecrets(data: PasskeyData) {
        data.privateKey.clear()
        data.prfSecret?.clear()
        data.prfNoUvSecret?.clear()
    }

    /**
     * 归属复校失败后的收尾（`ISSUE-P2-530`）。
     *
     * 候选列表自本批起按请求 `rp.id` 放行（对齐同类项目），因此「列得出来、签不出去」不再
     * 是理论组合：非白名单浏览器上用户会**先看到条目再被拒**。故此处把原先的静默
     * [failAndFinish] 换成拒绝原因页——调用方确属「已安装、且未加入特权名单的浏览器」时
     * 一并给出「加入特权名单」的就地补救（资格守卫复用 [BrowserRemedyBuilder]，
     * 与创建链路同款）；其余情形（原生应用 / 已启用的浏览器 / 真不匹配）保持静默收尾，
     * 不凭空造一个用户无从下手的页面。
     *
     * 本次断言**仍然失败**：origin 在候选组装之初即已固定为 `apk-key-hash`，授权只影响**下一次**发起
     * （与 [CredentialRejectionAction] 的「就地」语义一致）。
     */
    private fun rejectUntrustedCaller(context: PasskeyAssertionContext) {
        val attestedPackage = CallingOriginResolver.systemAttestedPackageName(context.providerReq?.callingAppInfo)
        val reason = CredentialRejectionReason.CALLER_BROWSER_NOT_TRUSTED
        val remedy = attestedPackage?.let {
            BrowserRemedyBuilder.build(
                context = this,
                store = privilegedBrowserStore,
                reason = reason,
                callerPackage = it,
                scope = lifecycleScope
            )
        }
        if (remedy == null) {
            failAndFinish()
            return
        }
        rejectAndFinish(reason, remedy, R.string.cred_reject_get_title)
    }

    /**
     * 签发前的**授权裁决 + 拒绝呈现**（`ENG-20261028-06`）。
     *
     * 返回 `true` 表示授权成立、可以继续用户验证与签发；返回 `false` 表示本次断言已按
     * 对应原因在受保护窗口内呈现并收尾（或静默收尾），调用方直接返回即可。
     *
     * ## 判定顺序（先结构、后授权）
     *
     * 1. **请求 RP 与凭据 RP**：请求自报的 RP 与凭据不符 ⇒ 拒绝（与调用方身份无关）；
     * 2. **归属复校**：浏览器走 origin ⇄ 凭据 RP；原生要求条目 `android://<包名>` 等于系统背书的
     *    调用包名。失败即拒绝——此时连「哪把密钥归谁」都不成立，给授权入口只会误导；
     * 3. **调用方签名授权**（原生）：包名相同**不**等于已授权，签名绑定是唯一参照，
     *    与请求是否声明 RP 无关（请求 JSON 由调用方自报）。三类结果：
     *    - `ALLOWED`：继续；
     *    - `CALLER_UNBOUND`：呈现原因 + **登记此应用签名**的就地补救；
     *    - `CALLER_BINDING_REJECTED`：呈现原因，**不提供**任何重绑入口。
     *
     * 第 1、2 步拒绝沿用 `ISSUE-P2-530` 的收尾口径（[rejectUntrustedCaller]：调用方确为
     * 未入名单的浏览器时给出就地补救，其余静默）。第 3 步才使用 `ENG-20261028-06` 的新原因。
     * 浏览器缺 RP 不进入第 3 步：WebAuthn 缺省 `rpId` 即 origin 的有效域，归属由第 2 步锚定。
     */
    private fun authorizeAssertion(
        context: PasskeyAssertionContext,
        passkeyData: PasskeyData,
        entryUrl: String
    ): Boolean {
        val browserFlow = CallingOriginResolver.isBrowserOrigin(context.origin)
        val certDigests = context.providerReq?.callingAppInfo
            ?.let { CallingOriginResolver.certDigests(it) }
            ?: CallerCertDigests.EMPTY
        // 第 1 步：请求 RP 与凭据 RP 的精确对照（两条通道同一口径）
        if (PasskeyRequestRpBinding.evaluate(context.requestRpId, passkeyData.relyingPartyId) ==
            RequestRpBinding.MISMATCHED
        ) {
            AppLog.e(TAG, "请求 RP 与凭据 RP 不一致，拒绝签发断言")
            // 沿用 ISSUE-P2-530 的收尾口径：调用方确为未入名单的浏览器时给出就地补救，
            // 其余情形（原生应用 / 已启用浏览器 / 真不匹配）保持静默
            rejectUntrustedCaller(context)
            return false
        }
        // 第 2 步：归属复校
        if (browserFlow) {
            val originHost = DomainMatcher.extractDomain(context.origin)
            if (originHost.isEmpty() ||
                !DomainMatcher.isDomainMatch(passkeyData.relyingPartyId, originHost)
            ) {
                AppLog.e(TAG, "origin 与凭据 RP-ID 不匹配，拒绝签发断言")
                rejectUntrustedCaller(context)
                return false
            }
            return true
        }
        val boundPackage = DomainMatcher.extractAndroidBoundPackage(entryUrl)
        if (context.expectedPackage.isBlank() ||
            boundPackage != context.expectedPackage.trim().lowercase()
        ) {
            AppLog.e(TAG, "调用包名与凭据绑定包名不一致，拒绝签发断言")
            // 同上：非白名单浏览器（origin 已退化为 apk-key-hash、条目为 Web 绑定时）
            // 仍要拿到「加入特权名单」的就地补救（ISSUE-P2-530）
            rejectUntrustedCaller(context)
            return false
        }
        // 第 3 步：原生调用的签名授权（web 调用已在第 2 步以 origin 锚定并返回）
        val callerBinding = CredentialManagerPackageBindingGate.decide(
            callingPackage = context.expectedPackage,
            certDigests = certDigests,
            isTrusted = callerTrustStore::isTrusted,
            hasAnyBinding = callerTrustStore::hasAnyBindingFor
        )
        val verdict = PasskeyRpAuthorization.evaluate(
            requestRpId = context.requestRpId,
            credentialRpId = passkeyData.relyingPartyId,
            browserFlow = false,
            callerBinding = callerBinding
        )
        // ISSUE-P1-10：只记裁决类别，不记 rpId / 包名 / 凭据标识
        return when (verdict) {
            PasskeyRpVerdict.ALLOWED -> true
            PasskeyRpVerdict.RP_MISMATCH -> {
                // 理论上已在第 1 步拦下；保留 fail-closed 分支，避免未来调整顺序时静默放行
                AppLog.e(TAG, "签发授权裁决未通过（${verdict.name}），拒绝签发断言")
                failAndFinish()
                false
            }
            PasskeyRpVerdict.CALLER_UNBOUND,
            PasskeyRpVerdict.CALLER_BINDING_REJECTED -> {
                AppLog.w(TAG, "签发授权裁决未通过（${verdict.name}）")
                rejectCallerSignature(context, certDigests, verdict)
                false
            }
        }
    }

    /**
     * 原生调用方的签名裁决拒绝（`ENG-20261028-06`）。
     *
     * [PasskeyRpVerdict.CALLER_UNBOUND] 给出「登记此应用签名」的就地补救
     * （[CallerSignatureRemedyBuilder]，资格与 fail-closed 前提在构建器内）；
     * [PasskeyRpVerdict.CALLER_BINDING_REJECTED] **不构造**补救——已登记而签名不符时
     * 就地重绑等于让顶替者自证身份，故只呈现原因。
     */
    private fun rejectCallerSignature(
        context: PasskeyAssertionContext,
        certDigests: CallerCertDigests,
        verdict: PasskeyRpVerdict
    ) {
        val reason = when (verdict) {
            PasskeyRpVerdict.CALLER_UNBOUND -> CredentialRejectionReason.CALLER_SIGNATURE_UNBOUND
            else -> CredentialRejectionReason.CALLER_SIGNATURE_MISMATCH
        }
        val remedy = if (verdict.authorizeCallerInWindow) {
            CallerSignatureRemedyBuilder.build(
                context = this,
                store = callerTrustStore,
                reason = reason,
                callerPackage = CallingOriginResolver.systemAttestedPackageName(context.providerReq?.callingAppInfo),
                certDigests = certDigests,
                scope = lifecycleScope
            )
        } else {
            null
        }
        rejectAndFinish(reason, remedy, R.string.cred_reject_get_title)
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
                if (settled) {
                    // 本流程已收尾：租约外复制的私钥 / PRF 副本不能再留在内存里等 GC
                    releaseOwnedSecrets(passkeyData)
                    return@requestCredentialUserVerification
                }
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
                if (settled) {
                    releaseOwnedSecrets(passkeyData)
                    return@requestCredentialUserVerification
                }
                settled = true
                AppLog.w(TAG, "用户验证未通过，拒绝签发 Passkey 断言")
                // 私钥 / PRF 独占副本随本次拒签一并擦除（库内实例不受影响）
                releaseOwnedSecrets(passkeyData)
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
        lifecycleScope.safeLaunch("PasskeyAssertionActivity") {
            try {
                // ENG-20261028-05：用户验证挂起期间通道仍可能被关闭——签名与计数器落盘前复核，
                // 关闭即拒签（也不推进签名计数器，避免为一次不交付的断言留下副作用）。
                if (rejectsClosedChannel()) {
                    failAndFinish()
                    return@safeLaunch
                }
                // fail-closed 兜底：凡不可签发的验证结果（理论不可达）一律拒绝产出断言
                val flags = PasskeyAuthFlags.forAssertion(
                    verification = verification,
                    backupEligible = passkeyData.backupEligible,
                    backupState = passkeyData.backupState
                )
                if (flags == null) {
                    AppLog.e(TAG, "用户验证结果不可签发断言: verification=$verification")
                    failAndFinish()
                    return@safeLaunch
                }

                // ISSUE-P3-27 子项 2：先把签名计数器**原子递增并落库**，再据其返回值签名。
                // ISSUE-P1-590：只有计数器真正落盘才签发——落盘失败一律拒签（否则签名值已交 RP
                // 而库内计数器未提交，重启后即回退），并按「未命中」与「落盘失败」分型收尾。
                val signCount = when (val signResult = credentialSessionGate.withRequest {
                    vaultRepository.incrementPasskeySignCount(entryId)
                }) {
                    is KdbxResult.Success -> signResult.data
                    is KdbxResult.Failure -> {
                        if (signResult.code == KdbxError.PASSKEY_ENTRY_NOT_FOUND) {
                            AppLog.e(TAG, "签名计数器未能原子递增并落库，拒绝签发断言")
                            failAndFinish()
                        } else {
                            AppLog.e(
                                TAG,
                                "签名计数器已递增但未落盘（${signResult.error.javaClass.simpleName}），拒绝签发断言"
                            )
                            rejectAndFinish(
                                CredentialRejectionReason.PASSKEY_PERSISTENCE_FAILED,
                                titleRes = R.string.cred_reject_get_title
                            )
                        }
                        return@safeLaunch
                    }
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

                // ENG-20261028-05：签名与计数器落库都是挂起点，通道可能在期间被关闭——**交付前**
                // 再按本次值判定一次并紧贴 setResult（其后只剩票据窄锁）。计数器已经提交，
                // 但断言不交给已关闭的通道：RP 侧至多看到一次计数跳跃，不会拿到本应被拒的签名。
                if (rejectsClosedChannel()) {
                    failAndFinish()
                    return@safeLaunch
                }

                // ISSUE-P3-27 子项 2：计数器已落库后才回传 RP
                // ISSUE-P3-298 ⑥：回传成功 = 用户真实选用了本条目，记录上次使用时刻
                val resultIntent = Intent()
                val response = GetCredentialResponse(PublicKeyCredential(assertionJson))
                PendingIntentHandler.setGetCredentialResponse(resultIntent, response)
                // ENG-20261028-05：交付在**票据窄锁内**完成；通道开关在锁内最后再判一次，
                // 覆盖「等待窄锁期间用户关闭通道」这一挂起窗口（块内只允许同步代码，故判定与
                // setResult 之间不存在新的挂起点）。不交付时按取消收尾，绝不回传 RESULT_OK。
                var delivered = false
                val accepted = credentialSessionGate.complete {
                    if (rejectsClosedChannel()) return@complete
                    delivered = true
                    credentialLastUsedStore.record(entryId)
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
                if (!accepted || !delivered) failAndFinish()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(TAG, "Passkey 认证执行失败", t)
                failAndFinish()
            } finally {
                // 签名已完成（或失败）：本流程独占的私钥 / PRF 副本到此退出
                releaseOwnedSecrets(passkeyData)
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
