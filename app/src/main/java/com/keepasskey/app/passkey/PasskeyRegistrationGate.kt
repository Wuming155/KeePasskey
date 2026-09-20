package com.keepasskey.app.passkey

import com.keepasskey.app.security.CallerCertDigests

/**
 * 创建链路「非浏览器路径」的注册门禁（ISSUE-P2-02 原始判定 + ISSUE-P2-220 原因投影）。
 *
 * ## 判定链（顺序与 fail-closed 口径逐字沿袭 ISSUE-P2-02 实现）
 *
 * 1. 浏览器委派调用（origin 为网页来源）→ **放行**：`rp.id ↔ web origin` 的归属已由
 *    [DomainMatcher] 按点号边界强校验，无需 DAL 二次声明；
 * 2. 取不到系统背书的调用包名 → [CredentialRejectionReason.CALLER_UNKNOWN]；
 * 3. 用户显式开启设置页「跳过通行密钥站点归属校验」→ **放行**（削弱防线的显式取舍，风险由用户自担）；
 * 4. 取不到 `CallingAppInfo` 或签名摘要为空 → [CredentialRejectionReason.CALLER_CERT_UNREADABLE]
 *    （DAL 无从执行 ⇒ fail-closed）；
 * 5. 其余情形执行 DAL，并按其结论投影（[CredentialRejectionReason.fromDalResult]）。
 *
 * ## 返回值语义
 *
 * `null` = **放行**；非 null = 拒绝，且该值**就是**用户可见的拒绝原因。判定不读 Activity
 * 状态、不打日志：外部事实一律由参数注入，可被 JVM 单测穷举；日志与原因呈现留给调用方
 * （[PasskeyCreateActivity]）。
 */
object PasskeyRegistrationGate {

    /**
     * @param origin 本次由系统背书重新派生出的 origin
     * @param callerPackage 系统背书的调用方包名（取不到为 `null`）
     * @param skipDalVerification 用户是否已显式开启设置页「跳过通行密钥站点归属校验」
     * @param callingAppInfoPresent 是否取到系统背书的 `CallingAppInfo`
     * @param certDigests 调用方签名摘要集合（不可读时为 [CallerCertDigests.EMPTY]）
     * @param verifyDal DAL 远程资产声明校验；仅在前置条件齐备时被调用，且**只会**收到非空包名
     */
    suspend fun evaluate(
        origin: String,
        callerPackage: String?,
        skipDalVerification: Boolean,
        callingAppInfoPresent: Boolean,
        certDigests: CallerCertDigests,
        verifyDal: suspend (callingPackage: String) -> DigitalAssetLinksVerifier.DalResult
    ): CredentialRejectionReason? {
        if (CallingOriginResolver.isBrowserOrigin(origin)) return null
        if (callerPackage.isNullOrBlank()) return CredentialRejectionReason.CALLER_UNKNOWN
        if (skipDalVerification) return null
        if (!callingAppInfoPresent || certDigests.isEmpty) {
            return CredentialRejectionReason.CALLER_CERT_UNREADABLE
        }
        return CredentialRejectionReason.fromDalResult(verifyDal(callerPackage))
    }
}