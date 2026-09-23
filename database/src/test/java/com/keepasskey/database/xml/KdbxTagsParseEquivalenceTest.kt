package com.keepasskey.database.xml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `<Tags>` 读侧解析口径回归（原 `ISSUE-P3-181` ③ 的「与旧表达式等价」用例，
 * `ISSUE-P2-282` 起**改锁新口径**：旧表达式只按 `;` 切分、无归一化，已被证为
 * 跨实现漂移源——逗号库（KeePassXC / pykeepass）读成单个标签）。
 *
 * 新口径＝官方 `g_vTagSep { ',', ';' }` 切分 + `NormalizeTags` 同义归一化
 * （trim + 去空 + 去重 + 自然排序），单一实现 `KdbxTags.parse`（本类经 `parseTagsText`
 * 代理验证读侧接线；词汇本身的面由 `KdbxTagsTest` 锁定）。
 *
 * 测试资产纪律①：本类为**修改期望**（原等价参照已随生产口径退役），未删除用例。
 */
class KdbxTagsParseEquivalenceTest {

    @Test
    fun `分号与逗号同为分隔符且空段一律不产出标签`() {
        assertEquals(listOf("a", "b"), parseTagsText("a;;b;"))
        assertEquals(listOf("a", "b"), parseTagsText("a,,b,"))
        assertEquals(listOf("a", "b", "c"), parseTagsText("a;b,c"))
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

    @Test
    fun `归一化：去重与自然排序（中文按码位序）`() {
        assertEquals(listOf("a"), parseTagsText("a;a"))
        assertEquals(listOf("a2", "a10"), parseTagsText("a10;a2"))
        assertEquals(listOf("同步", "工作", "重要"), parseTagsText("工作;重要;同步"))
    }
}
