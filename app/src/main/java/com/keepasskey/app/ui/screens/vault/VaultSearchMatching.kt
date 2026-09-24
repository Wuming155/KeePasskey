package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.ui.model.UiVaultEntry
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
 * 成本（AC③ 留证口径）：候选文本序列惰性求值；ALL_TERMS 最坏 O(词数 × 文本量)，
 * 与 CONTAINS 同阶（词数即查询长度 / 平均词长），且搜索流已有 300ms 防抖。
 */
internal fun matchesSearchQuery(
    entry: UiVaultEntry,
    query: String,
    mode: SearchMatchMode = SearchMatchMode.CONTAINS
): Boolean {
    if (query.isBlank()) return true
    return when (mode) {
        SearchMatchMode.CONTAINS -> entry.matchesTerm(query)
        SearchMatchMode.ALL_TERMS ->
            query.trim().split(WHITESPACE_RUN).all { entry.matchesTerm(it) }
    }
}

/** 单词（或单串）对候选文本的大小写不敏感子串匹配。 */
private fun UiVaultEntry.matchesTerm(term: String): Boolean =
    searchableTexts().any { it.contains(term, ignoreCase = true) }

/** 参与搜索命中的候选文本（受保护自定义字段只出键、不出值——见 [matchesSearchQuery] KDoc）。 */
private fun UiVaultEntry.searchableTexts(): Sequence<String> = sequence {
    yield(title)
    yield(username)
    yield(url)
    yield(notes)
    yieldAll(tags)
    for (field in customFields) {
        yield(field.key)
        if (!field.isProtected) yield(field.value)
    }
}

private val WHITESPACE_RUN = Regex("\\s+")
