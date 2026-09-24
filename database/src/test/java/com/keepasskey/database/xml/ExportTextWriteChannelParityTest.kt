package com.keepasskey.database.xml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 导出/序列化文本写出的**双通道等价**守卫（**ISSUE-P3-303** AC①）。
 *
 * 背景：为让受保护字段（条目口令、自定义字段值）不再经 `ProtectedString.readString()`
 * 物化**不可擦** `String`，新增了 `CharArray` 写出通道（`KdbxXmlStreamWriter.text(CharArray)` /
 * `KdbxXmlWriteUtil.textElement(…, CharArray)`）。AC① 要求产物**逐字节等价** ——
 * 本用例即该要求的机检出口：对同一组字符内容，String 通道与 CharArray 通道必须写出完全相同的字节。
 *
 * 样本覆盖转义表的每一类分支与「非法码点剔除」：
 * `&` `<` `>` `"`、CR（转义为 `&#xD;`）、LF / TAB（文本路径不转义）、
 * 非 BMP（emoji，代理对完整）、以及 XML 1.0 非法控制字符（U+0001，应被剔除）。
 */
class ExportTextWriteChannelParityTest {

    private fun writeViaString(value: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        val writer = KdbxXmlStreamWriter(buffer)
        KdbxXmlWriteUtil.textElement(writer, "Value", value)
        writer.close()
        return buffer.toByteArray()
    }

    private fun writeViaChars(value: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        val writer = KdbxXmlStreamWriter(buffer)
        KdbxXmlWriteUtil.textElement(writer, "Value", value.toCharArray())
        writer.close()
        return buffer.toByteArray()
    }

    @Test
    fun `字符串与字符数组两条写出通道逐字节等价`() {
        val samples = listOf(
            "plain",
            "a&b<c>d\"e",
            "line1\r\nline2",
            "tab\tinside",
            "中文与 emoji \uD83D\uDE00 混排",
            "控制字符\u0001被剔除",
            "",
            "含引号\"与单引号'"
        )

        samples.forEach { sample ->
            val viaString = writeViaString(sample)
            val viaChars = writeViaChars(sample)
            assertArrayEquals(
                "样本 ${sample.map { it.code }} 的两条通道产物不一致（ISSUE-P3-303 逐字节等价要求）",
                viaString,
                viaChars
            )
        }
    }

    @Test
    fun `转义与非法码点剔除在字符数组通道同样生效`() {
        // 正控制：证明上面那组「等价」不是「两条都没转义」造成的假等价
        val escaped = writeViaChars("a&b<c>d").toString(Charsets.UTF_8)
        assertTrue("字符数组通道未转义 &", escaped.contains("&amp;"))
        assertTrue("字符数组通道未转义 <", escaped.contains("&lt;"))
        assertTrue("字符数组通道未转义 >", escaped.contains("&gt;"))

        val carriageReturn = writeViaChars("x\ry").toString(Charsets.UTF_8)
        assertTrue("字符数组通道未把 CR 转义为字符引用（P3-3 往返要求）", carriageReturn.contains("&#xD;"))

        val illegal = writeViaChars("x\u0001y").toString(Charsets.UTF_8)
        assertTrue("字符数组通道未剔除 XML 1.0 非法控制字符", !illegal.contains('\u0001'))

        // 非 BMP（代理对）必须完整保留，不得被截断为低位
        val emoji = writeViaChars("\uD83D\uDE00").toString(Charsets.UTF_8)
        assertTrue("字符数组通道丢失非 BMP 字符", emoji.contains("\uD83D\uDE00"))
    }
}
