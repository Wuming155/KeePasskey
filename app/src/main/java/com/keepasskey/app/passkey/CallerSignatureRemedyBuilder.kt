package com.keepasskey.app.passkey

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import com.keepasskey.app.R
import com.keepasskey.app.security.CallerCertDigests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 原生调用方**签名登记**的就地补救构建器（`ENG-20261028-06`）。
 *
 * ## 为什么需要
 *
 * 原生调用（`android:apk-key-hash:` origin）没有可推导的 RP，签发授权只能来自
 * 「系统背书的包名 + 签名摘要」这一条参照（见 [PasskeyRpAuthorization]）。若该包名从未登记，
 * 本次必须拒签；但若**只**拒签不给入口，存量条目在 CM 通道就无路可走——那是功能回归。
 * 故拒绝页提供「登记此应用的签名」，把**本次系统背书的摘要**写入
 * [CredentialManagerCallerTrustStore]，与浏览器补救同型：窗口内闭环、无跳转、
 * 只影响**下一次**发起。
 *
 * ## 为什么文案不点名调用方
 *
 * 浏览器补救能点名，是因为浏览器经 `<queries>` 的 https VIEW 签名可见、展示名取自系统。
 * 任意原生调用方**不在**包可见面内（本仓刻意不使用 `QUERY_ALL_PACKAGES`），读不到展示名；
 * 退回包名又会把包名写进拒绝文案，违反 ISSUE-P1-10。故本动作的说明与反馈一律**无插值**。
 *
 * ## 四个 fail-closed 前提（任一不满足即返回 `null`，不给假入口）
 *
 * 1. 原因本身带该动作（[CredentialRejectionAction.forReason] 必须是
 *    [CredentialRejectionAction.AUTHORIZE_CALLER_SIGNATURE]）；
 * 2. 调用包名非空——必须是**系统背书**的包名（`CallingOriginResolver.systemAttestedPackageName`），
 *    取不到即无从登记；
 * 3. 本次摘要可读（[CallerCertDigests.isEmpty] 为 false）——写「仅包名」降级键正是本项要消灭的形态；
 * 4. 该包名**确实尚未绑定**（`hasAnyBindingFor` 为 false）。已绑定而签名不符时本动作不得出现：
 *    就地重绑等于让顶替者自证身份（[PasskeyRpVerdict.authorizeCallerInWindow] 同口径）。
 *
 * ## 与「用户身份验证」的分工
 *
 * 本动作**不是**用户身份验证的替代：`BiometricPrompt` 证明「是本机用户」，不证明
 * 「这个包名的签名属于该用户想授权的应用」。两者各答一个问题，缺一不可。
 */
internal object CallerSignatureRemedyBuilder {

    /**
     * @param callerPackage 系统背书的调用方包名
     * @param certDigests 本次系统背书的调用方签名摘要集合
     * @param scope 宿主生命周期作用域，登记协程在其上发起
     * @return 可渲染的补救描述；不满足前提时为 `null`
     */
    fun build(
        context: Context,
        store: CredentialManagerCallerTrustStore,
        reason: CredentialRejectionReason,
        callerPackage: String?,
        certDigests: CallerCertDigests,
        scope: LifecycleCoroutineScope
    ): CredentialRejectionRemedy? {
        val pkg = callerPackage?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val primary = certDigests.primary ?: return null
        // 资格判定与写入前提同一真相源（[CallerSignatureRemedyPolicy]），不得各写一份
        if (!CallerSignatureRemedyPolicy.eligible(reason, pkg, certDigests, store.hasAnyBindingFor(pkg))) {
            return null
        }

        return CredentialRejectionRemedy(
            description = context.getString(R.string.cred_reject_caller_unbound),
            actionLabel = context.getString(R.string.cred_reject_action_authorize_caller),
            successMessage = context.getString(R.string.cred_reject_caller_authorized),
            failureMessage = context.getString(R.string.cred_reject_caller_authorize_failed),
            // 写入属偏好面：下沉 Default，完成后回调（默认主线程）更新页面
            perform = { onOutcome ->
                scope.launch {
                    onOutcome(withContext(Dispatchers.Default) { store.trust(pkg, primary) })
                }
            }
        )
    }
}
