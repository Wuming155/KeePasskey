package com.keepasskey.app.passkey

import androidx.annotation.StringRes
import com.keepasskey.app.R

/**
 * 凭据链路 fail-closed 拒绝原因（创建：`ISSUE-P2-220`；断言：`ISSUE-P2-530`）。
 *
 * ## 缺陷形态
 *
 * 整改前创建链路的各拒绝分支统一走 `failAndFinish()`：只回传 `RESULT_CANCELED` 后立即
 * `finish()`，**任何界面都未呈现**。用户视角是「点了继续就断」——无法区分「功能坏了」
 * 与「被安全门控拒绝」，只能反复重试。断言链路（`PasskeyAssertionActivity`）的
 * 「调用方归属不匹配」分支原为同款静默收尾，`ISSUE-P2-530` 起一并接入本页。
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

    /**
     * `ISSUE-P2-530`（断言链路）：调用方**未持系统背书的 web origin**（origin 退化为
     * `apk-key-hash`）⇒ 由本应用签出的断言其 origin 无法被依赖方接受，故在签发前拒绝。
     *
     * 与 `DAL_*` 同因：非白名单浏览器走的是普通应用分支。区别是它出现在**使用**链路，
     * 且候选已在列表侧放行（`ISSUE-P2-530` 起列表按请求 rp.id 检索），故拒绝页必须给出
     * 「加入特权名单」的就地补救，否则用户看到条目却点不动、且无路可走。
     */
    CALLER_BROWSER_NOT_TRUSTED(R.string.passkey_reject_caller_browser_not_trusted),

    /**
     * `ISSUE-P1-590`：通行密钥**未能写入密码库**（保存失败 / 漂移拒绝 / 只读）——创建与断言
     * 两条链路共用。此前保存失败只写日志仍回传成功，RP 随即登记了一把本机未持久化的私钥；
     * 现在落盘失败一律拒签 / 拒绝回传成功，并给出本页可恢复提示。
     */
    PASSKEY_PERSISTENCE_FAILED(R.string.passkey_reject_persistence_failed),

    /**
     * `ENG-20261028-05`：用户验证 / 落库期间，用户在设置中关闭了凭据管理器通道
     * （或「通行密钥支持」）——本次注册不再回传响应。与请求本身无关，故单列原因，
     * 不复用「请求不符合规范」一类会把责任推给调用方的文案。
     */
    CHANNEL_DISABLED(R.string.passkey_reject_channel_disabled),

    /**
     * `ENG-20261028-06`（断言链路）：原生调用**从未**为该包名登记签名绑定。
     *
     * 包名相同不等于调用方已获授权：本地没有可比对的签名参照，故本次不签发。
     * 用户可在受保护窗口内**显式授权**（把本次系统背书的包名 + 签名摘要写入信任存储），
     * 授权只影响**下一次**发起（与 [CredentialRejectionAction] 的「就地」语义一致）。
     * 不提供「跳过」入口：那等于让任意同包名应用一步取得签发权。
     */
    CALLER_SIGNATURE_UNBOUND(R.string.passkey_reject_caller_signature_unbound),

    /**
     * `ENG-20261028-06`（断言链路）：该包名**已登记**签名绑定，但本次调用的签名不一致
     * （同 `applicationId` 顶替 / 换签名）。
     *
     * 一律拒绝且**不提供**就地重绑——就地补绑定等于让顶替者自证身份；防止应用被冒充。
     */
    CALLER_SIGNATURE_MISMATCH(R.string.passkey_reject_caller_signature_mismatch),

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