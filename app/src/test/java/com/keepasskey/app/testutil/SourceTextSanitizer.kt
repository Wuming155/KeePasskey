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
