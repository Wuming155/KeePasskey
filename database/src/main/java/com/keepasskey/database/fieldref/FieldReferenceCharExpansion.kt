package com.keepasskey.database.fieldref

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import java.util.Arrays

/**
 * `{REF:...}` 字段引用展开的 **Char 通道**（ISSUE-P3-394）。
 *
 * 健康检查的强度评估要把展开后的真实口令喂给 `crypto` 强度引擎；若走
 * [FieldReferenceEngine.resolve]（产物为 `String`），被引用条目的口令明文会物化进 JVM 堆
 * 字符串——违反敏感数据铁律（主密码 / 字段值只用 `CharArray` / `ByteArray` 承载并显式清零，
 * 绝不落地为 `String`，严禁进日志）。本通道与 `resolve`（`consumerField = PASSWORD`
 * 口令消费点、VALUE 模式）**语义逐分支对齐**，唯一差异是容器：
 *
 * - 语法 `{REF:<Want>@<SearchIn>:<Text>}`：字段代码（`T/U/P/A/N/I`）大小写不敏感、
 *   检索文本禁花括号——与 String 引擎的引用正则同一套契约（字符级扫描器替代正则，
 *   正则无法作用在 CharArray 上）；
 * - 口令消费点（`Want=P` 或 `SearchIn=P`）按 KDBX 语义展开受保护字段，**不做掩码**
 *   （对齐 ISSUE-P0-08 消费点面白名单的 P 面；掩码只属非口令消费点 / 展示侧）；
 * - 深度超 [FieldReferenceEngine.MAX_DEPTH]、展开次数超 [FieldReferenceEngine.MAX_EXPANSIONS]、
 *   产出字符超 [FieldReferenceEngine.MAX_PRODUCED_CHARS]、引用未命中——一律**保留该引用
 *   原文**：不抛错、不吞数据，与 String 引擎的保守语义逐字一致；
 * - 伪引用（前缀命中但语法不合法）：该位置只消费一个 `{` 后继续右移扫描——与正则
 *   「匹配失败继续右移、后续真引用照常展开」的行为一致；
 * - 检索键等值口径：与 String 引擎的 `String.CASE_INSENSITIVE_ORDER` 同一逐字符三折
 *   （相等 / 大写折等 / 小写折等），同值取**文档序首个**。
 *
 * 敏感数据治理（本通道存在的理由）：
 * - 展开产物与全部中间副本只以 `CharArray` 存在，通道内用毕立即清零；返回值由**调用方**
 *   负责用毕清零；
 * - 检索面 `SearchIn=P`（按口令检索）**不建** String 索引（那会把全库口令物化成 String），
 *   改对候选条目口令做 CharArray 区域比较——被比较的检索文本同样只以 Char 形态存在；
 * - `SearchIn ∈ {T,U,A,N,I}` 时检索键为公开字段文本，物化 String 与领域模型对这些字段的
 *   既有 String 暴露同面（`KdbxEntry.title` 等本就是 String），不新增敏感面。
 *
 * 防漂移约定：改动任一侧（本通道或 [FieldReferenceEngine]）的解析 / 闸门 / 检索语义时，
 * **必须**同步核对另一侧，并跑两侧的对照单测（`FieldReferenceEngineTest` /
 * `FieldReferenceCharExpansionTest`，逐场景锁定两通道同答）。
 */
internal object FieldReferenceCharExpansion {

    /** 引用前缀字面量（与 [FieldReferenceEngine.containsReference] 探测的子串一致） */
    private const val REF_PREFIX = "{REF:"

    /**
     * 口令面展开入口。
     *
     * @param text 条目口令原文的 Char 形态（敏感，本函数只读不改、不保留引用）
     * @param root 引用检索根（库根分组；引擎只消费 `root.allEntries()`）
     * @return [text] 不含引用时返回 `null`（调用方零成本直用原文）；否则返回展开结果——
     * **敏感 CharArray，调用方用毕必须显式清零**。
     */
    internal fun resolvePasswordFace(text: CharArray, root: KdbxGroup): CharArray? {
        if (!containsReferenceIgnoreCase(text)) return null
        // 索引检索面只放公开字段（P 返回 null，见 [publicFieldValueOrNull]）：
        // 按口令检索由 [findTarget] 走 Char 区域比较专路，全库口令绝不物化成检索键
        val index = FieldReferenceEngine.RefIndex(root) { entry, field ->
            publicFieldValueOrNull(entry, field)
        }
        return expand(text, index, depth = 0, budget = FieldReferenceEngine.ExpansionBudget())
    }

    /** String 索引只收公开字段；`P` 返回 null——口令检索不走 String 键（敏感数据铁律） */
    private fun publicFieldValueOrNull(entry: KdbxEntry, field: FieldReferenceEngine.RefField): String? =
        if (field == FieldReferenceEngine.RefField.PASSWORD) {
            null
        } else {
            FieldReferenceEngine.valueOf(entry, field)
        }

    /** 与 [FieldReferenceEngine.resolveInternal]（VALUE + 口令消费点）逐分支对齐的 Char 版递归 */
    private fun expand(
        text: CharArray,
        index: FieldReferenceEngine.RefIndex,
        depth: Int,
        budget: FieldReferenceEngine.ExpansionBudget
    ): CharArray {
        // 深度超限原样返回（对齐引擎；返回副本使调用方对全部产物统一清零）
        if (depth > FieldReferenceEngine.MAX_DEPTH) return text.copyOf()
        val out = CharSink()
        var cursor = 0
        while (cursor < text.size) {
            val refStart = indexOfRefPrefix(text, cursor)
            if (refStart < 0) {
                out.append(text, cursor, text.size)
                break
            }
            out.append(text, cursor, refStart)
            val parsed = parseRef(text, refStart)
            if (parsed == null) {
                // 伪引用：只消费一个 '{' 后继续右移（对齐正则语义，后续真引用照常展开）
                out.append('{')
                cursor = refStart + 1
                continue
            }
            cursor = appendResolution(parsed, text, index, depth, budget, out)
        }
        return out.finish()
    }

    /** 解析单个引用并按引擎语义把结果（或回退原文）写入 [out]；返回新的扫描游标 */
    private fun appendResolution(
        ref: ParsedRef,
        text: CharArray,
        index: FieldReferenceEngine.RefIndex,
        depth: Int,
        budget: FieldReferenceEngine.ExpansionBudget,
        out: CharSink
    ): Int {
        // 展开次数闸门：超限保留原文（对齐引擎——先于检索认领）
        if (!budget.tryClaimExpansion()) return ref.endExclusive
        val target = findTarget(ref, text, index)
        if (target == null) {
            out.append(text, ref.refStart, ref.endExclusive) // 未命中：保守不吞
            return ref.endExclusive
        }
        if (ref.wantField == FieldReferenceEngine.RefField.PASSWORD) {
            // 取值面 P：目标口令以 Char 读出（敏感副本），递归展开后在本层清零
            val raw = target.password?.readChars() ?: CharArray(0)
            val expanded = try {
                expand(raw, index, depth + 1, budget)
            } finally {
                Arrays.fill(raw, '0')
            }
            claimAndAppend(expanded, ref, text, budget, out)
            return ref.endExclusive
        }
        // 取值面为公开字段：值为模型侧既有 String（title/userName/url/notes/uuid）；
        // 值自身仍含引用则继续递归展开（引擎同语义），副本用毕清零
        val value = FieldReferenceEngine.valueOf(target, ref.wantField).orEmpty()
        if (!FieldReferenceEngine.containsReference(value)) {
            if (budget.tryClaimChars(value.length)) out.append(value) else out.appendLiteralOf(ref, text)
            return ref.endExclusive
        }
        val raw = value.toCharArray()
        val expanded = try {
            expand(raw, index, depth + 1, budget)
        } finally {
            Arrays.fill(raw, '0')
        }
        claimAndAppend(expanded, ref, text, budget, out)
        return ref.endExclusive
    }

    /** 产出体积闸门：超限保留引用原文（对齐引擎）；敏感副本无论去向用毕清零 */
    private fun claimAndAppend(
        expanded: CharArray,
        ref: ParsedRef,
        text: CharArray,
        budget: FieldReferenceEngine.ExpansionBudget,
        out: CharSink
    ) {
        if (budget.tryClaimChars(expanded.size)) {
            out.append(expanded)
        } else {
            out.appendLiteralOf(ref, text)
        }
        Arrays.fill(expanded, '0')
    }

    /** 目标定位：`SearchIn=P` 走 Char 区域比较线性扫描（文档序首个，与索引同答），其余走共享 String 索引 */
    private fun findTarget(
        ref: ParsedRef,
        text: CharArray,
        index: FieldReferenceEngine.RefIndex
    ): KdbxEntry? {
        if (ref.searchField == FieldReferenceEngine.RefField.PASSWORD) {
            for (entry in index.entries) {
                val candidate = entry.password?.readChars() ?: continue
                val hit = try {
                    regionEqualsIgnoreCase(text, ref.searchFrom, ref.searchTo, candidate)
                } finally {
                    Arrays.fill(candidate, '0')
                }
                if (hit) return entry
            }
            return null
        }
        // 检索键为公开字段文本（模型对 T/U/A/N/I 的既有 String 暴露同面，非敏感新增）
        val key = String(text, ref.searchFrom, ref.searchTo - ref.searchFrom)
        return index.find(ref.searchField, key)
    }

    /** 已解析的单个引用：取值面 / 检索面 / 检索文本区间 / 引用原文区间 */
    private class ParsedRef(
        val wantField: FieldReferenceEngine.RefField,
        val searchField: FieldReferenceEngine.RefField,
        val refStart: Int,
        val searchFrom: Int,
        val searchTo: Int,
        val endExclusive: Int
    )

    /**
     * 字符级语法解析（对齐 String 引擎的正则 `\{REF:([TUAPNI])@([TUAPNI]):([^\{\}]*)\}`）：
     * 前缀后依次为「取值面代码、`@`、检索面代码、`:`」，检索文本到首个花括号为止且必须以
     * `}` 收尾；不合法返回 `null`（调用方按伪引用保留原文）。
     * 与 [FieldReferenceEngine.fieldOf] 的代码→字段映射须保持同集合同序（防漂移对照点）。
     */
    private fun parseRef(text: CharArray, refStart: Int): ParsedRef? {
        var j = refStart + REF_PREFIX.length
        val want = fieldCodeAt(text, j) ?: return null
        j++
        if (j >= text.size || text[j] != '@') return null
        j++
        val search = fieldCodeAt(text, j) ?: return null
        j++
        if (j >= text.size || text[j] != ':') return null
        j++
        var k = j
        while (k < text.size && text[k] != '{' && text[k] != '}') k++
        if (k >= text.size || text[k] != '}') return null
        return ParsedRef(want, search, refStart, j, k, k + 1)
    }

    /** 字段代码单字符解析（大小写不敏感；集合与 [FieldReferenceEngine.fieldOf] 一致，非法返回 null） */
    private fun fieldCodeAt(text: CharArray, index: Int): FieldReferenceEngine.RefField? {
        if (index >= text.size) return null
        return when (Character.toUpperCase(text[index])) {
            'T' -> FieldReferenceEngine.RefField.TITLE
            'U' -> FieldReferenceEngine.RefField.USER_NAME
            'P' -> FieldReferenceEngine.RefField.PASSWORD
            'A' -> FieldReferenceEngine.RefField.URL
            'N' -> FieldReferenceEngine.RefField.NOTES
            'I' -> FieldReferenceEngine.RefField.UUID
            else -> null
        }
    }

    /** 与引擎 `contains("{REF:", ignoreCase = true)` 同口径的 Char 版探测 */
    private fun containsReferenceIgnoreCase(text: CharArray): Boolean =
        indexOfRefPrefix(text, 0) >= 0

    /** 自 [from] 起首个 `{REF:`（大小写不敏感）的位置，无则 -1 */
    private fun indexOfRefPrefix(text: CharArray, from: Int): Int {
        var i = from
        while (i <= text.size - REF_PREFIX.length) {
            if (charEqualsIgnoreCase(text[i], '{') &&
                charEqualsIgnoreCase(text[i + 1], 'R') &&
                charEqualsIgnoreCase(text[i + 2], 'E') &&
                charEqualsIgnoreCase(text[i + 3], 'F') &&
                charEqualsIgnoreCase(text[i + 4], ':')
            ) {
                return i
            }
            i++
        }
        return -1
    }

    /**
     * 与 Kotlin `Char.equals(other, ignoreCase = true)` 同一三折口径
     * （相等 / 大写折等 / 小写折等）——引擎的 `contains(ignoreCase)` 与
     * `String.CASE_INSENSITIVE_ORDER` 在该口径下逐字符一致。
     */
    private fun charEqualsIgnoreCase(a: Char, b: Char): Boolean =
        a == b || Character.toUpperCase(a) == Character.toUpperCase(b) ||
            Character.toLowerCase(a) == Character.toLowerCase(b)

    /** [from, to) 区域与 [other] 全串比较（长度不等即否），口径同 [charEqualsIgnoreCase] */
    private fun regionEqualsIgnoreCase(source: CharArray, from: Int, to: Int, other: CharArray): Boolean {
        if (to - from != other.size) return false
        for (i in other.indices) {
            if (!charEqualsIgnoreCase(source[from + i], other[i])) return false
        }
        return true
    }

    /** 可增长 Char 缓冲：[finish] 裁剪为精确长度并清零工作缓冲（工作缓冲承载过敏感明文） */
    private class CharSink {
        private var data = CharArray(64)
        private var size = 0

        fun append(source: CharArray, from: Int, to: Int) {
            ensure(to - from)
            System.arraycopy(source, from, data, size, to - from)
            size += to - from
        }

        fun append(source: CharArray) = append(source, 0, source.size)

        /** 单字符追加（伪引用只消费 `{` 时使用） */
        fun append(char: Char) {
            ensure(1)
            data[size] = char
            size++
        }

        /** String 版：仅用于公开字段值（模型侧既有 String），复制进 Char 缓冲 */
        fun append(source: String) {
            ensure(source.length)
            source.toCharArray(data, size, 0, source.length)
            size += source.length
        }

        /** 引用原文回退（含起止区间的具名表达，替代散落的下标对） */
        fun appendLiteralOf(ref: ParsedRef, text: CharArray) = append(text, ref.refStart, ref.endExclusive)

        private fun ensure(count: Int) {
            if (size + count > data.size) {
                data = data.copyOf(maxOf(data.size * 2, size + count))
            }
        }

        /** 产出精确长度的结果；工作缓冲清零后弃置 */
        fun finish(): CharArray {
            val result = data.copyOf(size)
            Arrays.fill(data, '0')
            return result
        }
    }
}
