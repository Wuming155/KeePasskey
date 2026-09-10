package com.keepasskey.app.autofill

import com.keepasskey.app.passkey.DomainMatcher

/**
 * webDomain 归属判定（ISSUE-P2-07 / ZT-12）。
 *
 * 传统自动填充的 [android.service.autofill.AssistStructure.ViewNode.webDomain] 由**调用方应用**
 * 提供（WebView 宿主可控），并非系统背书的站点归属——任意非浏览器应用都可伪造一个 webDomain
 * 冒领该站点的凭据。因此本策略要求：仅**受信任浏览器包名白名单**内的调用方，
 * 或经 DAL（Digital Asset Links）证明与 webDomain 存在强归属关系的调用方，方可使用该域参与匹配；
 * 其余一律 [WebDomainAttribution.REJECTED]（fail-closed，不下发该域候选）。
 *
 * 纯 Kotlin 判定内核（仅复用 [DomainMatcher] 的主机归一化，零 Android 框架依赖），JVM 可测。
 */
enum class WebDomainAttribution {
    /** 受信任浏览器委派：浏览器承载任意站点，其 webDomain 可信 */
    BROWSER_DELEGATED,

    /** 非浏览器应用，但 webDomain 站点经 DAL 声明显式授权该应用 */
    DAL_VERIFIED,

    /** 无法验证归属：拒绝使用该 webDomain（fail-closed） */
    REJECTED
}

object AutofillWebDomainPolicy {

    /**
     * 受信任浏览器包名白名单（包名精确匹配，大小写不敏感）。
     *
     * 与 Credential Manager 侧 [com.keepasskey.app.passkey.CallingOriginResolver] 的「浏览器特权白名单」
     * 语义对齐：后者基于调用方签名证书指纹校验（Credentials API 提供 CallingAppInfo），
     * 传统自动填充无等价签名上下文，故此处以系统背书的调用包名（AssistStructure.activityComponent）
     * 作为一级白名单；白名单外的调用方必须另行经 DAL 归属校验。
     */
    val TRUSTED_BROWSER_PACKAGES: Set<String> = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.google.android.apps.chrome",
        "org.chromium.chrome",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.yandex.browser",
        "com.qwant.liberty",
        "com.android.browser",
        "com.miui.browser",
        "com.heytap.browser",
        "com.vivo.browser",
        "com.huawei.browser"
    )

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

    /** 调用包名是否为受信任浏览器 */
    fun isTrustedBrowser(callingPackage: String): Boolean =
        callingPackage.trim().lowercase() in TRUSTED_BROWSER_PACKAGES

    /**
     * 裁决 webDomain 归属。
     *
     * @param callingPackage 系统背书的调用方包名（AssistStructure.activityComponent）
     * @param rawWebDomain 结构树中的原始 webDomain（调用方可控，不可直接信任）
     * @param dalVerified 非浏览器调用方是否已通过 DAL 归属校验
     */
    fun attribute(
        callingPackage: String,
        rawWebDomain: String?,
        dalVerified: Boolean
    ): WebDomainAttribution {
        if (normalizeDomain(rawWebDomain) == null) return WebDomainAttribution.REJECTED
        if (isTrustedBrowser(callingPackage)) return WebDomainAttribution.BROWSER_DELEGATED
        if (dalVerified) return WebDomainAttribution.DAL_VERIFIED
        return WebDomainAttribution.REJECTED
    }
}
