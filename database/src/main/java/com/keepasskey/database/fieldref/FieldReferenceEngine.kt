package com.keepasskey.database.fieldref

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import java.util.TreeMap

/**
 * KeePass 字段引用（`{REF:...}`）解析引擎（TASK-17）。
 *
 * 语法（对齐 KeePass 2.x 官方 Spr 引擎的字段引用子集）：
 * ```
 * {REF:<WantField>@<SearchInField>:<SearchText>}
 * ```
 * 字段代码（大小写不敏感）：`T`=Title `U`=UserName `P`=Password `A`=URL `N`=Notes `I`=UUID。
 * 解析语义：在整库条目树中检索 [SearchInField] 值等于 [SearchText] 的首个条目，
 * 取其 [WantField] 字段值替换占位符；解析出的值若仍含引用则继续递归展开
 * （深度上限 [MAX_DEPTH] 防循环引用无限递归，超限后原样返回不再展开）。
 * 未命中的引用保持原文（保守策略：宁缺毋错，不静默吞掉用户数据）。
 *
 * ISSUE-P2-200：仅封深度**不足以**约束产物——分支因子（目标字段内引用个数）会让
 * `k^depth` 相乘放大（自引用 `{REF:N@T:<自身标题>}` 即可构造）。故每次解析另设
 * [MAX_EXPANSIONS]（展开次数）与 [MAX_PRODUCED_CHARS]（产出字符数）双闸门，
 * 二者超限**一律逐段保留引用原文**：不抛错、不吞数据，与未命中 / 超深的既有语义一致。
 *
 * 两条解析通道（ISSUE-P3-02 / TASK-49）：
 * - [resolve]：**取值消费点**（自动填充下发、详情页复制）——**必须显式声明消费点面**
 *   （[consumerField]）：口令消费点（`P`）可按 KDBX 语义展开受保护字段；**非口令消费点
 *   （`T/U/A/N/I`）命中受保护字段（取值面或检索面为 `P`）时一律输出
 *   [PROTECTED_PLACEHOLDER] 掩码**（ISSUE-P0-08 消费点面白名单）——否则 `UserName` 里的
 *   `{REF:P@…}` 会把被引用条目的口令明文经用户名通道送出应用（RemoteViews / IME 内联建议 /
 *   确认页 extra / 请求方输入框），一条根因四个泄漏出口；
 * - [resolveForDisplay]：**展示侧**（Notes / URL 渲染）——受保护字段一律输出
 *   [PROTECTED_PLACEHOLDER] 掩码，**任何递归深度都不物化受保护值**，
 *   以维持 M1 投影层「不把密码明文物化进 UI 状态流」的约束。
 *
 * 调用时机约定：投影层不做展开（投影只下发原文），展开结果由状态层按需装配。
 */
object FieldReferenceEngine {

    /** 引用字段代码（KeePass 2.x 官方定义） */
    enum class RefField {
        TITLE, USER_NAME, PASSWORD, URL, NOTES, UUID
    }

    /** 展示侧受保护字段引用的掩码占位（可见「此处有一个受保护引用」，但值不外泄） */
    const val PROTECTED_PLACEHOLDER: String = "••••••••"

    /**
     * 递归展开深度上限：防 A→B→A 循环引用导致无限递归（超限后原样返回，不崩溃）。
     * 公开为常量，便于调用方与单测按同一上限断言超深引用行为。
     */
    const val MAX_DEPTH: Int = 10

    /**
     * ISSUE-P2-200 落点②：单次解析的**展开次数**上限（分支计数闸门）。
     *
     * ## 为什么深度上限不够
     * [MAX_DEPTH] 只封递归层数，**不封每层的展开个数**：`{REF:W@S:text}` 的替换结果会作为
     * 下一层的输入继续展开，分支因子等于「目标字段内引用个数」。于是一个自引用
     * `{REF:N@T:<自身标题>}`（目标是本条目，检索键恰为自身标题）即可让每层引用数相乘——
     * 字段内放 k 个引用、深度 11（d=0..10 均执行替换）即产生 `k^11` 个叶展开：
     * `k=4`、输入不足 1 KB 即 ~4×10⁶ 次展开，而**输出长度无上界**。
     *
     * ## 取值依据
     * 合法场景（展示 Notes/URL、填充账密）中引用链是**线性**的：一次解析的展开次数与
     * 「文本内引用数 × 链长」同阶，实际为个位数到几十。4000 足足高出两个数量级；
     * 而放大构造需要 10⁵ 次以上展开才会体现「爆炸」，故该闸门必然先于内存耗尽触发。
     */
    const val MAX_EXPANSIONS: Int = 4_000

    /**
     * ISSUE-P2-200 落点②：单次解析的**产出字符数**上限（输出预算闸门，UTF-16 字符计）。
     *
     * 与 [MAX_EXPANSIONS] 构成双闸门：次数闸门封「相乘的分支数」，本闸门封「每次展开的产出
     * 体积」。二者缺一都会被绕过——只有次数闸门时，k 很小但单个目标的字段极大仍可产出巨串；
     * 只有体积闸门时，海量小展开的 CPU / 分配开销不受约束。
     *
     * 超限的引用**原样保留未展开段**（`{REF:...}` 原文），既不抛错也不吞掉用户数据——
     * 与 [MAX_DEPTH] 超限、未命中引用的既有保守语义完全一致。
     *
     * 1 MiB 的选取：合法展开产物与字段本身同阶（正常为几 KB），高出三个数量级仍不误伤；
     * 而在真机上该量级的内存峰值（数十 MiB 的瞬时串）远低于任何设备的堆界。
     */
    const val MAX_PRODUCED_CHARS: Int = 1 shl 20

    /**
     * `{REF:W@S:text}` 匹配：字段代码单字符、SearchText 不允许出现花括号
     * （KeePass 语义：SearchText 中如需引用须使用嵌套占位符，由递归展开处理其值侧）。
     *
     * **花括号必须全部转义（ISSUE-P0-04）**：JVM 的 `java.util.regex` 容忍未转义的 `}`，
     * 但 Android 运行时走 ICU4C，会把结尾未转义的 `}` 判为语法错误 →
     * `<clinit>` 抛 [ExceptionInInitializerError]，而本类在库列表逐条目投影中被初始化，
     * 因此「库内 ≥1 条目」即导致列表渲染崩溃。设备侧回归见
     * `database/src/androidTest/.../FieldReferenceEngineDeviceTest`。
     */
    private val REF_REGEX = Regex(
        pattern = """\{REF:([TUAPNI])@([TUAPNI]):([^\{\}]*)\}""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    /** 解析模式：区分「取值」与「展示」两类消费点对受保护字段的处理 */
    private enum class ResolveMode {
        /** 取值消费点：受保护字段按引擎既有语义展开 */
        VALUE,

        /** 展示侧：受保护字段一律掩码，绝不物化明文 */
        DISPLAY
    }

    /** 快速短路：文本不含 `{REF:` 时零开销直返 */
    fun containsReference(text: String): Boolean =
        text.contains("{REF:", ignoreCase = true)

    /**
     * 检测 [text] 是否含**口令面**引用（取值面或检索面为 `P`，ISSUE-P1-25 AC①）。
     *
     * 取值消费点白名单（ISSUE-P0-08）会在引擎内把此类引用掩码输出，但**复制通道**仍须
     * 据此选择敏感复制路径（`EXTRA_IS_SENSITIVE` + 调度自动擦除）：即便内容已是掩码，
     * 「该字段携有指向口令的引用」这一事实本身就应触发敏感数据处理，作为白名单被
     * 未来改动削弱时的纵深防线。
     */
    fun containsPasswordFaceReference(text: String): Boolean {
        if (!containsReference(text)) return false
        return REF_REGEX.findAll(text).any { match ->
            isProtected(fieldOf(match.groupValues[1]), fieldOf(match.groupValues[2]))
        }
    }

    /**
     * 解析 [text] 中全部字段引用；[root] 为库根分组。
     *
     * [consumerField] 为**消费点面白名单**（ISSUE-P0-08）：声明本次解析结果将进入哪个字段通道。
     * 口令消费点（[RefField.PASSWORD]）可展开受保护字段；其余消费点（`T/U/A/N/I`）在
     * 取值面或检索面命中 [RefField.PASSWORD] 时输出 [PROTECTED_PLACEHOLDER] 掩码
     * （递归展开的任何深度同此约束），绝不物化被引用条目的口令明文。
     */
    fun resolve(text: String, root: KdbxGroup, consumerField: RefField): String =
        resolveWith(text, root, ResolveMode.VALUE, PROTECTED_PLACEHOLDER, consumerField)

    /**
     * 展示侧解析（ISSUE-P3-02）：仅展开**公开字段**引用。
     *
     * 取值面或检索面为受保护字段（`P`=Password）的引用一律以 [protectedPlaceholder] 替换，
     * 且该掩码在递归展开的任何深度都不被绕开——引用目标的其他公开字段若自身含受保护引用，
     * 同样按掩码输出。深度上限与 [resolve] 共用 [MAX_DEPTH]。
     */
    fun resolveForDisplay(
        text: String,
        root: KdbxGroup,
        protectedPlaceholder: String = PROTECTED_PLACEHOLDER
    ): String = resolveWith(text, root, ResolveMode.DISPLAY, protectedPlaceholder, consumerField = null)

    private fun resolveWith(
        text: String,
        root: KdbxGroup,
        mode: ResolveMode,
        protectedPlaceholder: String,
        consumerField: RefField?
    ): String =
        if (!containsReference(text)) text
        else resolveInternal(
            text, RefIndex(root, ::valueOf), depth = 0, mode = mode,
            protectedPlaceholder = protectedPlaceholder, consumerField = consumerField,
            // ISSUE-P2-200：双闸门**每次解析独享**（不作为跨调用状态），超限即逐段保留原文
            budget = ExpansionBudget()
        )

    /**
     * ISSUE-P2-200 落点②：一次解析内的展开预算（次数 + 产出字符数双闸门）。
     *
     * 计数器**每层递归共享**，故「指数展开」在总次数上受限而非逐层各自计数——
     * 逐层计数会被「每层都在额度内」的形式绕过（这正是 `k^depth` 放大的成因）。
     *
     * 超限**不抛异常**：调用方返回该引用的原文（`match.value`），与未命中 / 超深引用的
     * 既有保守语义一致（宁缺毋错，绝不吞掉用户数据）。
     */
    private class ExpansionBudget {
        private var expansions = 0
        private var producedChars = 0L

        /** 认领一次展开；额度耗尽返回 false（调用方保留原文） */
        fun tryClaimExpansion(): Boolean {
            if (expansions >= MAX_EXPANSIONS) return false
            expansions++
            return true
        }

        /** 认领一段产出字符；额度耗尽返回 false（调用方保留原文） */
        fun tryClaimChars(count: Int): Boolean {
            if (producedChars + count > MAX_PRODUCED_CHARS) return false
            producedChars += count
            return true
        }
    }

    /**
     * 一次解析内共享的**引用目标索引**（`ISSUE-P3-163`）。
     *
     * 原实现把 `root.allEntries()` 写在正则回调体内，而该回调**每个引用出现处执行一次**、
     * 且解析本身是递归的（每层各展平一次）⇒ 复杂度 `O(引用数 × 深度 × 条目数)` 次整树展平
     * （每次都是一遍递归遍历 + 一个新 `List` 分配）。本索引在解析入口建一次、沿递归全程共享：
     *
     * - 条目树**只展平一次**，且**惰性**——文本不含引用时根本不触发（[resolveWith] 已短路）；
     * - 按**检索字段**分桶的索引同样惰性构建，故「无人引用口令」时**不会**去解密口令，
     *   与既有「按需读取受保护字段」的成本面一致（若改为一次性建全部字段的索引，就会把
     *   全库口令都解密一遍——那是**反向优化**）；
     * - 查找语义与 `firstOrNull { valueOf(…).equals(searchText, ignoreCase = true) }` **逐字等价**：
     *   键用 `String.CASE_INSENSITIVE_ORDER`（与 `equalsIgnoreCase` 同一套逐字符折叠，
     *   故不会像 `lowercase()` 那样在希腊语末位 sigma 等码点上改变等价类），
     *   同值只保留**文档序首个**条目（`TreeMap` 的 `containsKey` 走同一比较器）。
     */
    private class RefIndex(
        root: KdbxGroup,
        private val valueOf: (KdbxEntry, RefField) -> String?
    ) {
        private val entries: List<KdbxEntry> by lazy { root.allEntries() }
        private val byField = mutableMapOf<RefField, TreeMap<String, KdbxEntry>>()

        fun find(field: RefField, text: String): KdbxEntry? = indexOf(field)[text]

        private fun indexOf(field: RefField): TreeMap<String, KdbxEntry> =
            byField.getOrPut(field) {
                val index = TreeMap<String, KdbxEntry>(String.CASE_INSENSITIVE_ORDER)
                for (entry in entries) {
                    val value = valueOf(entry, field) ?: continue
                    if (!index.containsKey(value)) index[value] = entry
                }
                index
            }
    }

    private fun resolveInternal(
        text: String,
        index: RefIndex,
        depth: Int,
        mode: ResolveMode,
        protectedPlaceholder: String,
        consumerField: RefField?,
        budget: ExpansionBudget
    ): String {
        if (depth > MAX_DEPTH) return text
        return REF_REGEX.replace(text) { match ->
            val wantField = fieldOf(match.groupValues[1])
            val searchField = fieldOf(match.groupValues[2])
            val searchText = match.groupValues[3]

            val protectedHit = isProtected(wantField, searchField)
            if (mode == ResolveMode.DISPLAY && protectedHit ||
                mode == ResolveMode.VALUE && consumerField != RefField.PASSWORD && protectedHit
            ) {
                // 展示侧：受保护值一律不物化；取值侧非口令消费点（ISSUE-P0-08 白名单）：同不物化。
                // 掩码占位后不再递归（掩码本身不含引用）
                return@replace protectedPlaceholder
            }

            // ISSUE-P2-200：展开次数闸门——超限即保留该引用原文（不再递归，也不吞原文）
            if (!budget.tryClaimExpansion()) return@replace match.value

            val target = index.find(searchField, searchText)
            when {
                // 未命中：保持原文（保守不吞）
                target == null -> match.value
                // 命中：取值并递归展开（值本身可能仍是引用链；消费点面白名单随通道全程传递）
                else -> {
                    val expanded = resolveInternal(
                        valueOf(target, wantField).orEmpty(),
                        index,
                        depth + 1,
                        mode,
                        protectedPlaceholder,
                        consumerField,
                        budget
                    )
                    // ISSUE-P2-200：产出体积闸门——超限即保留该引用原文（原文长度已计入输入侧，
                    // 不额外占用预算），使最终产物上界 ≈ 输入长度 + MAX_PRODUCED_CHARS
                    if (!budget.tryClaimChars(expanded.length)) match.value else expanded
                }
            }
        }
    }

    /** 受保护判定：取值面或检索面命中受保护字段即为真（展示侧与取值侧白名单共用） */
    private fun isProtected(wantField: RefField, searchField: RefField): Boolean =
        wantField == RefField.PASSWORD || searchField == RefField.PASSWORD

    private fun fieldOf(code: String): RefField = when (code.uppercase()) {
        "T" -> RefField.TITLE
        "U" -> RefField.USER_NAME
        "P" -> RefField.PASSWORD
        "A" -> RefField.URL
        "N" -> RefField.NOTES
        else -> RefField.UUID
    }

    private fun valueOf(entry: KdbxEntry, field: RefField): String? = when (field) {
        RefField.TITLE -> entry.title
        RefField.USER_NAME -> entry.userName
        RefField.PASSWORD -> entry.password?.readString()
        RefField.URL -> entry.url
        RefField.NOTES -> entry.notes
        RefField.UUID -> entry.id.toHexString()
    }
}
