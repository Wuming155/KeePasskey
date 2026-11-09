package com.keepasskey.app.passkey

import com.keepasskey.app.security.CallerCertDigests

/**
 * 「登记此应用签名」补救的**资格判定**（纯函数，`ENG-20261028-06`）。
 *
 * 与 [BrowserRemedyBuilder] 同型：拒绝页只在**确有用户可执行解法**时给出动作。抽成纯函数
 * 是因为资格涉及「原因 / 包名 / 摘要 / 是否已绑定」四个外部事实的排列，宿主 JVM 可穷举；
 * 真正的写入与文案留在 [CallerSignatureRemedyBuilder]。
 *
 * ## 四条 fail-closed 前提
 *
 * 1. 原因对应 [CredentialRejectionAction.AUTHORIZE_CALLER_SIGNATURE]；
 * 2. 包名非空（必须是**系统背书**的包名，调用方负责只传该值）；
 * 3. 摘要可读——写「仅包名」降级键正是本项要消灭的形态；
 * 4. 该包名**尚无任何绑定**——已绑定而签名不符时就地重绑等于让顶替者自证身份
 *    （与 [PasskeyRpVerdict.authorizeCallerInWindow] 同一判据，两处必须一致）。
 */
internal object CallerSignatureRemedyPolicy {

    /**
     * @param certDigests 本次系统背书的调用方签名摘要集合
     * @param hasAnyBinding 该包名是否已有任一签名绑定（生产传
     *   [CredentialManagerCallerTrustStore.hasAnyBindingFor]）
     */
    fun eligible(
        reason: CredentialRejectionReason,
        callerPackage: String?,
        certDigests: CallerCertDigests,
        hasAnyBinding: Boolean
    ): Boolean =
        CredentialRejectionAction.forReason(reason) == CredentialRejectionAction.AUTHORIZE_CALLER_SIGNATURE &&
            !callerPackage.isNullOrBlank() &&
            !certDigests.isEmpty &&
            !hasAnyBinding
}
