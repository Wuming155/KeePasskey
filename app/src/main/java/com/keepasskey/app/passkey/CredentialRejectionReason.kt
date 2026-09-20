package com.keepasskey.app.passkey

import androidx.annotation.StringRes
import com.keepasskey.app.R

/**
 * 凭据**创建链路** fail-closed 拒绝原因（ISSUE-P2-220）。
 *
 * ## 缺陷形态
 *
 * 整改前创建链路的各拒绝分支统一走 `failAndFinish()`：只回传 `RESULT_CANCELED` 后立即
 * `finish()`，**任何界面都未呈现**。用户视角是「点了继续就断」——无法区分「功能坏了」
 * 与「被安全门控拒绝」，只能反复重试。
 *
 * ## 文案口径（ISSUE-P1-10 沿用）
 *
 * 每个原因只映射到一条**预定义、无插值**的字符串资源：拒绝文案**绝不**携带 rpId、
 * 调用包名、域名、凭据 id 等敏感标识（这些只允许经 [com.keepasskey.core.log.AppLog] 脱敏记录）。
 *
 * ## 与回传契约的关系
 *
 * 呈现原因**不改变**对系统的回传语义：用户确认后仍是 `RESULT_CANCELED`，浏览器 / 调用方
 * 照旧收到创建失败（绝不因「给了用户一个界面」而谎报成功）。
 */
enum class CredentialRejectionReason(@StringRes val messageRes: Int) {
    /** 系统未注入创建请求（条目 PendingIntent 非 `FLAG_MUTABLE` 等），无任何可信输入 */
    MISSING_REQUEST(R.string.passkey_reject_missing_request),

    /** 请求缺 rpId / userName 等注册核心参数 */
    MISSING_PARAMETERS(R.string.passkey_error_missing_register_params),

    /** 锁定态复核仍未解锁（用户在解锁页放弃 / 解锁后再次上锁） */
    VAULT_LOCKED(R.string.cred_error_vault_locked),

    /** 取不到系统背书的调用方包名 */
    CALLER_UNKNOWN(R.string.passkey_error_caller_unknown),

    /** 取不到调用方签名摘要，DAL 校验无从执行 */
    CALLER_CERT_UNREADABLE(R.string.passkey_reject_caller_cert_unreadable),

    /** DAL 远程资产声明未通过（站点无对本应用的授权声明，或声明格式错误） */
    DAL_UNVERIFIED(R.string.passkey_reject_dal_unverified),

    /** DAL 校验因网络不可用而无法完成（fail-closed） */
    DAL_NETWORK_UNAVAILABLE(R.string.passkey_reject_dal_network_unavailable),

    /** 命中 `excludeCredentials`：库中已存在同一凭据，规范要求拒绝重复创建 */
    CREDENTIAL_ALREADY_EXISTS(R.string.passkey_reject_credential_exists),

    /** 请求违反 WebAuthn 规范（如注册携带 `evalByCredential`），拒绝 —— 外部输入可达 */
    REQUEST_INVALID(R.string.passkey_reject_request_invalid),

    /** 创建过程内部异常（fail-closed 兜底） */
    INTERNAL_ERROR(R.string.cred_error_unknown);

    companion object {

        /**
         * DAL 校验结论 → 拒绝原因（**纯函数**，JVM 单测穷举）。
         * `VERIFIED` 无需拒绝，返回 `null`。
         */
        fun fromDalResult(dalResult: DigitalAssetLinksVerifier.DalResult): CredentialRejectionReason? =
            when (dalResult) {
                DigitalAssetLinksVerifier.DalResult.VERIFIED -> null
                DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED -> DAL_UNVERIFIED
                DigitalAssetLinksVerifier.DalResult.NETWORK_UNAVAILABLE -> DAL_NETWORK_UNAVAILABLE
            }
    }
}