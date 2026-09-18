package com.keepasskey.app.testutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码文本清洗工具的自检用例（`ISSUE-P3-194` 整改纪律 ③）。
 *
 * 存在理由：静态守卫的判据是**源码文本**，一旦清洗口径出错（把真实代码当注释抹掉），
 * 守卫会**静默失明**——不是变红，而是「看不见」。§156 实测到该形态：SAF 通配过滤器字面量
 * （「星、斜杠、星」那串）里的「斜杠星」被当成块注释起点，一路吞到下一个闭注释符，
 * 使被吞区间内的真实代码从守卫视野中消失（最坏单文件 10 KB 级）。
 *
 * **本文件刻意不直接写出那两个符号序列**（改用 [OPEN] / [CLOSE] 拼接）——
 * 否则自检用例自身就成了「注释符落在串内」的受害者，读到本文件的其它静态守卫会被误导。
 */
class SourceTextSanitizerTest {

    @Test
    fun `字符串内的注释符不得吞掉其后的真实代码`() {
        val source = lines(
            "package sample",
            "",
            "$BLOCK_COMMENT_MARK 真注释：这一行必须被抹掉 $BLOCK_COMMENT_END",
            "private val filter = arrayOf(\"$CLOSE$OPEN\") $LINE_COMMENT_MARK 行尾注释：抹掉",
            "private val sentinel = \"MUST_SURVIVE\"",
            "",
            "$DOC_MARK",
             " * 另一段真注释 $BLOCK_COMMENT_END",
            "private val second = arrayOf(\"$CLOSE$OPEN\")",
            "private val sentinel2 = \"MUST_SURVIVE_TOO\""
        )

        val commentsOnly = stripCommentsOnly(source)
        assertTrue(
            "字面量本身须保留（供「比对标签 / MIME 等字面量」的守卫使用）",
            commentsOnly.contains("\"MUST_SURVIVE\"") && commentsOnly.contains("\"MUST_SURVIVE_TOO\"")
        )
        assertFalse("真块注释须被抹掉", commentsOnly.contains("这一行必须被抹掉"))
        assertFalse("真行注释须被抹掉", commentsOnly.contains("行尾注释"))
        assertTrue("字符串之后的代码必须仍在视野内", commentsOnly.contains("private val sentinel2"))

        val codeOnly = stripStringsAndComments(source)
        assertTrue("代码骨架须保留", codeOnly.contains("private val sentinel2"))
        assertFalse("字面量内容须被抹掉", codeOnly.contains("MUST_SURVIVE"))
    }

    @Test
    fun `三引号与转义字符串不破坏扫描`() {
        val source = lines(
            "val raw = $TRIPLE raw 串内含 $OPEN 与 $CLOSE $TRIPLE",
            "val escaped = \"串内含转义引号 \\\"x\\\" 与 $OPEN\"",
            "val after = \"SENTINEL_AFTER\""
        )

        val stripped = stripCommentsOnly(source)
        assertTrue("其后的声明必须仍在视野内", stripped.contains("\"SENTINEL_AFTER\""))
        assertEquals(
            "三处声明都应保留（注释符全在字符串中，不应吞行）",
            3,
            Regex("val (raw|escaped|after)").findAll(stripped).count()
        )
        assertTrue("三引号串内容须原样保留", stripped.contains("raw 串内含"))
    }

    @Test
    fun `未闭合字符串不得让扫描越吃越多`() {
        val source = lines(
            "val broken = \"未闭合",
            "val real = \"AFTER_UNCLOSED\""
        )
        assertTrue(stripCommentsOnly(source).contains("AFTER_UNCLOSED"))
    }

    /**
     * **正向对照**（证明本工具不是无谓改动）：同一份样例源下，旧口径（直接拿块注释正则去replace）
     * 会把串内的闭注释符当配对点，从而**丢掉其后真实代码**；[stripCommentsOnly] 不丢。
     * 没有这条对照，「修好假阴性」只是口头主张——旧口径下守卫不会变红，只会**看不见**。
     */
    @Test
    fun `对照旧口径确实会丢代码`() {
        // 配对方向：先出现「开注释符」的串，再出现「闭注释符」的串，中间夹一行必须留存的代码
        val source = lines(
            "private val openish = \"$OPEN\"",
            "private val sentinel = \"SENTINEL_AFTER\"",
            "private val closeish = \"$CLOSE\""
        )
        val naive = NAIVE_BLOCK.replace(source, "")
        assertFalse("对照前提：旧口径会把 sentinel 一并吞掉（这正是假阴性的成因）",
            naive.contains("SENTINEL_AFTER"))
        assertTrue("新口径必须保留其后真实代码",
            stripCommentsOnly(source).contains("SENTINEL_AFTER"))
    }

    private fun lines(vararg items: String): String = items.joinToString("\n")

    private companion object {
        /** 开注释符（拼接而成：本文件不得出现该序列本身） */
        val OPEN = "/" + "*"

        /** 闭注释符（同上） */
        val CLOSE = "*" + "/"
        val BLOCK_COMMENT_MARK = OPEN
        val BLOCK_COMMENT_END = CLOSE
        val LINE_COMMENT_MARK = "/" + "/"
        val DOC_MARK = OPEN + "*"
        val TRIPLE = "\"\"\""

        /** 旧守卫口径（拼接而成：本文件仍不得出现该序列本身）——仅供上面的正向对照使用 */
        val NAIVE_BLOCK = Regex("/" + "\\*" + "[\\s\\S]*?" + "\\*" + "/")
    }
}
