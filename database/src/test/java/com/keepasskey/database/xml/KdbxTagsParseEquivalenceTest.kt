package com.keepasskey.database.xml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ISSUE-P3-181` ③：`<Tags>` 单趟解析与旧表达式的**逐项等价**回归。
 *
 * 旧写法 `raw?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()`
 * 每个 Group / Entry 各产生 3 个中间集合；新实现按 `;` 单趟扫描直接累积最终列表。
 * 唯一可能产生分歧的位置是**空段**（相邻分号、尾随分号、纯空白段），故本类把旧表达式
 * **原样抄录**在测试内作为参照，对含空段的各种畸形输入逐项对拍。
 *
 * 注：`split(";")` 是字面量分隔符、不限段数；`trim()` 不传谓词时即 Kotlin 默认的
 * `Char.isWhitespace()`，与手写实现的 `trim()` 为同一实现——本类只断言**结果**相等，
 * 不依赖对上述两条的推理。
 */
class KdbxTagsParseEquivalenceTest {

    @Test
    fun `畸形分号与空白输入与旧表达式逐项等价`() {
        inputs().forEach { input ->
            assertEquals(
                "输入 ${input?.let { "「$it」" } ?: "null"} 的解析结果必须与旧表达式一致",
                legacyParse(input),
                parseTagsText(input)
            )
        }
    }

    @Test
    fun `空段一律不产出标签且顺序保持`() {
        assertEquals(listOf("a", "b"), parseTagsText("a;;b;"))
        assertEquals(listOf("工作", "重要", "同步"), parseTagsText("工作;重要;同步"))
        assertEquals(listOf("a"), parseTagsText("  a  "))
    }

    @Test
    fun `null 与空白输入返回空列表`() {
        assertEquals(emptyList<String>(), parseTagsText(null))
        assertEquals(emptyList<String>(), parseTagsText(""))
        assertEquals(emptyList<String>(), parseTagsText("   "))
        assertEquals(emptyList<String>(), parseTagsText(";"))
        assertEquals(emptyList<String>(), parseTagsText(";;  ;;\t\n"))
    }

    private fun inputs(): List<String?> = listOf(
        null, "", " ", "  \t\n ", ";", ";;", ";;;", "a", ";a", "a;", ";a;",
        "a;;b", "a; ;b", "a; ;b;", " a ; b ", "工作;重要", "工作;;重要;",
        "a;b;c;d;e;", " ; ; ", "\u00A0a\u3000", "a\u000Bb", "a\u001Cb"
    )

    /** 旧表达式原样抄录（仅作参照，不参与生产路径） */
    private fun legacyParse(raw: String?): List<String> =
        raw?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
