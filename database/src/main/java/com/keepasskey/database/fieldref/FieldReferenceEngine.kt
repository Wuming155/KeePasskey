package com.keepasskey.database.fieldref

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup

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
 * 两条解析通道（ISSUE-P3-02 / TASK-49）：
 * - [resolve]：**取值消费点**（自动填充下发、详情页复制）——受保护字段可按 KDBX 语义展开；
 * - [resolveForDisplay]：**展示侧**（Notes / URL 渲染）——受保护字段（取值面或检索面为 `P`）
 *   一律输出 [PROTECTED_PLACEHOLDER] 掩码，**任何递归深度都不物化受保护值**，
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

    /** 解析 [text] 中全部字段引用；[root] 为库根分组 */
    fun resolve(text: String, root: KdbxGroup): String =
        resolveWith(text, root, ResolveMode.VALUE, PROTECTED_PLACEHOLDER)

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
    ): String = resolveWith(text, root, ResolveMode.DISPLAY, protectedPlaceholder)

    private fun resolveWith(
        text: String,
        root: KdbxGroup,
        mode: ResolveMode,
        protectedPlaceholder: String
    ): String =
        if (!containsReference(text)) text
        else resolveInternal(text, root, depth = 0, mode = mode, protectedPlaceholder = protectedPlaceholder)

    private fun resolveInternal(
        text: String,
        root: KdbxGroup,
        depth: Int,
        mode: ResolveMode,
        protectedPlaceholder: String
    ): String {
        if (depth > MAX_DEPTH) return text
        return REF_REGEX.replace(text) { match ->
            val wantField = fieldOf(match.groupValues[1])
            val searchField = fieldOf(match.groupValues[2])
            val searchText = match.groupValues[3]

            if (mode == ResolveMode.DISPLAY && isProtected(wantField, searchField)) {
                // 展示侧不物化受保护值：掩码占位后不再递归（掩码本身不含引用）
                return@replace protectedPlaceholder
            }

            val target = root.allEntries().firstOrNull { entry ->
                valueOf(entry, searchField).equals(searchText, ignoreCase = true)
            }
            when {
                // 未命中：保持原文（保守不吞）
                target == null -> match.value
                // 命中：取值并递归展开（值本身可能仍是引用链）
                else -> resolveInternal(
                    valueOf(target, wantField).orEmpty(),
                    root,
                    depth + 1,
                    mode,
                    protectedPlaceholder
                )
            }
        }
    }

    /** 展示侧受保护判定：取值面或检索面命中受保护字段即为真 */
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
