package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.result.KdbxResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 传统密码凭据在 Credential Manager 中的保存落地 Activity (对齐 P0-3 要求)。
 *
 * 库锁定时在**同一受保护窗口内**呈现解锁页（[CredentialUnlockPresenter]）——此前直接
 * `failAndFinish()`，用户点选系统「保存密码」后静默失败（与 Passkey 注册同一缺陷形态）。
 */
@AndroidEntryPoint
class PasswordSaveActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var callerTrustStore: CredentialManagerCallerTrustStore

    @Inject
    lateinit var unlockPresenter: CredentialUnlockPresenter

    @Inject
    lateinit var saveRequestLedger: SaveRequestLedger

    /**
     * ENG-20261028-05：保存边界的通道开关来源。CM 创建入口按 CM 通道总开关复核；
     * 非 CM 入口（`providerReq == null`）按 **Autofill 通道开关**复核——该入口现已无生产写入方
     * （锁定态保存改由 `AutofillLockedSaveActivity` 经受控载体承接），保留判定仅为
     * 「将来若复用本页则仍受开关约束」（见 [rejectsClosedChannel]）。
     */
    @Inject
    lateinit var settingsStore: ExtendedSettingsStore

    /**
     * ISSUE-P2-606：等待解锁阶段由本 Activity **独占**的口令数组。
     * 构造后立即登记于此；任何早退 / 取消 / 销毁路径经 [releasePendingPassword] 擦除；
     * 解锁成功后**转移**给保存任务并从本字段注销（避免双擦）。
     */
    private var pendingPasswordChars: CharArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val providerReq: ProviderCreateCredentialRequest? = try {
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        } catch (_: Exception) {
            null
        }
        val callingReq = providerReq?.callingRequest
        if (providerReq != null &&
            !credentialSessionGate.bind(intent, CredentialEntryRequestType.PASSWORD_SAVE, allowUnlock = true)) {
            failAndFinish()
            return
        }
        // ENG-20261028-21：口令**只**来自系统 Credential Manager 的 CreatePasswordRequest。
        // 旧「传统自动填充保存」的 EXTRA_PASSWORD / EXTRA_USERNAME 通道已随锁定态保存改走
        // 受控载荷载体（`LockedSavePayloadStore` + `AutofillLockedSaveActivity`）而失效，
        // 全仓再无写入方；此处不再回退读取 extras，避免留下「可经 intent 交付明文」的通路。
        val username = when (val req = callingReq) {
            is CreatePasswordRequest -> req.id
            else -> ""
        }
        val password: String? = (callingReq as? CreatePasswordRequest)?.password
        val targetPackage = providerReq?.callingAppInfo?.packageName
            ?: intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        // H1 同源整改（审计观察 1）：web 域只信任本应用 Provider 服务填充的 EXTRA_WEB_DOMAIN，
        // 绝不回退读取调用方可控的 candidateQueryData
        val webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)
        if (providerReq != null && !credentialSessionGate.matchesCaller(webDomain.orEmpty(), targetPackage)) {
            failAndFinish()
            return
        }

        if (password.isNullOrBlank()) {
            AppLog.e(TAG, "缺少待保存的密码数据")
            failAndFinish()
            return
        }

        // ISSUE-P2-603：传统自动填充保存路径必须携带本应用下发的**唯一请求令牌**——
        // 缺失即 fail-closed（该 Activity 非导出，正常只由本应用 PendingIntent 拉起）。
        val requestToken = intent.getStringExtra(EXTRA_REQUEST_TOKEN)
        if (providerReq == null && requestToken.isNullOrBlank()) {
            AppLog.e(TAG, "自动填充保存请求缺少请求令牌，拒绝处理")
            failAndFinish()
            return
        }
        // ISSUE-P2-603：单次消费——同令牌已消费（成功 / 取消 / 过期）则拒绝重复处理
        if (!requestToken.isNullOrBlank() && saveRequestLedger.isConsumed(requestToken)) {
            AppLog.w(TAG, "保存请求令牌已消费，拒绝重复处理")
            failAndFinish()
            return
        }

        // ENG-20261028-05：通道开关在**解锁前**复核——通道已关闭时不再要求用户解锁，
        // 也不物化任何口令副本。解锁后的落库边界另有同判据复核（见 [startSave]）。
        if (rejectsClosedChannel(providerReq)) {
            failAndFinish()
            return
        }

        // M4 整改：平台 String 到 CharArray 的唯一副本即取即用；擦除必须发生在协程内部
        // （lifecycleScope.launch 为异步，外层 finally 会先于保存完成执行）
        val passwordChars = password.toCharArray()
        // ISSUE-P2-606：数组自构造起即登记为本 Activity 在等待解锁阶段独占的载体
        pendingPasswordChars = passwordChars
        // 锁库时先在同一窗口内解锁，解锁成功后继续保存流程
        unlockPresenter.requireUnlocked(this) {
            // ISSUE-P2-606：解锁成功回调可能在销毁后抵达——等待副本已释放（null）则不再推进
            val owned = pendingPasswordChars ?: return@requireUnlocked
            // 所有权转移给保存任务（其 finally 负责擦除），本 Activity 注销以避免双擦
            pendingPasswordChars = null
            startSave(
                providerReq = providerReq,
                username = username,
                passwordChars = owned,
                targetPackage = targetPackage,
                webDomain = webDomain,
                requestToken = requestToken
            )
        }
    }

    /**
     * ISSUE-P2-606：幂等释放本 Activity 仍持有的等待副本。
     * 仅当 [pendingPasswordChars] 非空（所有权尚未转移）时擦除；已转移（null）则不动任务副本。
     */
    private fun releasePendingPassword() {
        pendingPasswordChars?.fill('0')
        pendingPasswordChars = null
    }

    override fun onDestroy() {
        releasePendingPassword()
        super.onDestroy()
    }

    /** ISSUE-P2-603：登记请求令牌为已消费（成功 / 明确失败 / 异常路径）。 */
    private fun consumeRequest(requestToken: String?) {
        if (!requestToken.isNullOrBlank()) saveRequestLedger.markConsumed(requestToken)
    }

    /**
     * ENG-20261028-05：保存边界的通道开关复核。
     *
     * [providerReq] 非空 = 系统 Credential Manager 的创建入口，只受 **CM 通道总开关**约束
     * （「通行密钥支持」开关不得拦截密码保存）。为空 = 非 CM 入口（旧「传统自动填充保存」的
     * extras 通道已随 `ENG-20261028-21` 改为受控载体而失效、本页不再读取明文 extras），
     * 按 **Autofill 通道开关**复核，保留为「将来若复用本页仍受开关约束」的兜底。
     */
    private fun rejectsClosedChannel(providerReq: ProviderCreateCredentialRequest?): Boolean {
        if (providerReq != null) {
            if (CredentialDeliveryChannelGate.allowsPasswordDelivery(settingsStore.isCredentialProviderEnabled())) {
                return false
            }
            AppLog.w(TAG, "凭据管理器通道已在设置中关闭，拒绝保存密码")
            return true
        }
        if (settingsStore.isAutofillServiceEnabled()) return false
        AppLog.w(TAG, "自动填充通道已在设置中关闭，拒绝保存密码")
        return true
    }

    /** 解锁后（或库本就解锁）的保存主流程。任何结果路径都会清零 [passwordChars]。 */
    private fun startSave(
        providerReq: ProviderCreateCredentialRequest?,
        username: String,
        passwordChars: CharArray,
        targetPackage: String,
        webDomain: String?,
        requestToken: String?
    ) {
        lifecycleScope.launch {
            try {
                if (vaultRepository.isLocked()) {
                    AppLog.w(TAG, "密码库仍未解锁，无法保存密码凭据")
                    consumeRequest(requestToken)
                    failAndFinish()
                    return@launch
                }
                // ENG-20261028-05：解锁 / 等待期间通道仍可能被关闭——落库前按本次值复核。
                if (rejectsClosedChannel(providerReq)) {
                    consumeRequest(requestToken)
                    failAndFinish()
                    return@launch
                }

                val boundPackage = targetPackage.ifBlank { callingPackage ?: packageName }
                val saveResult = credentialSessionGate.withRequest { vaultRepository.saveAutofillCredential(
                    packageName = boundPackage,
                    webDomain = webDomain,
                    username = username,
                    passwordChars = passwordChars
                ) }

                // ISSUE-P2-84：保存失败**不得**回传成功结果（原实现丢弃 KdbxResult 后无条件 RESULT_OK，
                // 落盘失败被谎报为保存成功，系统据此可能不再提示保存——用户口令静默丢失）。
                if (saveResult is KdbxResult.Failure) {
                    AppLog.e(TAG, "保存密码凭据失败: ${saveResult.error.javaClass.simpleName}")
                    consumeRequest(requestToken)
                    failAndFinish()
                    return@launch
                }

                // ISSUE-P2-83：保存流程是「用户在受保护窗口内把凭据显式交给该调用方」的两个入口之一
                // （另一个是 Passkey 注册），故在**确认入库成功后**写入 CM 通道的调用方绑定。
                val callerDigests = providerReq?.callingAppInfo
                    ?.let { CallingOriginResolver.certDigests(it) }
                    ?: CallerCertDigests.EMPTY
                if (callerDigests.isEmpty) {
                    AppLog.w(TAG, "调用方签名摘要不可读，CM 通道保持未绑定（fail-closed）")
                } else {
                    callerTrustStore.trust(boundPackage, callerDigests.primary)
                }

                if (providerReq != null) {
                    val response = CreatePasswordResponse()
                    val resultIntent = Intent()
                    PendingIntentHandler.setCreateCredentialResponse(resultIntent, response)
                    if (!credentialSessionGate.complete { setResult(RESULT_OK, resultIntent); finish() }) {
                        failAndFinish()
                    }
                } else {
                    setResult(RESULT_OK)
                }
                // ISSUE-P2-603：仅在实际落盘（成功路径）后才登记消费
                consumeRequest(requestToken)
                if (providerReq == null) finish()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(TAG, "保存密码凭据失败", t)
                consumeRequest(requestToken)
                failAndFinish()
            } finally {
                passwordChars.fill('0')
            }
        }
    }

    companion object {
        private const val TAG = "PasswordSaveActivity"
        const val EXTRA_PACKAGE_NAME = "com.keepasskey.extra.PACKAGE_NAME"
        const val EXTRA_WEB_DOMAIN = "com.keepasskey.extra.WEB_DOMAIN"

        /** ISSUE-P2-603：锁定态自动填充保存请求的唯一令牌（与 Intent.data 同源）。 */
        const val EXTRA_REQUEST_TOKEN = "com.keepasskey.extra.REQUEST_TOKEN"
    }
}
