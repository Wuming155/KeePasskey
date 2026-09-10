package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.RealVaultRepository
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData

/**
 * 自动填充候选条目打分与排序器（ISSUE-P3-39）。
 *
 * 设计前提（**安全不可退让**）：
 * - 本排序器**只决定候选之间的先后与截断**，**绝不放宽**任何匹配条件——
 *   入选项必须先通过既有的严格匹配（[DomainMatcher.isDomainMatch] / [DomainMatcher.isPackageMatch]，
 *   无任何 title/notes 启发式）。`webDomain` 的归属校验由调用方（[AutofillOriginResolver]）负责，
 *   进入本类时已被判定为可用；
 * - 打分仅用于「同一批已匹配候选」的排序，分数高低不改变「是否可填充」这一事实。
 *
 * 排序键（降序）：匹配强度分 → 收藏 → 最后修改时间；
 * 另有「上次填充优先」置顶（仅重排，不改变入选集合），确定性排序（同分同时间保持入参顺序）。
 */
object AutofillCandidateRanker {

    /** 默认候选上限（与原 `KeePasskeyAutofillService.MAX_DATASET_COUNT` 对齐） */
    const val DEFAULT_LIMIT = 8

    /** 匹配原因（供诊断与单测断言；不参与安全判定） */
    enum class MatchReason {
        /** 条目 url 以 android:// 绑定调用包名（严格精确，无父子关系） */
        EXACT_PACKAGE,

        /** 条目域名与目标域完全相等 */
        EXACT_DOMAIN,

        /** 条目域名是目标域的父域（目标域为条目域的子域） */
        PARENT_DOMAIN,

        /** 包名与域名同时命中 */
        PACKAGE_DOMAIN_COMBO
    }

    data class Ranked(
        val entry: KdbxEntry,
        val score: Int,
        val reasons: Set<MatchReason>
    )

    private const val SCORE_EXACT_PACKAGE = 130
    private const val SCORE_EXACT_DOMAIN = 140
    private const val SCORE_PARENT_DOMAIN = 120
    private const val SCORE_PACKAGE_DOMAIN_COMBO = 30
    private const val SCORE_FAVORITE_BONUS = 5

    /**
     * 对候选条目执行「匹配判定 + 打分 + 排序 + 截断」。
     *
     * @param entries 库内全部条目（Core 层直出）
     * @param callingPackage 系统背书的调用方包名
     * @param webDomain **已通过归属校验**的目标域名（null 表示本次不使用域名维度）
     * @param lastFilledEntryId 上次填充条目 id（hex），命中则置顶
     * @param limit 返回上限（≥1）
     * @return 按优先级降序排列的候选，长度 ≤ [limit]
     */
    fun rank(
        entries: List<KdbxEntry>,
        callingPackage: String,
        webDomain: String?,
        lastFilledEntryId: String? = null,
        limit: Int = DEFAULT_LIMIT
    ): List<Ranked> {
        if (entries.isEmpty()) return emptyList()

        val normalizedDomain = webDomain
            ?.let { DomainMatcher.extractDomain(it) }
            ?.takeIf { it.isNotEmpty() }

        val scored = entries.mapNotNull { scoreEntry(it, callingPackage, normalizedDomain) }
        if (scored.isEmpty()) return emptyList()

        // 防御性去重：同一条目 id 保留最高分
        val bestById = linkedMapOf<String, Ranked>()
        scored.forEach { ranked ->
            val key = ranked.entry.id.toHexString()
            val existing = bestById[key]
            if (existing == null || ranked.score > existing.score) {
                bestById[key] = ranked
            }
        }

        val ordered = bestById.values.sortedWith(
            compareByDescending<Ranked> { it.score }
                .thenByDescending { it.entry.times.lastModificationTime }
        )

        return promoteLastFilled(ordered, lastFilledEntryId).take(limit.coerceAtLeast(1))
    }

    /** 上次填充条目置顶（仅重排，不改变入选集合）；不存在或已在首位时原样返回 */
    private fun promoteLastFilled(ordered: List<Ranked>, lastFilledEntryId: String?): List<Ranked> {
        if (lastFilledEntryId.isNullOrBlank() || ordered.isEmpty()) return ordered
        val index = ordered.indexOfFirst {
            it.entry.id.toHexString().equals(lastFilledEntryId, ignoreCase = true)
        }
        if (index <= 0) return ordered
        val promoted = ordered[index]
        return buildList(ordered.size) {
            add(promoted)
            ordered.forEachIndexed { i, ranked -> if (i != index) add(ranked) }
        }
    }

    private fun scoreEntry(
        entry: KdbxEntry,
        callingPackage: String,
        webDomain: String?
    ): Ranked? {
        val reasons = linkedSetOf<MatchReason>()
        var score = 0

        val packageMatch = callingPackage.isNotBlank() && entry.url.isNotBlank() &&
                DomainMatcher.isPackageMatch(entry.url, callingPackage)
        if (packageMatch) {
            score += SCORE_EXACT_PACKAGE
            reasons += MatchReason.EXACT_PACKAGE
        }

        if (webDomain != null) {
            val passkey = PasskeyData.fromCustomFields(entry.customFields)
            val domainMatched = (passkey != null &&
                    DomainMatcher.isDomainMatch(passkey.relyingPartyId, webDomain)) ||
                    (entry.url.isNotBlank() && DomainMatcher.isDomainMatch(entry.url, webDomain))
            if (domainMatched) {
                if (isExactDomain(entry, passkey, webDomain)) {
                    score += SCORE_EXACT_DOMAIN
                    reasons += MatchReason.EXACT_DOMAIN
                } else {
                    score += SCORE_PARENT_DOMAIN
                    reasons += MatchReason.PARENT_DOMAIN
                }
            }
        }

        if (score <= 0) return null

        if (MatchReason.EXACT_PACKAGE in reasons &&
            (MatchReason.EXACT_DOMAIN in reasons || MatchReason.PARENT_DOMAIN in reasons)
        ) {
            score += SCORE_PACKAGE_DOMAIN_COMBO
            reasons += MatchReason.PACKAGE_DOMAIN_COMBO
        }

        if (entry.customData[RealVaultRepository.FAVORITE_CUSTOM_DATA_KEY] == "true") {
            score += SCORE_FAVORITE_BONUS
        }

        return Ranked(entry = entry, score = score, reasons = reasons)
    }

    /**
     * 判定是否为「精确域名」匹配：取实际命中的域名来源（passkey RP ID 优先）与目标域比较。
     * 注意：本方法仅在 [DomainMatcher.isDomainMatch] 已通过的前提下调用。
     */
    private fun isExactDomain(entry: KdbxEntry, passkey: PasskeyData?, webDomain: String): Boolean {
        val source = if (passkey != null && DomainMatcher.isDomainMatch(passkey.relyingPartyId, webDomain)) {
            passkey.relyingPartyId
        } else {
            entry.url
        }
        return DomainMatcher.extractDomain(source) == webDomain
    }
}
