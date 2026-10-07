package com.keepasskey.app.passkey

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData
import java.time.Instant

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
 * - 通行密钥候选：**RP-ID 域匹配**（浏览器委派与「无可信 web origin 的调用方」同口径，
 *   见 `ISSUE-P2-530`）**或** `android://` 包名维度命中（后者须通过签名绑定门控
 *   [CredentialManagerPackageBindingGate]）；两条维度并存，任一命中即入选；
 *   **且**（请求给出 `allowCredentials` 时）凭据 id 必须在白名单内 —— WebAuthn 规范要求
 *   认证器只呈现请求列出的凭据，否则用户可能选中 RP 明确不接受的凭据导致登录失败；
 *   > **为什么非浏览器调用方也按 RP-ID 匹配**：候选**筛选**与凭据**归属校验**是两件事。
 *   > 归属（origin ⇄ rpId、包名 ⇄ 绑定包名）在**签发前**由 `PasskeyAssertionActivity` 逐条
 *   > 复校并 fail-closed，故列表侧放宽不会改变「能签出什么」；而列表侧收紧的代价是
 *   > **零候选**——用户在系统 UI 上既看不到内容也拿不到补救面（`已知工程限界.md` §6）。
 *   > 四个同类实现（KeePassDX / Monica / Authnkey / fenris）一律按请求 rp.id 检索，
 *   > 本仓此前是唯一例外（`ISSUE-P2-530`）。
 * - 密码候选：域匹配 **或** `android://` 包名匹配，后者同样受门控约束；条目必须**确有密码**。
 * - ISSUE-P3-393：两通道均排除**已过期**条目（与 Autofill 候选面同口径）。
 */
internal object CredentialCandidateMatcher {

    /**
     * 通行密钥候选入选判定。
     *
     * @param browserFlow 调用方是否持**系统背书**的 https web origin。为 `true` 时只认 RP-ID
     *   域匹配（该 `targetRpId` 已由组装器校验为 origin 的可注册后缀，行为与整改前逐字一致）；
     *   为 `false` 时 RP-ID 域匹配与 `android://` 包名维度**并存**。
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
        // ISSUE-P3-393：过期条目不出候选（两通道一致；对齐 KeePassXC 浏览器扩展）
        if (isExpired(entry)) return false
        val passkey = PasskeyData.fromCustomFields(entry.customFields) ?: return false
        if (allowedCredentialIds.isNotEmpty() && passkey.credentialId !in allowedCredentialIds) return false
        // ISSUE-P3-339：域维度只认凭据自身的 `rpId` 一个真相源（`entry.url` 不参与 passkey 候选）
        val domainMatch = targetRpId.isNotBlank() &&
            DomainMatcher.isDomainMatch(passkey.relyingPartyId, targetRpId)
        if (browserFlow) return domainMatch
        val packageMatch = packageDimensionAllowed && callingPackage.isNotBlank() &&
            DomainMatcher.isAndroidPackageMatch(entry.url, callingPackage)
        return domainMatch || packageMatch
    }

    /** 密码候选入选判定 */
    fun matchesPassword(
        entry: KdbxEntry,
        targetDomain: String,
        callingPackage: String,
        packageDimensionAllowed: Boolean
    ): Boolean {
        if (isExpired(entry)) return false
        if (entry.password == null) return false
        val domainMatch = targetDomain.isNotBlank() && entry.url.isNotBlank() &&
            DomainMatcher.isDomainMatch(entry.url, targetDomain)
        val packageMatch = packageDimensionAllowed && callingPackage.isNotBlank() && entry.url.isNotBlank() &&
            DomainMatcher.isAndroidPackageMatch(entry.url, callingPackage)
        return domainMatch || packageMatch
    }

    /**
     * ISSUE-P3-393：条目过期判定（与 AutofillCandidateRanker.isEntryExpired 同口径）。
     * 与 HealthCheckEngine 一致：仅 `Times.Expires=true` 且 `ExpiryTime` 早于 now 时判定过期。
     */
    fun isExpired(entry: KdbxEntry, now: Instant = Instant.now()): Boolean {
        val times = entry.times
        if (!times.expires) return false
        return times.expiryTime.isBefore(now)
    }
}
