package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.settings.SearchAdvancedOptions
import com.keepasskey.app.ui.screens.settings.SearchField
import com.keepasskey.app.ui.screens.settings.SearchMatchMode

/**
 * 全文搜索匹配（ISSUE-P3-309；§313 结构性拆分：自 `VaultListProjection.kt` 搬出，纯搬移零行为变更）。
 * 全文搜索匹配：命中条目任一处非受保护文本即算匹配。
 *
 * 覆盖范围（对齐 README「全文搜索」契约）：标题 / 用户名 / URL / 备注 / 标签 /
 * 自定义字段的键与**非受保护**值。受保护字段（`isProtected=true`）的明文不进投影
 * （见 [com.keepasskey.app.ui.model.UiCustomField]），故天然不参与命中，
 * 避免搜索侧信道泄露机密；其字段**键**属元数据（如 `TOTP Seed`）仍可命中。
 *
 * 空查询恒为真（未搜索时不过滤）。
 *
 * ISSUE-P3-309 匹配档（[SearchMatchMode]，默认子串口径零变更）：
 * - [SearchMatchMode.CONTAINS]：查询串整体做大小写不敏感子串匹配；
 * - [SearchMatchMode.ALL_TERMS]：查询串按空白切词，**每个词**都须命中（子串口径）——
 *   收紧「短查询误报多」的场景。只切空白、不做词干化；切词结果为空（纯空白）时
 *   走空查询恒真语义（前置 isBlank 已拦截，此处防御性保持一致）。
 *
 * ISSUE-P3-352 AC②：子串未命中时追加**域名感知档**（[domainTierMatches]，加性 OR，
 * 不放宽任何子串口径）——见该函数 KDoc；凭据供给 / 签名侧域判定零改动。
 *
 * ISSUE-P3-439 AC①②：高级选项（[SearchAdvancedOptions]，默认值＝现行为零变化）：
 * - 字段范围勾选（[SearchAdvancedOptions.fields]）收窄候选文本类别；受保护字段键仍可命中
 *   （勾选自定义字段时），保护口径零改动；域名感知档独立于字段范围（复用 URL / rpId 主机名，
 *   属 ISSUE-P3-352 既有档，不在本条收窄面内）；
 * - 大小写档（[SearchAdvancedOptions.caseSensitive]，默认不敏感＝现状）。
 *
 * 成本（AC③ 留证口径）：候选文本序列惰性求值；ALL_TERMS 最坏 O(词数 × 文本量)，
 * 与 CONTAINS 同阶（词数即查询长度 / 平均词长），且搜索流已有 300ms 防抖。
 * 域名感知档仅在子串未命中且查询含 `.` 时求值（多数查询在短路守卫处零开销）。
 */
internal fun matchesSearchQuery(
    entry: UiVaultEntry,
    query: String,
    mode: SearchMatchMode = SearchMatchMode.CONTAINS,
    options: SearchAdvancedOptions = SearchAdvancedOptions()
): Boolean {
    if (query.isBlank()) return true
    return when (mode) {
        SearchMatchMode.CONTAINS -> entry.matchesTerm(query, options)
        SearchMatchMode.ALL_TERMS ->
            query.trim().split(WHITESPACE_RUN).all { entry.matchesTerm(it, options) }
    }
}

/** 单词（或单串）对候选文本的子串匹配（大小写敏感档由 [SearchAdvancedOptions.caseSensitive] 裁决）。 */
private fun UiVaultEntry.matchesTerm(term: String, options: SearchAdvancedOptions): Boolean =
    searchableTexts(options.fields)
        .any { it.contains(term, ignoreCase = !options.caseSensitive) } ||
        domainTierMatches(term, this)

/** 参与搜索命中的候选文本（受保护自定义字段只出键、不出值——见 [matchesSearchQuery] KDoc）。 */
private fun UiVaultEntry.searchableTexts(fields: Set<SearchField>): Sequence<String> = sequence {
    if (SearchField.TITLE in fields) yield(title)
    if (SearchField.USERNAME in fields) yield(username)
    if (SearchField.URL in fields) yield(url)
    if (SearchField.NOTES in fields) yield(notes)
    if (SearchField.TAGS in fields) yieldAll(tags)
    if (SearchField.CUSTOM_FIELDS in fields) {
        for (field in customFields) {
            yield(field.key)
            if (!field.isProtected) yield(field.value)
        }
    }
}

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * ISSUE-P3-352 AC②：域名感知匹配档——**加性 OR 层**，子串未命中时才求值。
 *
 * 取 Kp2a `SearchForHost` 形态（`docs/references/交互体验的参考项目对照.md` §4A②）：
 * 查询串形如域名时，与条目 URL / passkeyRpId 的主机名做**双向父域-子域**判定，
 * 使 `login.example.com` 查得到 URL 为 `https://example.com` 的条目（纯子串做不到）。
 *
 * 红线（AC②）：本档**只服务应用内搜索**，`passkey/DomainMatcher` 本体与其在
 * 凭据供给 / 签名侧的判定链路**零改动**——此处仅**复用**其归一化与点号边界语义：
 * - [DomainMatcher.extractDomain] 统一剥 scheme / 路径 / 端口（与凭据侧同一归一器，
 *   避免「搜索与供给两个归一器答案不同」的 ISSUE-P3-339 同型问题）；
 * - [DomainMatcher.isDomainMatch] 自带**严格点号边界**与**可注册域名下限**
 *   （单标签 / 公共后缀拒绝），杜绝 `evilexample.com` 粘连命中 `example.com`、
 *   以及查询词 `com` 这类单标签扫射全库。
 *
 * 守卫：
 * - 查询含空白或不含 `.` ⇒ 直接不求值（普通词语零 PSL 查询开销，绝大多数查询在此短路）；
 * - `android://` 绑定条目 / 查询不走 host 维度（Kp2a 对 `androidapp://` 同样跳过 host 匹配；
 *   包名形态由子串档与 autofill 的 [DomainMatcher.isAndroidPackageMatch] 各自负责）。
 */
internal fun domainTierMatches(query: String, entry: UiVaultEntry): Boolean {
    val qRaw = query.trim()
    if (qRaw.isEmpty() || qRaw.any { it.isWhitespace() }) return false
    if (!qRaw.contains('.')) return false
    if (qRaw.startsWith("android://", ignoreCase = true)) return false
    val qHost = DomainMatcher.extractDomain(qRaw)
    if (qHost.isEmpty()) return false
    return sequenceOf(entry.url, entry.passkeyRpId)
        .filterNotNull()
        .filter { !it.startsWith("android://", ignoreCase = true) }
        .any { source -> hostPairMatch(qHost, DomainMatcher.extractDomain(source)) }
}

/** 双向父域-子域：查询为条目父域，或条目为查询父域（点号边界 / 可注册下限由 isDomainMatch 裁决）。 */
private fun hostPairMatch(queryHost: String, entryHost: String): Boolean {
    if (entryHost.isEmpty()) return false
    // www 前缀对匹配无语义（Kp2a SearchForHost 同样剥离），先归一再判定
    val q = queryHost.removePrefix("www.")
    val e = entryHost.removePrefix("www.")
    if (q.isEmpty() || e.isEmpty()) return false
    return DomainMatcher.isDomainMatch(e, q) || DomainMatcher.isDomainMatch(q, e)
}
