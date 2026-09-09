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
 * 调用时机约定：仅在取值消费点（自动填充下发、详情页复制）解析——
 * 投影层不做展开，避免引用指向的密码明文提前物化进 UI 状态流。
 */
object FieldReferenceEngine {

    /** 引用字段代码（KeePass 2.x 官方定义） */
    enum class RefField {
        TITLE, USER_NAME, PASSWORD, URL, NOTES, UUID
    }

    /**
     * `{REF:W@S:text}` 匹配：字段代码单字符、SearchText 不允许出现花括号
     * （KeePass 语义：SearchText 中如需引用须使用嵌套占位符，由递归展开处理其值侧）。
     */
    private val REF_REGEX = Regex(
        pattern = """\{REF:([TUAPNI])@([TUAPNI]):([^{}]*)}""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    /** 递归展开深度上限：防 A→B→A 循环引用导致无限递归 */
    private const val MAX_DEPTH = 10

    /** 快速短路：文本不含 `{REF:` 时零开销直返 */
    fun containsReference(text: String): Boolean =
        text.contains("{REF:", ignoreCase = true)

    /** 解析 [text] 中全部字段引用；[root] 为库根分组 */
    fun resolve(text: String, root: KdbxGroup): String =
        if (!containsReference(text)) text else resolveInternal(text, root, depth = 0)

    private fun resolveInternal(text: String, root: KdbxGroup, depth: Int): String {
        if (depth > MAX_DEPTH) return text
        return REF_REGEX.replace(text) { match ->
            val wantField = fieldOf(match.groupValues[1])
            val searchField = fieldOf(match.groupValues[2])
            val searchText = match.groupValues[3]

            val target = root.allEntries().firstOrNull { entry ->
                valueOf(entry, searchField).equals(searchText, ignoreCase = true)
            }
            when {
                // 未命中：保持原文（保守不吞）
                target == null -> match.value
                // 命中：取值并递归展开（值本身可能仍是引用链）
                else -> resolveInternal(valueOf(target, wantField).orEmpty(), root, depth + 1)
            }
        }
    }

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
