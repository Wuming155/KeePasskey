package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.settings.SearchMatchMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全文搜索匹配单元测试（README「全文搜索」契约）。
 *
 * 覆盖范围：标题 / 用户名 / URL / 备注 / 标签 / 自定义字段键值与受保护值排除，
 * 以及空查询与大小写不敏感语义。
 */
class VaultListProjectionSearchTest {

    private fun entry(
        title: String = "站点",
        username: String = "user",
        url: String = "https://example.com",
        notes: String = "",
        tags: List<String> = emptyList(),
        customFields: List<UiCustomField> = emptyList()
    ) = UiVaultEntry(
        id = "e1",
        title = title,
        username = username,
        url = url,
        notes = notes,
        tags = tags,
        customFields = customFields
    )

    @Test
    fun `空查询不过滤任何条目`() {
        assertTrue(matchesSearchQuery(entry(), ""))
        assertTrue(matchesSearchQuery(entry(), "   "))
    }

    @Test
    fun `命中标题用户名与URL且大小写不敏感`() {
        assertTrue(matchesSearchQuery(entry(title = "GitHub"), "github"))
        assertTrue(matchesSearchQuery(entry(username = "Alice@Example.com"), "alice"))
        assertTrue(matchesSearchQuery(entry(url = "https://example.com"), "EXAMPLE"))
    }

    @Test
    fun `命中备注`() {
        assertTrue(matchesSearchQuery(entry(notes = "备用恢复码存放于此"), "恢复码"))
    }

    @Test
    fun `命中标签`() {
        assertTrue(matchesSearchQuery(entry(tags = listOf("工作", "财务")), "财务"))
    }

    @Test
    fun `命中自定义字段键与非受保护值`() {
        val fields = listOf(UiCustomField(id = "f1", key = "手机号", value = "13800000000"))
        assertTrue(matchesSearchQuery(entry(customFields = fields), "手机号"))
        assertTrue(matchesSearchQuery(entry(customFields = fields), "1380"))
    }

    @Test
    fun `受保护字段值不参与命中但键可命中`() {
        val fields = listOf(
            UiCustomField(id = "f1", key = "TOTP Seed", value = "", isProtected = true)
        )
        // 键属元数据，可命中
        assertTrue(matchesSearchQuery(entry(customFields = fields), "totp"))
        // 受保护值不物化，任何针对其明文的查询都不应命中
        assertFalse(matchesSearchQuery(entry(customFields = fields), "JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun `无命中返回假`() {
        assertFalse(matchesSearchQuery(entry(), "不存在的关键字"))
    }

    // ===== ISSUE-P3-309：分词档（ALL_TERMS） =====

    @Test
    fun `分词档要求每个空白分隔词都命中`() {
        val target = entry(title = "GitHub 工作账号", username = "dev@corp.com")
        // 两词都命中 → 匹配
        assertTrue(matchesSearchQuery(target, "github 工作", SearchMatchMode.ALL_TERMS))
        // 含未命中的词 → 不匹配（子串档会因 "github" 命中而误报）
        assertFalse(matchesSearchQuery(target, "github 不存在", SearchMatchMode.ALL_TERMS))
    }

    @Test
    fun `分词档各词仍为大小写不敏感子串口径`() {
        val target = entry(title = "GitHub", notes = "work account")
        assertTrue(matchesSearchQuery(target, "GITHUB Work", SearchMatchMode.ALL_TERMS))
        // 词内子串即可（非整词边界）
        assertTrue(matchesSearchQuery(target, "hub cco", SearchMatchMode.ALL_TERMS))
    }

    @Test
    fun `分词档多空白分隔按空词忽略`() {
        val target = entry(title = "GitHub")
        assertTrue(matchesSearchQuery(target, "  github   ", SearchMatchMode.ALL_TERMS))
    }

    @Test
    fun `分词档受保护字段值不参与命中`() {
        val fields = listOf(
            UiCustomField(id = "f1", key = "TOTP Seed", value = "", isProtected = true)
        )
        assertTrue(matchesSearchQuery(entry(customFields = fields), "totp", SearchMatchMode.ALL_TERMS))
        assertFalse(
            matchesSearchQuery(entry(customFields = fields), "seed JBSWY3DPEHPK3PXP", SearchMatchMode.ALL_TERMS)
        )
    }

    @Test
    fun `分词档空查询不过滤`() {
        assertTrue(matchesSearchQuery(entry(), "", SearchMatchMode.ALL_TERMS))
        assertTrue(matchesSearchQuery(entry(), "   ", SearchMatchMode.ALL_TERMS))
    }
}
