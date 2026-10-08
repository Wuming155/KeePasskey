package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.RealVaultRepository
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.passkey.PublicSuffixList
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData

/**
 * 自动填充候选条目打分与排序器（ISSUE-P3-39；ISSUE-P3-372 AC④⑤ 扩档）。
 *
 * 设计前提（**安全不可退让**）：
 * - 本排序器**只决定候选之间的先后与截断**，**绝不放宽**任何匹配条件——
 *   入选项必须先通过既有的严格匹配（[DomainMatcher.isDomainMatch] / [DomainMatcher.isAndroidPackageMatch]，
 *   无任何 title/notes 启发式）。`webDomain` 的归属校验由调用方（[AutofillOriginResolver]）负责，
 *   进入本类时已被判定为可用；
 * - **唯一例外且是刻意例外**（ISSUE-P3-528）：`rememberedEntryId` 命中的条目不经匹配即入选。
 *   其准入依据**不是**任何形式的模糊匹配，而是「用户在**同一调用方**（包名 + 签名摘要）上
 *   此前已显式选中并交付过这一条」这一既成事实——该授权强度不低于 `android://` 首次绑定
 *   （`ISSUE-P2-46`），且不构成对未授权调用方的放行：记忆键含签名摘要，换签名 / 重打包不命中，
 *   包名非法一律 null（见 [AutofillCallerEntryMemory]）。记忆条目进入候选后**仍挂强制二次确认**，
 *   用户仍看到调用方归属。**不得**在此基础上再引入 title / 应用名一类的准入档（那会绕过
 *   `ISSUE-P2-46`，见 `docs/references/自动填充关联记忆与字段识别的参考项目对照.md` §4 第 5 行）。
 * - **包名维度另受 ISSUE-P2-46 门控**：`android://` 绑定只有在调用方完成「包名 + 签名摘要」首次绑定后
 *   才参与放行（判定由调用方经 `packageDimensionAuthorized` 显式传入，见 [rank]）；
 * - 打分仅用于「同一批已匹配候选」的排序，分数高低不改变「是否可填充」这一事实。
 *
 * ISSUE-P3-372 AC④⑤ + ISSUE-P3-377 新增三条**同站归一 / 同站后缀**的加性命中档（Monica
 * `BitwardenLikeAutofillMatcherNg` 子域/基域/应用名维度的吸收）：
 * - **去 www 归一**：条目主机名 `www.` 前缀剥离后与目标域相等 ⇒ 按 EXACT_DOMAIN 计
 *   （`www` 只是普通标签，剥离不可能造出跨域命中——与 `ISSUE-P3-339` 剔除 DNS 根点
 *   同属「两个归一器必须同口径」的归一化，而非模糊匹配）；
 * - **子域后缀**：条目主机名以 `.<目标域>` 结尾（条目在目标域之下）⇒ `SUBDOMAIN_OF_ORIGIN`
 *   ——子域受目标域所有者控制，标签边界严格（`evilgithub.com` 不命中 `github.com`）；
 * - **基域**（ISSUE-P3-377）：同可注册域（eTLD+1 相等，兄弟子域）⇒ `SAME_BASE_DOMAIN`——
 *   经 PSL 计算，私有段兄弟与 IP 字面量恒不命中；
 * - **应用名加成**：调用方 launcher label 与条目标题相似 ⇒ `APP_TITLE_MATCH` 加分。
 *   仅对**已通过严格匹配**（score > 0）的条目生效，绝不让纯标题相似的条目入选。
 *
 * 排序键（降序）：匹配强度分 → 收藏 → 最后修改时间；
 * 另有「上次填充优先」置顶（仅重排，不改变入选集合），确定性排序（同分同时间保持入参顺序）。
 */
object AutofillCandidateRanker {

    /** 默认候选上限（与原 `KeePasskeyAutofillService.MAX_DATASET_COUNT` 对齐） */
    const val DEFAULT_LIMIT = 8

    /** 匹配原因（供诊断与单测断言；不参与安全判定） */
    enum class MatchReason {
        /** 条目 url 以 android:// 绑定调用包名（严格精确，无父子关系；https:// 等 Web 绑定条目不产生本原因） */
        EXACT_PACKAGE,

        /** 条目域名与目标域完全相等（含去 www 归一后相等，ISSUE-P3-372 AC⑤） */
        EXACT_DOMAIN,

        /** 条目域名是目标域的父域（目标域为条目域的子域） */
        PARENT_DOMAIN,

        /** 条目域名是目标域的子域（条目在目标域之下，标签边界严格；ISSUE-P3-372 AC⑤） */
        SUBDOMAIN_OF_ORIGIN,

        /** 条目与目标域同可注册域（eTLD+1 相等，如兄弟子域；ISSUE-P3-377 AC②） */
        SAME_BASE_DOMAIN,

        /** 包名与域名同时命中 */
        PACKAGE_DOMAIN_COMBO,

        /** 条目标题与调用方应用名相似（仅排序加成；ISSUE-P3-372 AC④） */
        APP_TITLE_MATCH,

        /** Wi-Fi 设置上下文加成（条目携带 Wi-Fi 信号词；仅排序加成；ISSUE-P3-373 AC①） */
        WIFI_CONTEXT_MATCH,

        /**
         * `ISSUE-P3-528`：调用方关联记忆命中（Monica `resolveLastFilledEntry` 吸收，
         * 键＝包名 + 签名摘要，见 [AutofillCallerEntryMemory]）。
         *
         * **本档的准入依据不是匹配**，而是「用户此前在该调用方上**显式选中并交付过**这一条」这一
         * 已发生的事实（记忆由选择器 / 确认页在交付成功后写入）。它不受
         * `webDomain` / `android://` 绑定是否存在约束，故纯 App 场景首次手选后即可持续命中。
         */
        REMEMBERED_CALLER_ENTRY
    }

    data class Ranked(
        val entry: KdbxEntry,
        val score: Int,
        val reasons: Set<MatchReason>
    )

    private const val SCORE_EXACT_PACKAGE = 130
    private const val SCORE_EXACT_DOMAIN = 140
    private const val SCORE_PARENT_DOMAIN = 120

    /** 子域档（ISSUE-P3-377 AC③：110 → 115，与 Monica `子域 115` 同值对齐） */
    private const val SCORE_SUBDOMAIN_OF_ORIGIN = 115

    /** 基域档（ISSUE-P3-377 AC②：与 Monica `基域 100` 同值——同 eTLD+1 兄弟子域） */
    private const val SCORE_BASE_DOMAIN = 100
    private const val SCORE_PACKAGE_DOMAIN_COMBO = 30
    private const val SCORE_FAVORITE_BONUS = 5

    /** 应用名相似加成（低于全部 EXACT_* 档，对齐 Monica 应用名 95 的相对位次） */
    private const val SCORE_APP_TITLE_MATCH = 95

    /** Wi-Fi 设置上下文加成（ISSUE-P3-373 AC①：仅排序、低于应用名档，绝不出现在准入判定里） */
    private const val SCORE_WIFI_CONTEXT_BOOST = 70

    /**
     * 调用方关联记忆档（ISSUE-P3-528）：**高于全部匹配档**，使命中条目置于首位。
     * 该档不参与匹配判定（准入依据见 [MatchReason.REMEMBERED_CALLER_ENTRY]）。
     */
    private const val SCORE_REMEMBERED_CALLER_ENTRY = 150

    /**
     * 对候选条目执行「匹配判定 + 打分 + 排序 + 截断」。
     *
     * @param entries 库内全部条目（Core 层直出）
     * @param callingPackage 系统背书的调用方包名
     * @param webDomain **已通过归属校验**的目标域名（null 表示本次不使用域名维度）
     * @param packageDimensionAuthorized `android://` 包名维度是否可用于本次放行（ISSUE-P2-46）。
     *   **必须由调用方逐次显式给出**（无默认值）：判定口径为「该调用方的**包名 + 签名摘要**已被用户
     *   显式绑定过」（`AutofillCallerTrustStore.isTrusted` 且摘要**非空**）。为 false 时
     *   `android://` 绑定条目**一律不入选**——条目仍可经域名维度入选，未绑定的调用方则需用户
     *   经选择器**显式指认**（该动作即首次绑定写入，见 `AutofillPickerActivity`）。
     *   **不得**在这里传常量 true：那等于取消本条整改。
     * @param lastFilledEntryId 上次填充条目 id（hex），命中则置顶
     * @param limit 返回上限（≥1）
     * @param callingAppLabel ISSUE-P3-372 AC④：调用方 launcher 应用名（取不到传 null）；
     *   仅作排序加成，不影响入选
     * @param wifiContext ISSUE-P3-373 AC①：调用方是否为 Wi-Fi 设置类应用
     *   （[WifiFillBoostPolicy.isWifiSettingsPackage]）；true 时对携带 Wi-Fi 信号词的条目
     *   给排序加成，同样**只改排序不改准入**
     * @param rememberedEntryId ISSUE-P3-528：调用方关联记忆命中的条目标识（hex）。
     *   **唯一一条不依赖匹配的准入来源**——依据是用户在该调用方上的既有显式交付
     *   （记忆键含包名 + 签名摘要，见 [AutofillCallerEntryMemory]）；传入时该条目恒成为候选并置首位，
     *   但仍受「已过期条目排除」约束。传 null（未命中）时本参数对结果零影响。
     * @return 按优先级降序排列的候选，长度 ≤ [limit]
     */
    fun rank(
        entries: List<KdbxEntry>,
        callingPackage: String,
        webDomain: String?,
        packageDimensionAuthorized: Boolean,
        lastFilledEntryId: String? = null,
        limit: Int = DEFAULT_LIMIT,
        callingAppLabel: String? = null,
        wifiContext: Boolean = false,
        rememberedEntryId: String? = null
    ): List<Ranked> {
        if (entries.isEmpty()) return emptyList()

        val normalizedDomain = webDomain
            ?.let { DomainMatcher.extractDomain(it) }
            ?.takeIf { it.isNotEmpty() }

        val scored = entries.mapNotNull { entry ->
            if (rememberedEntryId != null &&
                entry.id.toHexString().equals(rememberedEntryId, ignoreCase = true)
            ) {
                // 记忆命中：不参与匹配判定（准入依据是用户已发生的显式交付），但仍排除已过期条目
                if (isEntryExpired(entry)) null
                else Ranked(
                    entry = entry,
                    score = SCORE_REMEMBERED_CALLER_ENTRY,
                    reasons = setOf(MatchReason.REMEMBERED_CALLER_ENTRY)
                )
            } else {
                scoreEntry(
                    entry, callingPackage, normalizedDomain, packageDimensionAuthorized,
                    callingAppLabel, wifiContext
                )
            }
        }
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

    /**
     * ISSUE-P3-393：条目是否已过期（Times.Expires=true 且 ExpiryTime 早于 now）。
     * 与 `HealthCheckEngine` / 详情页 ExpiryStatusCard 同口径。
     */
    fun isEntryExpired(entry: KdbxEntry, now: java.time.Instant = java.time.Instant.now()): Boolean {
        val times = entry.times
        if (!times.expires) return false
        // KdbxTimes.expiryTime 为非空 Instant；过期与否由 expires 开关控制
        return times.expiryTime.isBefore(now)
    }

    private fun scoreEntry(
        entry: KdbxEntry,
        callingPackage: String,
        webDomain: String?,
        packageDimensionAuthorized: Boolean,
        callingAppLabel: String?,
        wifiContext: Boolean
    ): Ranked? {
        // ISSUE-P3-393 AC①：过期条目直接排除出填充候选（对齐 KeePassXC 浏览器扩展
        // 「prior to custom data」检查；两通道一致见调用方过滤）。
        if (isEntryExpired(entry)) return null

        val reasons = linkedSetOf<MatchReason>()
        var score = 0

        // ISSUE-P3-171：`url` 是属性 getter——每次访问都是一次驻留密文解密 + String 物化；
        // 本函数与其助手 [isExactDomain] 合计最多访问 4 次 ⇒ 每条目只读一次并下传。
        // ISSUE-P2-534：本函数在自动填充服务 / 选择器里于 `Dispatchers.Default` 上对候选排序，
        // 与会话整树替换的就地擦除不共享锁 ⇒ 元数据读取一律走展示面读口（裸 getter 会崩进程）
        val entryUrl = entry.displayUrl()

        // ISSUE-P2-46：包名维度必须同时满足「条目显式 android:// 绑定」与「调用方已按
        // 包名 + 签名摘要完成首次绑定」。仅有前者时，任意以同 applicationId 侧载的应用
        // 都能命中（原缺陷）；仅有后者时，条目并未声明对该包的绑定，同样不得入选。
        val packageMatch = packageDimensionAuthorized &&
            callingPackage.isNotBlank() &&
            entryUrl.isNotBlank() &&
            DomainMatcher.isAndroidPackageMatch(entryUrl, callingPackage)
        if (packageMatch) {
            score += SCORE_EXACT_PACKAGE
            reasons += MatchReason.EXACT_PACKAGE
        }

        if (webDomain != null) {
            val passkey = PasskeyData.fromCustomFields(entry.customFields)
            val passkeyMatched = passkey != null &&
                    DomainMatcher.isDomainMatch(passkey.relyingPartyId, webDomain)
            val rawDomainMatched = entryUrl.isNotBlank() &&
                    DomainMatcher.isDomainMatch(entryUrl, webDomain)
            if (passkeyMatched || rawDomainMatched) {
                if (isExactDomain(entryUrl, passkey, webDomain)) {
                    score += SCORE_EXACT_DOMAIN
                    reasons += MatchReason.EXACT_DOMAIN
                } else {
                    score += SCORE_PARENT_DOMAIN
                    reasons += MatchReason.PARENT_DOMAIN
                }
            } else if (entryUrl.isNotBlank()) {
                // ISSUE-P3-372 AC⑤：严格匹配未命中时的两条同站加性档（只作用于条目 url 主机名，
                // 不触碰 passkey rpId 判定——rpId 仍只走 DomainMatcher 严格路径）
                applySameSiteTiers(entryUrl, webDomain)?.let { tier ->
                    score += tier.score
                    reasons += tier.reason
                }
            }
        }

        if (score <= 0) return null

        // 标题只读一次，供两个排序加成维度共用（ISSUE-P3-171 同口径：属性 getter 少次化）
        val entryTitle = entry.displayTitle()

        if (MatchReason.EXACT_PACKAGE in reasons &&
            (MatchReason.EXACT_DOMAIN in reasons || MatchReason.PARENT_DOMAIN in reasons)
        ) {
            score += SCORE_PACKAGE_DOMAIN_COMBO
            reasons += MatchReason.PACKAGE_DOMAIN_COMBO
        }

        // ISSUE-P3-372 AC④：应用名相似加成——只对已入选（score > 0）条目生效的排序维度
        if (callingAppLabel != null && titleMatchesAppLabel(entryTitle, callingAppLabel)) {
            score += SCORE_APP_TITLE_MATCH
            reasons += MatchReason.APP_TITLE_MATCH
        }

        // ISSUE-P3-373 AC①：Wi-Fi 设置上下文加成——同样只对已入选条目生效的排序维度
        if (wifiContext && WifiFillBoostPolicy.hasWifiSignal(entryTitle, entryUrl)) {
            score += SCORE_WIFI_CONTEXT_BOOST
            reasons += MatchReason.WIFI_CONTEXT_MATCH
        }

        if (entry.customData[RealVaultRepository.FAVORITE_CUSTOM_DATA_KEY] == "true") {
            score += SCORE_FAVORITE_BONUS
        }

        return Ranked(entry = entry, score = score, reasons = reasons)
    }

    /** 同站加性档命中结果（分数 + 原因） */
    private data class SameSiteTier(val score: Int, val reason: MatchReason)

    /**
     * ISSUE-P3-372 AC⑤ + ISSUE-P3-377 AC②：条目主机名的三档同站归一
     * （调用前提：既有严格匹配已判未命中）。档位与 Monica 层级对齐：
     * - 去 www 归一相等 ⇒ EXACT_DOMAIN（140）；
     * - 条目主机名以 `.<目标域>` 结尾（严格点号标签边界）⇒ SUBDOMAIN_OF_ORIGIN（115）；
     * - 同可注册域（eTLD+1 相等，兄弟子域）⇒ SAME_BASE_DOMAIN（100）——
     *   经 PSL 计算，私有段兄弟（`foo.github.io` vs `bar.github.io`）自然不命中；
     *   IP / 资源不可用 ⇒ registrableDomain 返回 null 恒不命中（fail-closed）。
     */
    private fun applySameSiteTiers(entryUrl: String, webDomain: String): SameSiteTier? {
        val entryHost = DomainMatcher.extractDomain(entryUrl)
        if (entryHost.isEmpty()) return null
        if (entryHost.startsWith(WWW_PREFIX)) {
            val stripped = entryHost.removePrefix(WWW_PREFIX)
            if (stripped.isNotEmpty() && stripped == webDomain) {
                return SameSiteTier(SCORE_EXACT_DOMAIN, MatchReason.EXACT_DOMAIN)
            }
        }
        if (entryHost.endsWith(".$webDomain")) {
            return SameSiteTier(SCORE_SUBDOMAIN_OF_ORIGIN, MatchReason.SUBDOMAIN_OF_ORIGIN)
        }
        val entryBase = PublicSuffixList.registrableDomain(entryHost)
        val originBase = PublicSuffixList.registrableDomain(webDomain)
        if (entryBase != null && entryBase == originBase) {
            return SameSiteTier(SCORE_BASE_DOMAIN, MatchReason.SAME_BASE_DOMAIN)
        }
        return null
    }

    /**
     * ISSUE-P3-372 AC④：条目标题与调用方应用名是否相似（纯排序维度）。
     * 归一化 = 小写 + 去首尾空白 + 折叠内部空白；两侧归一后等值或互含即算相似。
     * 双侧归一长度须 ≥ [MIN_APP_LABEL_LENGTH]（单字符标签互含无鉴别力，恒不加成）。
     */
    internal fun titleMatchesAppLabel(title: String, appLabel: String): Boolean {
        val t = normalizeForLabel(title)
        val l = normalizeForLabel(appLabel)
        if (t.length < MIN_APP_LABEL_LENGTH || l.length < MIN_APP_LABEL_LENGTH) return false
        return t == l || t.contains(l) || l.contains(t)
    }

    private fun normalizeForLabel(value: String): String =
        value.trim().lowercase().replace(WHITESPACE_REGEX, "")

    /** `www.` 主机前缀（归一化剥离用，见 [applySameSiteTiers]） */
    private const val WWW_PREFIX = "www."

    /** 应用名相似判定的最小归一长度（单字符 / 空白标签无鉴别力） */
    private const val MIN_APP_LABEL_LENGTH = 2

    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * 判定是否为「精确域名」匹配：取实际命中的域名来源（passkey RP ID 优先）与目标域比较。
     * 注意：本方法仅在 [DomainMatcher.isDomainMatch] 已通过的前提下调用。
     *
     * `ISSUE-P3-171`：`entryUrl` 由调用方预先读出并下传（原实现每次内部再访问一次 `entry.url` 属性
     * getter ⇒ 又一次解密 + String 物化）。
     */
    private fun isExactDomain(entryUrl: String, passkey: PasskeyData?, webDomain: String): Boolean {
        val source = if (passkey != null && DomainMatcher.isDomainMatch(passkey.relyingPartyId, webDomain)) {
            passkey.relyingPartyId
        } else {
            entryUrl
        }
        return DomainMatcher.extractDomain(source) == webDomain
    }
}
