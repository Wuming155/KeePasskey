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
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxEntry
import dagger.hilt.android.AndroidEntryPoint
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
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var fillVerifier: CredentialFillVerifier

    @Inject
    lateinit var autofillBlocklistStore: AutofillBlocklistStore

    /** ISSUE-P2-83：CM 通道调用方签名绑定存储（`android://` 维度回传前二次校验） */
    @Inject
    lateinit var callerTrustStore: CredentialManagerCallerTrustStore

    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ISSUE-P3-188：`onCreate` 收敛为「读请求 → 调用方核对 → 黑名单复核 → 派发」编排，
        // 各段职责下沉私有函数；判定**顺序**与 fail-closed 口径逐字不变
        val request = readFillRequest()
        if (rejectsAttestedCaller(request)) {
            failAndFinish()
            return
        }
        if (request.entryId.isBlank()) {
            AppLog.e(TAG, "缺少密码凭据 entryId")
            failAndFinish()
            return
        }
        // ISSUE-P0-02：与 Autofill 通道一致的 fail-closed 黑名单复核。
        // 候选组装侧已拦截一次；此处对回传路径再校验，杜绝「组装后被加入黑名单」的窗口被利用。
        if (request.expectedPackage.isNotBlank() &&
            autofillBlocklistStore.isBlocked(request.expectedPackage)
        ) {
            AppLog.w(TAG, "调用应用已列入自动填充黑名单，拒绝回传密码")
            failAndFinish()
            return
        }
        lifecycleScope.launch {
            try {
                performFill(request)
            } catch (t: Throwable) {
                AppLog.e(TAG, "密码填充失败", t)
                failAndFinish()
            }
        }
    }

    /** 填充请求入参（ISSUE-P3-188：仅把原 `onCreate` 局部量收拢，取值口径不变） */
    private data class FillRequest(
        val entryId: String,
        val expectedDomain: String,
        val expectedPackage: String,
        val providerReq: ProviderGetCredentialRequest?
    )

    private fun readFillRequest(): FillRequest = FillRequest(
        entryId = intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty(),
        expectedDomain = intent.getStringExtra(EXTRA_EXPECTED_DOMAIN).orEmpty(),
        expectedPackage = intent.getStringExtra(EXTRA_EXPECTED_PACKAGE).orEmpty(),
        providerReq = try {
            PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        } catch (_: Exception) {
            null
        }
    )

    /**
     * ISSUE-P3-111：检索系统在本窗口 PendingIntent 上注入的**原始请求**，取平台背书的
     * `CallingAppInfo` 包名，与组装阶段写入 extras 的预期包名交叉核对——与
     * [PasskeyAssertionActivity]（ISSUE-P2-72 起已具备）对称，补齐密码填充这条滞后链路。
     *
     * 第四轮复核对 P3-111 的定版提醒必须遵守：`retrieve*` 取不到（例如系统未以 fillIn
     * 方式注入）时**不得**据此拒绝——否则会把「能填充」变成「不能填充」。故仅在
     * **两者均可得且不一致**时 fail-closed；取不到即保持既有判定面不变。
     */
    private fun rejectsAttestedCaller(request: FillRequest): Boolean {
        val attestedPackage = CallingOriginResolver.systemAttestedPackageName(request.providerReq?.callingAppInfo)
        if (attestedPackage != null &&
            request.expectedPackage.isNotBlank() &&
            attestedPackage != request.expectedPackage.trim()
        ) {
            AppLog.e(TAG, "系统认证调用方与预期包名不一致，拒绝回填密码")
            return true
        }
        return false
    }

    /** 库解锁 → 条目定位 → 绑定校验 → 用户验证，全部通过后才回传明文 */
    private suspend fun performFill(request: FillRequest) {
        if (vaultRepository.isLocked()) {
            AppLog.w(TAG, "密码库处于锁定状态，无法填充密码")
            failAndFinish()
            return
        }

        val allEntries = vaultRepository.getKdbxEntries()
        val entry = allEntries.firstOrNull { it.id.toHexString() == request.entryId }
        if (entry == null) {
            AppLog.e(TAG, "未找到目标密码条目")
            failAndFinish()
            return
        }
        if (!passesCallerBinding(request, entry)) {
            AppLog.e(TAG, "条目与调用方不匹配，拒绝回传密码")
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
                R.string.cred_fill_confirm_biometric_subtitle,
                credentialLabel
            ),
            manualHint = getString(R.string.cred_fill_confirm_manual_hint, credentialLabel),
            confirmText = getString(R.string.autofill_confirm_ok),
            cancelText = getString(R.string.autofill_confirm_cancel),
            onVerified = { deliverPassword(entry) },
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
        val domainOk = request.expectedDomain.isNotBlank() && entry.url.isNotBlank() &&
                DomainMatcher.isDomainMatch(entry.url, request.expectedDomain)
        val packageDimensionAllowed = CredentialManagerPackageBindingGate.allowsPackageDimension(
            callingPackage = request.expectedPackage,
            certDigests = request.providerReq?.callingAppInfo
                ?.let { CallingOriginResolver.certDigests(it) }
                ?: CallerCertDigests.EMPTY,
            isTrusted = callerTrustStore::isTrusted,
            hasAnyBinding = callerTrustStore::hasAnyBindingFor
        )
        val packageOk = packageDimensionAllowed &&
                request.expectedPackage.isNotBlank() && entry.url.isNotBlank() &&
                DomainMatcher.isAndroidPackageMatch(entry.url, request.expectedPackage)
        return domainOk || packageOk
    }

    /** 验证通过后回传明文密码（唯一允许 `RESULT_OK` 的路径） */
    private fun deliverPassword(entry: KdbxEntry) {
        if (settled) return
        settled = true

        val username = entry.userName
        val password = entry.password?.readString().orEmpty()

        val response = GetCredentialResponse(PasswordCredential(username, password))
        val resultIntent = Intent()
        PendingIntentHandler.setGetCredentialResponse(resultIntent, response)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        private const val TAG = "PasswordFillActivity"
        const val EXTRA_ENTRY_ID = "com.keepasskey.extra.ENTRY_ID"
        const val EXTRA_EXPECTED_DOMAIN = "com.keepasskey.extra.EXPECTED_DOMAIN"
        const val EXTRA_EXPECTED_PACKAGE = "com.keepasskey.extra.EXPECTED_PACKAGE"
    }
}
