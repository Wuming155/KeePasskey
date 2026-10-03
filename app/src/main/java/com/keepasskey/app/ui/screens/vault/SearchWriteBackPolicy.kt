package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.passkey.DomainMatcher

/**
 * 「搜索词写回条目 URL」的待确认提示（`ISSUE-P3-442` AC①）。
 *
 * @property entryTitle 条目名（用户刚点开的条目，属其本会话已见内容）
 * @property currentUrl 该条目当前 URL（空串＝未设置，对话框据此换文案）
 * @property proposedUrl 将要写入的 URL（＝搜索词原文，已 trim）
 */
data class SearchWriteBackPrompt(
    val entryId: String,
    val entryTitle: String,
    val currentUrl: String,
    val proposedUrl: String
)

/**
 * 搜索词自愈回灌的**纯判定内核**（`ISSUE-P3-442`；无 Android / 组合依赖，JVM 可直测）。
 *
 * 竞品依据（`docs/references/UI与操作体验的竞品对比报告.md` §5-G5 / keepass2android
 * `AppTask.cs:494-534`）：用户手动选中一个「域名匹配没命中却确实是他要的」条目时，
 * 询问是否把搜索词写进该条目 URL，一次操作永久修正匹配错误。
 *
 * 三条口径：
 * 1. **只在值得写的时候问**：搜索词必须是**域名形态**（`example.com` / `https://…`），
 *    否则写进 URL 字段毫无意义（「小明」不是网址）；条目 URL 已覆盖该域时也无需自愈。
 * 2. **敏感红线（`ISSUE-P2-105` 同源）**：口令形态文本绝不进入本链路——
 *    [looksLikeSecretQuery] 命中即**不提供、不预填、不回显**（对话框根本不出）。
 * 3. **`{REF}` 不可覆盖**：条目 URL 含字段引用时拒绝写回（写 URL 会整段替换该字段，
 *    引用因此丢失）——由 [hasReferenceUrl] 判定，`Override URL` 与其它字段不受影响
 *    （写回只改 `URL` 一个字段，见 `VaultEntryWriteCoordinator.updateEntryUrl`）。
 */
internal object SearchWriteBackPolicy {

    /** 可写回词的长度上限（真实域名不可能更长；超出即判非域名形态） */
    const val MAX_TERM_LENGTH = 255

    /** 「口令形态」判定的最小长度（更短的非域名串即使含符号也不值得拦） */
    const val MIN_SECRET_LENGTH = 12

    /**
     * 域名 / URL 形态：可选 scheme + 至少两个标签 + 字母 TLD，允许端口与单段路径。
     *
     * 刻意**不**接受无点短词与含空白串：ACP① 要求「该条目 URL 与搜索词**域**不匹配时触发」，
     * 只有域形态才有「写回后能修好域名匹配」的语义。
     */
    private val HOST_LIKE = Regex(
        "^(?:[a-z][a-z0-9+.\\-]*://)?" +
            "(?:[a-z0-9](?:[a-z0-9\\-]*[a-z0-9])?\\.)+[a-z]{2,24}" +
            "(?::\\d{1,5})?(?:/\\S*)?$",
        RegexOption.IGNORE_CASE
    )

    /** 搜索词是否为域名 / URL 形态（可写回的必要条件）。 */
    fun isUrlTerm(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty() || q.length > MAX_TERM_LENGTH) return false
        if (q.any { it.isWhitespace() }) return false
        return HOST_LIKE.matches(q)
    }

    /**
     * 敏感形态守卫（`ISSUE-P2-105` 同源红线）：像口令 / 密钥的搜索词**一律不进入本链路**。
     *
     * 判据是「不是域名形态 + 无空白 + 长度 ≥ [MIN_SECRET_LENGTH] + 至少含两类字符」：
     * 典型粘贴口令（`Tr0ub4dor&3xample`）命中；普通检索词（`小明` / `两张图`）不命中。
     *
     * 与 [isUrlTerm] 的关系是**冗余防线**而非替代：当前 [isUrlTerm] 已排除全部非域名形态，
     * 故本判定目前恒不改变结果——保留它是为了将来若放宽 [isUrlTerm]（例如允许无点短词写回）
     * 时，敏感红线仍在；`SearchWriteBackPolicyTest` 正反两态都锁定。
     */
    fun looksLikeSecretQuery(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        if (isUrlTerm(q)) return false
        if (q.any { it.isWhitespace() }) return false
        val hasLetter = q.any { it.isLetter() }
        val hasDigit = q.any { it.isDigit() }
        val hasSymbol = q.any { !it.isLetterOrDigit() }
        return q.length >= MIN_SECRET_LENGTH && ((hasLetter && hasDigit) || hasSymbol)
    }

    /** 条目 URL 是否含 `{REF:…}` 字段引用（含即拒绝写回，避免整段替换掉引用原文）。 */
    fun hasReferenceUrl(entryUrl: String): Boolean =
        entryUrl.contains("{REF", ignoreCase = true)

    /**
     * 条目 URL 是否**已覆盖**搜索词域（双向父域-子域判定，归一器与凭据侧同源）。
     *
     * 复用 [DomainMatcher]（与 `domainTierMatches` 同一归一器）：早就在能匹配上的条目上再问
     * 「要不要写进 URL」是纯噪音。
     */
    fun urlCoversTerm(entryUrl: String, term: String): Boolean {
        val q = DomainMatcher.extractDomain(term).removePrefix("www.")
        val e = DomainMatcher.extractDomain(entryUrl).removePrefix("www.")
        if (q.isEmpty() || e.isEmpty()) return false
        return DomainMatcher.isDomainMatch(e, q) || DomainMatcher.isDomainMatch(q, e)
    }

    /**
     * 是否向用户提出「把搜索词写入该条目 URL？」。
     *
     * @param entryBlocked 条目侧一票否决（只读会话 / 回收站内 / URL 含 `{REF}`），由调用方合成
     * @param alreadyAsked 本会话内是否已对该条目问过（AC③：同一条目一次会话只问一次）
     * @param sessionSuppressed 用户已勾选「本次会话不再询问」
     */
    fun shouldOffer(
        query: String,
        entryUrl: String,
        entryBlocked: Boolean,
        alreadyAsked: Boolean,
        sessionSuppressed: Boolean
    ): Boolean {
        if (sessionSuppressed || alreadyAsked || entryBlocked) return false
        val term = query.trim()
        // 敏感红线先于一切形态判定（AC②：不预填、不回显）
        if (looksLikeSecretQuery(term)) return false
        if (!isUrlTerm(term)) return false
        if (urlCoversTerm(entryUrl, term)) return false
        return true
    }
}
