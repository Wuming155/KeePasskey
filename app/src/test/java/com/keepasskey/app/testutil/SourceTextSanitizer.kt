package com.keepasskey.app.testutil

/**
 * 静态源码守卫的「剥离注释」公共实现（§156 收敛，两份守卫原本各写一份）。
 *
 * **为何必须先剥字符串字面量**：块注释的正则只看字符序列，不认识 Kotlin 的字符串。
 * 本仓 UI 代码里的 SAF 通配过滤器字面量（`arrayOf` 里那串「星、斜杠、星」）同时含
 * 「斜杠星」与「星斜杠」两个子串——于是从前一处该字面量的开注释符到后一处该字面量的闭注释符
 * 之间的**全部真实代码**会被当成注释抹掉。
 * 实测后果（§156）：`ExportTicketSinkGuardTest` 扫 `DatabaseSettingsScreen.kt` 时三处调用点只剩一处；
 * 更严重的是它「全仓 `app/src/main` 有无第二处 `ExportTicket` 实现」的扫描**同样**在被吞区间上失明
 * ⇒ 该安全守卫存在**假阴性**风险。故一律：**字符串 → 块注释 → 行注释**。
 */
private val TRIPLE_QUOTED = Regex("\"\"\"[\\s\\S]*?\"\"\"")
private val DOUBLE_QUOTED = Regex("\"[^\"\n]*\"")
private val CHAR_LITERAL = Regex("'[^'\n]'")
private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

/** 去掉字符串字面量与注释，只留代码骨架（供源码文本断言使用）。 */
fun stripStringsAndComments(source: String): String {
    val withoutLiterals = source
        .replace(TRIPLE_QUOTED, "\"\"")
        .replace(DOUBLE_QUOTED, "\"\"")
        .replace(CHAR_LITERAL, "' '")
    val withoutBlock = BLOCK_COMMENT.replace(withoutLiterals, "")
    return LINE_COMMENT.replace(withoutBlock, "")
}

/**
 * **只去注释、保留字符串内容**（§157，`ISSUE-P3-194` 收口时的两种口径之一）。
 *
 * 适用面更广：断言目标含**字面量本身**的守卫（如「加密算法标签必须唯一」要比对
 * `"ChaCha20 (256-bit)"` 这类字符串）不能用 [stripStringsAndComments]——那会把被查找的字面量一并抹掉。
 * 本实现用**逐字符扫描**记录「在字符串内 / 在注释内」状态，因此字符串里的开注释符与闭注释符
 * 不再造成误配对（原假阴性根因），而注释照旧被剔除。
 */
fun stripCommentsOnly(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        when {
            source.startsWith(STRING_TRIPLE, i) -> {
                val end = source.indexOf(STRING_TRIPLE, i + 3)
                if (end < 0) { out.append(source, i, source.length); i = source.length }
                else { out.append(source, i, end + 3); i = end + 3 }
            }
            source.startsWith(LINE_COMMENT_MARK, i) -> {
                val end = source.indexOf('\n', i)
                i = if (end < 0) source.length else end
            }
            source.startsWith(BLOCK_COMMENT_OPEN, i) -> {
                val end = source.indexOf(BLOCK_COMMENT_CLOSE, i + 2)
                i = if (end < 0) source.length else end + 2
            }
            source[i] == '"' || source[i] == '\'' -> {
                val quote = source[i]
                var j = i + 1
                while (j < source.length && source[j] != quote && source[j] != '\n') {
                    if (source[j] == '\\') j++
                    j++
                }
                val end = if (j < source.length && source[j] == quote) j + 1 else j
                out.append(source, i, end)
                i = end
            }
            else -> { out.append(source[i]); i++ }
        }
    }
    return out.toString()
}

private const val STRING_TRIPLE = "\"\"\""
private const val LINE_COMMENT_MARK = "//"
private const val BLOCK_COMMENT_OPEN = "/*"
private const val BLOCK_COMMENT_CLOSE = "*/"
