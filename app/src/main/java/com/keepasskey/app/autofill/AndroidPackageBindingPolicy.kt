package com.keepasskey.app.autofill

import com.keepasskey.app.security.CallerCertDigests

/**
 * `android://` 包名维度的放行判定（ISSUE-P2-46）。
 *
 * ## 缺陷形态（整改前）
 *
 * 条目以 `android://<包名>` 绑定真实应用、而该应用**未安装**时，任意应用只需以同 `applicationId`
 * 侧载即可命中并取得候选——包名维度的匹配是**纯字符串相等**，不比对调用方签名
 * （对照浏览器维度已有 `BrowserSigningFingerprints` 的包名 + 指纹二元组）。
 *
 * ## 整改口径（与报告定版一致）
 *
 * **首次绑定 + 调用方签名指纹校验**：只有调用方的「包名 + 签名摘要」经用户显式指认过一次
 * （`AutofillCallerTrustStore` 持久化，写入点为选择器 `AutofillPickerActivity`——那是用户
 * 明确指认调用方的唯一入口，且该页已展示包名 / 应用名 / 签名摘要），`android://` 维度才参与放行。
 *
 * **未获授权一律「不命中」**（而非降级为「弱候选」，也非「仍需二次确认」）：
 * 报告定版明确指出原 AC 的「未安装 → 弱候选」**仍会把凭据交给侧载应用**。
 *
 * ## 两条 fail-closed 判据
 *
 * 1. **摘要不可读即不放行**：`isTrusted(pkg, 空摘要集)` 会退化到「仅按包名」的降级键
 *    （`AutofillCallerTrustStore` 的 P1-24 既有取舍），而「只认包名」正是本项要消灭的形态，
 *    故包名维度**显式**要求摘要非空；
 * 2. **纯函数**：判定不依赖 Activity / Service，可被 JVM 单测穷举（见
 *    `AutofillCandidateRankerTest` 与 `AndroidPackageBindingPolicyTest`）。
 */
object AndroidPackageBindingPolicy {

    /**
     * @param callingPackage 系统背书的调用方包名
     * @param callingCertDigests 调用方签名摘要集合（`CallerCertDigests.EMPTY` = 不可读）
     * @param isTrusted 既有的「包名 + 签名」信任判定（生产传
     *   `AutofillCallerTrustStore::isTrusted` 的集合重载）
     */
    fun isPackageDimensionAuthorized(
        callingPackage: String,
        callingCertDigests: CallerCertDigests,
        isTrusted: (String, CallerCertDigests) -> Boolean
    ): Boolean {
        if (callingPackage.isBlank()) return false
        if (callingCertDigests.isEmpty) return false
        return isTrusted(callingPackage, callingCertDigests)
    }
}
