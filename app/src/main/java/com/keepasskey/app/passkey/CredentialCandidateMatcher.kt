package com.keepasskey.app.passkey

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData

/**
 * CM 通道**候选层**的条目入选判定（自 [CredentialResponseAssembler] 抽取，纯函数）。
 *
 * ## 为何单独抽取
 *
 * `ISSUE-P2-83` 的 AC② 要求在**候选层**断言「同包名不同签名 → 不命中」。该判定原先内联在
 * 组装器的两个 `filter` 里，而组装器依赖 `VaultRepository` / `Context`，宿主与设备侧都难以
 * 直接构造——于是「门控是否真的作用到候选入选」只能靠源码接线守卫间接保障。
 * 抽成纯函数后，候选层判据可被 JVM 穷举，也可在**真机**上用**真实绑定存储**复跑（AC③）。
 *
 * ## 判定口径（与组装器逐字一致，不得各自漂移）
 *
 * - 通行密钥候选：浏览器委派走 RP-ID 域匹配；普通应用走 `android://` 硬约束 **且** 包名维度
 *   已通过签名绑定门控（[CredentialManagerPackageBindingGate]）；
 *   **且**（请求给出 `allowCredentials` 时）凭据 id 必须在白名单内 —— WebAuthn 规范要求
 *   认证器只呈现请求列出的凭据，否则用户可能选中 RP 明确不接受的凭据导致登录失败；
 * - 密码候选：域匹配 **或** `android://` 包名匹配，后者同样受门控约束；条目必须**确有密码**。
 */
internal object CredentialCandidateMatcher {

    /**
     * 通行密钥候选入选判定。
     *
     * @param allowedCredentialIds 请求 `allowCredentials[].id` 的 Base64URL 文本集合；
     *   **空集表示请求未限定**（无用户名 / discoverable 流程），此时不做 id 收敛。
     */
    fun matchesPasskey(
        entry: KdbxEntry,
        browserFlow: Boolean,
        targetRpId: String,
        callingPackage: String,
        packageDimensionAllowed: Boolean,
        allowedCredentialIds: Set<String> = emptySet()
    ): Boolean {
        val passkey = PasskeyData.fromCustomFields(entry.customFields) ?: return false
        if (allowedCredentialIds.isNotEmpty() && passkey.credentialId !in allowedCredentialIds) return false
        return if (browserFlow) {
            DomainMatcher.isDomainMatch(passkey.relyingPartyId, targetRpId)
        } else {
            packageDimensionAllowed && callingPackage.isNotBlank() &&
                DomainMatcher.isAndroidPackageMatch(entry.url, callingPackage)
        }
    }

    /** 密码候选入选判定 */
    fun matchesPassword(
        entry: KdbxEntry,
        targetDomain: String,
        callingPackage: String,
        packageDimensionAllowed: Boolean
    ): Boolean {
        if (entry.password == null) return false
        val domainMatch = targetDomain.isNotBlank() && entry.url.isNotBlank() &&
            DomainMatcher.isDomainMatch(entry.url, targetDomain)
        val packageMatch = packageDimensionAllowed && callingPackage.isNotBlank() && entry.url.isNotBlank() &&
            DomainMatcher.isAndroidPackageMatch(entry.url, callingPackage)
        return domainMatch || packageMatch
    }
}
