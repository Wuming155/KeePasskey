package com.keepasskey.app.autofill

import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.security.BrowserSigningFingerprints

/**
 * webDomain 归属判定（ISSUE-P2-07 / ZT-12 / ISSUE-P1-11）。
 *
 * 传统自动填充的 [android.service.autofill.AssistStructure.ViewNode.webDomain] 由**调用方应用**
 * 提供（WebView 宿主可控），并非系统背书的站点归属——任意非浏览器应用都可伪造一个 webDomain
 * 冒领该站点的凭据。因此本策略要求：仅**「受信浏览器包名 + 已取证签名证书指纹」二元组**匹配的调用方，
 * 或经 DAL（Digital Asset Links）证明与 webDomain 存在强归属关系的调用方，方可使用该域参与匹配；
 * 其余一律 [WebDomainAttribution.REJECTED]（fail-closed，不下发该域候选）。
 *
 * ISSUE-P1-11：浏览器分支**不再仅按包名信任**。Android 不阻拦侧载占用未安装浏览器包名的 APK，
 * 仅按包名匹配可被绕过导致跨应用凭据泄露；现要求调用方签名证书指纹命中
 * [BrowserSigningFingerprints]（只收录已取证指纹），未取证浏览器 / 指纹不匹配一律降级 DAL。
 *
 * 纯 Kotlin 判定内核（仅复用 [DomainMatcher] 的主机归一化，零 Android 框架依赖），JVM 可测。
 */
enum class WebDomainAttribution {
    /** 受信任浏览器委派：包名与已取证签名指纹均匹配，其 webDomain 可信 */
    BROWSER_DELEGATED,

    /** 非浏览器应用，但 webDomain 站点经 DAL 声明显式授权该应用 */
    DAL_VERIFIED,

    /** 无法验证归属：拒绝使用该 webDomain（fail-closed） */
    REJECTED
}

object AutofillWebDomainPolicy {

    /**
     * 主机名形态校验：至少两段、每段字母/数字开头结尾、段内可含连字符。
     * 拒绝空白、单标签、纯点号等非法输入（额外的 fail-closed 防线）。
     */
    private val HOST_PATTERN = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")

    /**
     * 归一化 webDomain：剥离 scheme / 路径 / 端口，小写化；非法输入返回 null。
     */
    fun normalizeDomain(rawWebDomain: String?): String? {
        val host = DomainMatcher.extractDomain(rawWebDomain.orEmpty())
        return host.takeIf { HOST_PATTERN.matches(it) }
    }

    /**
     * 调用方是否为受信任浏览器：**包名 + 已取证签名证书指纹**二元组均匹配（ISSUE-P1-11）。
     * 指纹来源与核实纪律见 [BrowserSigningFingerprints]。
     */
    fun isTrustedBrowser(callingPackage: String, certSha256Hex: String?): Boolean =
        BrowserSigningFingerprints.isTrusted(callingPackage, certSha256Hex)

    /**
     * 裁决 webDomain 归属。
     *
     * @param callingPackage 系统背书的调用方包名（AssistStructure.activityComponent）
     * @param rawWebDomain 结构树中的原始 webDomain（调用方可控，不可直接信任）
     * @param certSha256Hex 调用方签名证书 SHA-256（大写无冒号）；无法取得时传 null（fail-closed）
     * @param dalVerified 非浏览器调用方是否已通过 DAL 归属校验
     */
    fun attribute(
        callingPackage: String,
        rawWebDomain: String?,
        certSha256Hex: String?,
        dalVerified: Boolean
    ): WebDomainAttribution {
        if (normalizeDomain(rawWebDomain) == null) return WebDomainAttribution.REJECTED
        if (isTrustedBrowser(callingPackage, certSha256Hex)) return WebDomainAttribution.BROWSER_DELEGATED
        if (dalVerified) return WebDomainAttribution.DAL_VERIFIED
        return WebDomainAttribution.REJECTED
    }
}
