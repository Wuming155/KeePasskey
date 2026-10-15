package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.fieldref.FieldReferenceEngine.RefField
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 传统密码凭据在 Credential Manager 中的填充落地 Activity (对齐 P0-3 要求)。
 *
 * ISSUE-P0-02 (ZT-02) 整改：本 Activity 此前是「零用户验证」的直通管道——候选条目未挂
 * `BiometricPromptData`，本类亦无任何确认，密码库一旦处于解锁态，任意调起 Credential Manager
 * 的应用即可在用户零交互下取得明文密码（设备被短暂占有即等同全库可读）。
 * 现强制「先验证、后下发」：
 * 1. 进入即校验调用包名黑名单（与 Autofill 通道一致的 fail-closed）；
 * 2. 读取目标条目并二次校验「条目 ⇄ 调用方」绑定关系；
 * 3. 由 [CredentialFillVerifier] 判定所需验证等级后拉起系统级 BiometricPrompt，
 *    设备无可用认证器时退化为受保护窗口内（FLAG_SECURE + 反 overlay）的显式手动确认；
 * 4. 仅当验证结果被 [CredentialFillVerifier.isSatisfied] 判为通过时才回传明文密码，
 *    其余路径（未验证 / 失败 / 取消）一律 `RESULT_CANCELED`，绝不返回 `RESULT_OK`。
 *
 * 门控自持于 Activity 内而非挂在 entry 上的理由见 [CredentialFillVerifier] KDoc：
 * `androidx.credentials:1.6.0` 未向提供方暴露 `BiometricPromptResult` 读取入口，
 * 挂在 entry 上无法闭环校验，属「看起来已验证」的假门控。
 */
@AndroidEntryPoint
class PasswordFillActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var databaseSession: DatabaseSession

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var fillVerifier: CredentialFillVerifier

    @Inject
    lateinit var autofillBlocklistStore: AutofillBlocklistStore

    /** ISSUE-P2-83：CM 通道调用方签名绑定存储（`android://` 维度回传前二次校验） */
    @Inject
    lateinit var callerTrustStore: CredentialManagerCallerTrustStore

    /** 最终系统请求的可信 origin 解析使用与候选组装相同的浏览器白名单。 */
    @Inject
    lateinit var privilegedBrowserStore: PasskeyPrivilegedBrowserStore

    /** ISSUE-P3-298 ⑥：条目上次使用时刻记录（回传成功即真实使用点） */
    @Inject
    lateinit var credentialLastUsedStore: CredentialLastUsedStore

    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ISSUE-P3-188：`onCreate` 收敛为「读请求 → 调用方核对 → 黑名单复核 → 派发」编排，
        // 各段职责下沉私有函数；判定**顺序**与 fail-closed 口径逐字不变
        val request = readFillRequest()
        if (request == null) {
            AppLog.w(TAG, "凭据入口身份或最终系统请求不可验证，拒绝回填密码")
            failAndFinish()
            return
        }
        if (rejectsAttestedCaller(request)) {
            failAndFinish()
            return
        }
        if (request.context.entryId.isNullOrBlank()) {
            AppLog.e(TAG, "缺少密码凭据 entryId")
            failAndFinish()
            return
        }
        // ISSUE-P0-02：与 Autofill 通道一致的 fail-closed 黑名单复核。
        // 候选组装侧已拦截一次；此处对回传路径再校验，杜绝「组装后被加入黑名单」的窗口被利用。
        if (autofillBlocklistStore.isBlocked(request.context.expectedPackage)) {
            AppLog.w(TAG, "调用应用已列入自动填充黑名单，拒绝回传密码")
            failAndFinish()
            return
        }
        lifecycleScope.launch {
            try {
                performFill(request)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(TAG, "密码填充失败", t)
                failAndFinish()
            }
        }
    }

    /** 最终下发请求与候选身份（仅含非秘密元数据）。 */
    private data class FillRequest(
        val requestId: String,
        val context: CredentialEntryRequestContext,
        val providerReq: ProviderGetCredentialRequest,
        val attestedOrigin: String,
        val attestedPackage: String
    )

    private fun readFillRequest(): FillRequest? {
        val context = CredentialPendingIntents.requestContext(
            intent,
            CredentialEntryRequestType.PASSWORD_FILL
        ) ?: return null
        if (context.databaseId.isNullOrBlank() || context.entryId.isNullOrBlank() ||
            context.expectedOrigin.isBlank() || context.expectedPackage.isBlank() ||
            context.sessionTicket == null
        ) {
            return null
        }
        // Extras 是候选构建时的副本，只用于发现不一致；身份和绑定权威来自注册上下文。
        if (intent.getStringExtra(EXTRA_ENTRY_ID) != context.entryId ||
            intent.getStringExtra(EXTRA_EXPECTED_DOMAIN) != context.expectedDomain ||
            intent.getStringExtra(EXTRA_EXPECTED_PACKAGE) != context.expectedPackage
        ) {
            return null
        }
        val requestId = CredentialPendingIntents.requestIdFromIntent(intent) ?: return null
        val providerReq = try {
            PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        } catch (_: Exception) {
            null
        } ?: return null
        if (providerReq.credentialOptions.none { it.type == PasswordCredential.TYPE_PASSWORD_CREDENTIAL }) {
            return null
        }
        val callingAppInfo = providerReq.callingAppInfo ?: return null
        val attestedPackage = CallingOriginResolver.systemAttestedPackageName(callingAppInfo) ?: return null
        val attestedOrigin = CallingOriginResolver.resolveTrustedOrigin(
            callingAppInfo,
            privilegedBrowserStore.allowlistJson()
        )
        if (attestedOrigin.isBlank()) return null
        return FillRequest(
            requestId = requestId,
            context = context,
            providerReq = providerReq,
            attestedOrigin = attestedOrigin,
            attestedPackage = attestedPackage
        )
    }

    /**
     * ISSUE-P2-620：最终下发必须有系统注入请求，且系统背书 origin/package 与随机入口身份
     * 注册的候选上下文完全一致。缺失系统请求、非法 origin 或身份不符均取消并让系统重取候选。
     */
    private fun rejectsAttestedCaller(request: FillRequest): Boolean {
        if (!request.context.matchesPasswordFillCaller(request.attestedOrigin, request.attestedPackage)) {
            AppLog.e(TAG, "最终系统请求与候选调用方身份不一致，拒绝回填密码")
            return true
        }
        return false
    }

    /** 使用当前白名单重新派生最终系统请求身份，覆盖用户验证期间的白名单变更。 */
    private fun isCurrentAttestedCaller(request: FillRequest): Boolean {
        val callingAppInfo = request.providerReq.callingAppInfo ?: return false
        val currentPackage = CallingOriginResolver.systemAttestedPackageName(callingAppInfo) ?: return false
        val currentOrigin = CallingOriginResolver.resolveTrustedOrigin(
            callingAppInfo,
            privilegedBrowserStore.allowlistJson()
        )
        return request.context.matchesPasswordFillCaller(currentOrigin, currentPackage)
    }

    /** 库解锁 → 条目定位 → 绑定校验 → 用户验证，全部通过后才回传明文 */
    private suspend fun performFill(request: FillRequest) {
        val entry = findCurrentlyBoundEntry(request)
        if (entry == null) {
            AppLog.w(TAG, "会话、活动库、请求或目标条目已变化，拒绝回填密码")
            failAndFinish()
            return
        }

        // 先验证、后取密：密码明文在用户验证通过之前绝不物化。
        // 验证请求复用共享门控 [requestCredentialUserVerification]（ISSUE-P0-03 抽取），
        // 其结果已被 [CredentialFillVerifier] 裁决为满足要求，未通过路径一律走 [onRejected]。
        val credentialLabel = entry.title.ifBlank { entry.userName }.ifBlank { entry.url }
        requestCredentialUserVerification(
            biometricAuthManager = biometricAuthManager,
            fillVerifier = fillVerifier,
            title = getString(R.string.cred_fill_confirm_title),
            biometricSubtitle = getString(
                R.string.fill_confirm_biometric_subtitle,
                credentialLabel
            ),
            manualHint = getString(R.string.cred_fill_confirm_manual_hint, credentialLabel),
            confirmText = getString(R.string.autofill_confirm_ok),
            cancelText = getString(R.string.autofill_confirm_cancel),
            onVerified = { deliverPassword(request) },
            onRejected = { failAndFinish() }
        )
    }

    /**
     * H1 整改：回传明文密码前二次校验条目与预期调用方（域名/包名）的严格绑定关系，
     * 与候选组装逻辑（DomainMatcher）保持一致，杜绝候选与回传之间的窗口被利用。
     * P2-40 整改：包名维度必须是 `android://<包名>` 硬约束，`https://<host>` 等
     * Web 绑定条目不得经同形包名放行（Web 绑定只走域匹配）。
     * ISSUE-P2-83：包名维度除严格 `android://` 匹配外，还须通过调用方**签名绑定**门控
     * （与候选组装同一判据，杜绝组装与回传之间的窗口被利用）。
     */
    private fun passesCallerBinding(request: FillRequest, entry: KdbxEntry): Boolean {
        val context = request.context
        val callingAppInfo = request.providerReq.callingAppInfo ?: return false
        val domainOk = context.expectedDomain.isNotBlank() && entry.url.isNotBlank() &&
                DomainMatcher.isDomainMatch(entry.url, context.expectedDomain)
        val packageDimensionAllowed = CredentialManagerPackageBindingGate.allowsPackageDimension(
            callingPackage = context.expectedPackage,
            certDigests = CallingOriginResolver.certDigests(callingAppInfo),
            isTrusted = callerTrustStore::isTrusted,
            hasAnyBinding = callerTrustStore::hasAnyBindingFor
        )
        val packageOk = packageDimensionAllowed &&
                context.expectedPackage.isNotBlank() && entry.url.isNotBlank() &&
                DomainMatcher.isAndroidPackageMatch(entry.url, context.expectedPackage)
        return domainOk || packageOk
    }

    /**
     * 验证前后都从当前会话重读活动库和可用条目，不能沿用验证前捕获的 KdbxEntry。
     * 入口身份过期、进程重建、锁库、换库、黑名单变化或 URL/package 绑定变化均取消。
     */
    private suspend fun findCurrentlyBoundEntry(request: FillRequest): KdbxEntry? {
        if (vaultRepository.isLocked()) return null
        if (!isCurrentAttestedCaller(request)) return null
        val sessionTicket = request.context.sessionTicket ?: return null
        if (request.context.databaseId != sessionTicket.pathIdentifier ||
            !databaseSession.isCredentialTicketCurrent(sessionTicket)
        ) {
            return null
        }
        if (CredentialPendingIntents.requestIdFromIntent(intent) != request.requestId ||
            CredentialPendingIntents.requestContext(
                intent,
                CredentialEntryRequestType.PASSWORD_FILL
            ) != request.context
        ) {
            return null
        }
        if (autofillBlocklistStore.isBlocked(request.context.expectedPackage)) return null

        // ISSUE-P2-341：交付执行侧同样只认可用条目，排除回收站子树。
        val entryId = request.context.entryId ?: return null
        val entry = vaultRepository.getUsableKdbxEntries()
            .firstOrNull { it.id.toHexString() == entryId }
            ?: return null
        if (vaultRepository.isLocked() || !databaseSession.isCredentialTicketCurrent(sessionTicket)) return null
        return entry.takeIf { passesCallerBinding(request, it) }
    }

    /**
     * 验证通过后回传明文密码（唯一允许 `RESULT_OK` 的路径）。
     *
     * ISSUE-P3-371 ①：用户名 / 密码的 {REF:} 展开是 suspend 调用（经单点收口的
     * `resolveFieldReferences`），而本回调位于验证回调的非挂起上下文 ⇒ 展开段在
     * [lifecycleScope] 内完成；**先验证、后取密**次序不变（展开仍在 onVerified 之后）。
     * 展开 / 组装任一异常按 fail-closed 收尾（绝不回传半截凭据）。
     */
    private fun deliverPassword(request: FillRequest) {
        if (settled) return
        settled = true

        lifecycleScope.launch {
            try {
                // ISSUE-P2-620：验证完成后重新确认请求、库、会话和条目，覆盖用户交互期间的状态变化。
                val entryBeforeExpansion = findCurrentlyBoundEntry(request)
                if (entryBeforeExpansion == null) {
                    AppLog.w(TAG, "验证后凭据请求或目标状态已变化，拒绝回填密码")
                    failAndFinish()
                    return@launch
                }
                // ISSUE-P3-371 ①：CM 回传两通道补 {REF:} 展开（与 Autofill DatasetBuilders
                // 消费点同口径）。用户名走 USER_NAME 面（非口令通道，{REF:P@…} 掩码输出）；
                // 密码走 PASSWORD 面（口令消费点按 KDBX 语义展开）。条目 / 会话不可用时
                // resolveFieldReferences 返回 null ⇒ 回退原文，与既有消费点兜底一致。
                val entryIdHex = entryBeforeExpansion.id.toHexString()
                val rawUsername = entryBeforeExpansion.userName
                val username = vaultRepository.resolveFieldReferences(
                    entryIdHex, rawUsername, RefField.USER_NAME
                ) ?: rawUsername
                // 用户名 REF 展开可能挂起；取密前用当前库重新定位并复核同一条目及调用方绑定。
                val entryBeforePassword = findCurrentlyBoundEntry(request)
                if (entryBeforePassword == null || entryBeforePassword.id.toHexString() != entryIdHex) {
                    AppLog.w(TAG, "取密前凭据请求或目标状态已变化，拒绝回填密码")
                    failAndFinish()
                    return@launch
                }
                val rawPassword = entryBeforePassword.password?.readString().orEmpty()
                val password = vaultRepository.resolveFieldReferences(
                    entryIdHex, rawPassword, RefField.PASSWORD
                ) ?: rawPassword

                // 密码解析可能挂起；结果提交前再次确认请求与绑定仍有效。
                val entryBeforeDelivery = findCurrentlyBoundEntry(request)
                if (entryBeforeDelivery == null || entryBeforeDelivery.id.toHexString() != entryIdHex) {
                    AppLog.w(TAG, "回传前凭据请求或目标状态已变化，拒绝回填密码")
                    failAndFinish()
                    return@launch
                }

                val sessionTicket = request.context.sessionTicket
                if (sessionTicket == null || vaultRepository.isLocked() ||
                    !databaseSession.isCredentialTicketCurrent(sessionTicket) ||
                    CredentialPendingIntents.requestContext(
                        intent,
                        CredentialEntryRequestType.PASSWORD_FILL
                    ) != request.context ||
                    autofillBlocklistStore.isBlocked(request.context.expectedPackage) ||
                    !isCurrentAttestedCaller(request)
                ) {
                    AppLog.w(TAG, "最终提交前会话代次变化，拒绝回填密码")
                    failAndFinish()
                    return@launch
                }

                // ISSUE-P3-298 ⑥：进入唯一成功回传路径后记录本次真实使用。
                credentialLastUsedStore.record(entryIdHex)

                val response = GetCredentialResponse(PasswordCredential(username, password))
                val resultIntent = Intent()
                PendingIntentHandler.setGetCredentialResponse(resultIntent, response)
                setResult(RESULT_OK, resultIntent)
                finish()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(TAG, "密码回传组装失败", t)
                failAndFinish()
            }
        }
    }

    companion object {
        private const val TAG = "PasswordFillActivity"
        const val EXTRA_ENTRY_ID = "com.keepasskey.extra.ENTRY_ID"
        const val EXTRA_EXPECTED_DOMAIN = "com.keepasskey.extra.EXPECTED_DOMAIN"
        const val EXTRA_EXPECTED_PACKAGE = "com.keepasskey.extra.EXPECTED_PACKAGE"
    }
}
