package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从模板新建的预填投影单元测试（ISSUE-P3-51）。
 *
 * 重点验证：**保持 entryId 为空**（保存即新建而非覆盖模板）、复制结构性字段、
 * 不复制机密/大对象（密码 / TOTP / 附件），以及落点分组优先级。
 */
class EntryEditTemplateProjectionTest {

    private val template = UiVaultEntry(
        id = "template-1",
        title = "信用卡",
        username = "holder",
        url = "https://bank.example",
        notes = "模板备注",
        groupId = "template-group",
        iconName = "credit_card",
        customIconId = "icon-hex",
        tags = listOf("财务", "重要"),
        autoTypeSequence = "{USERNAME}{TAB}{PASSWORD}",
        overrideUrl = "cmd://open",
        customFields = listOf(
            UiCustomField(id = "f1", key = "卡号", value = "", isProtected = true)
        ),
        attachments = listOf(
            UiAttachment(id = "a1", fileName = "x.bin", fileSizeFormatted = "1 KB")
        )
    )

    @Test
    fun `保持 entryId 为空以新建条目而非覆盖模板`() {
        val result = applyTemplateEntry(EntryEditUiState(), template, targetGroupId = null)
        assertNull(result.entryId)
    }

    @Test
    fun `复制结构性字段`() {
        val result = applyTemplateEntry(EntryEditUiState(), template, targetGroupId = null)
        assertEquals("信用卡", result.title)
        assertEquals("holder", result.username)
        assertEquals("https://bank.example", result.url)
        assertEquals("模板备注", result.notes)
        assertEquals("credit_card", result.iconName)
        assertEquals("icon-hex", result.customIconId)
        assertEquals("财务, 重要", result.tagsInput)
        assertEquals("{USERNAME}{TAB}{PASSWORD}", result.autoTypeSequence)
        assertEquals("cmd://open", result.overrideUrl)
        assertEquals(template.customFields, result.customFields)
    }

    @Test
    fun `不复制附件与密码长度`() {
        val result = applyTemplateEntry(EntryEditUiState(), template, targetGroupId = null)
        assertTrue(result.attachments.isEmpty())
        assertEquals(0, result.passwordLength)
    }

    @Test
    fun `落点分组优先取路由入参缺省回退模板所属分组`() {
        val explicit = applyTemplateEntry(EntryEditUiState(), template, targetGroupId = "target-group")
        assertEquals("target-group", explicit.groupId)

        val fallback = applyTemplateEntry(EntryEditUiState(), template, targetGroupId = null)
        assertEquals("template-group", fallback.groupId)
    }
}
