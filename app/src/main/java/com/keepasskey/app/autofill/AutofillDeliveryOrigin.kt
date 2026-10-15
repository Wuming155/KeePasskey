package com.keepasskey.app.autofill

import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData

/**
 * 自动填充交付来源判据（`ISSUE-P2-617`，自 [AutofillCandidateRanker] 按职责拆出——
 * 本文件新增后该排序器回落出行数闸门 tier2 以下；拆分**零行为变更**，只做结构搬运）。
 *
 * 本文件承载「条目是否可向本次调用方交付」的两道纯函数判据，调用方有二：
 * - [AutofillCandidateRanker] 的记忆档（浏览器上下文第二道门）；
 * - `AutofillConfirmActivity` 的最终交付复检（与候选装配同源）。
 * 两处共用同一实现，杜绝装配与复检口径漂移。
 */

/**
 * 条目是否命中目标 web 域（记忆域复检与交付复检的共用判据）。
 *
 * 与 `AutofillCandidateRanker.scoreEntry` 的域准入分支同口径：条目 URL 经
 * [DomainMatcher.isDomainMatch] 命中，或其通行密钥 RP ID 命中（`scoreEntry` 的
 * `passkeyMatched` 分支）；任一满足即 true。入参须为已归一非空的域
 * （调用方经 `DomainMatcher.extractDomain` 归一）。
 *
 * `ISSUE-P3-171` 同口径：`entryUrl` 由调用方预先读出并下传，避免属性 getter 的重复解密。
 */
internal fun entryMatchesWebDomain(entry: KdbxEntry, entryUrl: String, normalizedDomain: String): Boolean {
    if (entryUrl.isNotBlank() && DomainMatcher.isDomainMatch(entryUrl, normalizedDomain)) return true
    val passkey = PasskeyData.fromCustomFields(entry.customFields)
    return passkey != null && DomainMatcher.isDomainMatch(passkey.relyingPartyId, normalizedDomain)
}

/**
 * 条目是否可向本次调用方交付（`ISSUE-P2-617` 确认页最终交付复检，`scoreEntry` 准入的同源复述）。
 *
 * - `webDomain` 为空（纯 App 表单）⇒ true（无域约束，包名维度已由调用方的前置绑定门控）；
 * - 否则条目 URL / 通行密钥 RP ID 命中当前域，或条目经 `android://` 显式绑定调用包名，
 *   任一满足即 true——与候选装配的放行条件逐项对应，合法候选在此复检恒通过。
 *
 * @param entryUrl 调用方预读的条目 URL（[KdbxEntry.displayUrl]，避免重复解密）
 */
internal fun entryMatchesDeliveryOrigin(
    entry: KdbxEntry,
    entryUrl: String,
    webDomain: String?,
    callingPackage: String
): Boolean {
    val normalizedDomain = webDomain
        ?.let { DomainMatcher.extractDomain(it) }
        ?.takeIf { it.isNotEmpty() }
        ?: return true
    if (entryMatchesWebDomain(entry, entryUrl, normalizedDomain)) return true
    return callingPackage.isNotBlank() && DomainMatcher.isAndroidPackageMatch(entryUrl, callingPackage)
}
