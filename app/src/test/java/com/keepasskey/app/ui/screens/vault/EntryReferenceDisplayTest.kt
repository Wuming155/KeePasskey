package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.ui.model.EntryReferenceDisplayResolver
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.fieldref.FieldReferenceEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * ISSUE-P3-02（TASK-49）Notes / URL 展示侧字段引用解析单测。
 *
 * 覆盖：公开字段（T/U/A/N/I）正常展开、受保护字段（P，取值面或检索面）掩码占位且
 * 任何递归深度都不物化明文、循环引用不崩溃、无引用零开销直返、批量解析只含引用条目。
 */
class EntryReferenceDisplayTest {

    private fun kdbxEntry(
        title: String,
        username: String = "",
        password: String? = null,
        url: String = "",
        notes: String = ""
    ): KdbxEntry = KdbxEntry(
        id = KdbxUuid.random(),
        fields = buildMap {
            put(KdbxConstants.Fields.TITLE, ProtectedString(title, false))
            put(KdbxConstants.Fields.USER_NAME, ProtectedString(username, false))
            if (password != null) {
                put(KdbxConstants.Fields.PASSWORD, ProtectedString(password, true))
            }
            put(KdbxConstants.Fields.URL, ProtectedString(url, false))
            if (notes.isNotEmpty()) {
                put(KdbxConstants.Fields.NOTES, ProtectedString(notes, false))
            }
        }
    )

    private fun resolver(vararg entries: KdbxEntry) =
        EntryReferenceDisplayResolver(loadEntries = { entries.toList() })

    @Test
    fun `Notes 中的公开字段引用在展示侧展开`() = runTest {
        val github = kdbxEntry(title = "GitHub", username = "octocat")

        val display = resolver(github).display(notes = "账号：{REF:U@T:GitHub}", url = "")

        assertEquals("账号：octocat", display.notes)
    }

    @Test
    fun `URL 中的公开字段引用在展示侧展开`() = runTest {
        val bank = kdbxEntry(title = "Bank", url = "https://bank.example.com")

        val display = resolver(bank).display(notes = "", url = "{REF:A@T:Bank}/login")

        assertEquals("https://bank.example.com/login", display.url)
    }

    @Test
    fun `受保护字段作为取值面的引用保持掩码不物化明文`() = runTest {
        val github = kdbxEntry(title = "GitHub", password = "gh_secret")

        val display = resolver(github).display(notes = "密码：{REF:P@T:GitHub}", url = "")

        assertEquals("密码：${FieldReferenceEngine.PROTECTED_PLACEHOLDER}", display.notes)
        assertFalse("展示文案不得出现受保护明文", display.notes.contains("gh_secret"))
    }

    @Test
    fun `受保护字段作为检索面的引用同样掩码`() = runTest {
        val github = kdbxEntry(title = "GitHub", username = "octocat", password = "gh_secret")

        val display = resolver(github).display(notes = "{REF:U@P:gh_secret}", url = "")

        assertEquals(FieldReferenceEngine.PROTECTED_PLACEHOLDER, display.notes)
    }

    @Test
    fun `嵌套引用链末端为受保护字段时仍掩码`() = runTest {
        // B.Notes 引用 C 的密码：公开引用展开后不得把 P 引用带成明文
        val c = kdbxEntry(title = "C", password = "nested_secret")
        val b = kdbxEntry(title = "B", notes = "{REF:P@T:C}")

        val display = resolver(b, c).display(notes = "{REF:N@T:B}", url = "")

        assertEquals(FieldReferenceEngine.PROTECTED_PLACEHOLDER, display.notes)
        assertFalse(display.notes.contains("nested_secret"))
    }

    @Test
    fun `循环引用不崩溃且不物化明文`() = runTest {
        val a = kdbxEntry(title = "A", password = "loop_secret", notes = "{REF:N@T:A}")

        val display = resolver(a).display(notes = "{REF:N@T:A}", url = "")

        assertFalse(display.notes.contains("loop_secret"))
    }

    @Test
    fun `无引用文本零开销直返且不读取条目快照`() = runTest {
        var snapshotLoads = 0
        val resolver = EntryReferenceDisplayResolver(loadEntries = { snapshotLoads++; emptyList() })

        val display = resolver.display(notes = "普通备注", url = "https://example.com")

        assertEquals("普通备注", display.notes)
        assertEquals("https://example.com", display.url)
        assertEquals(0, snapshotLoads)
    }

    @Test
    fun `条目快照为空时保持原文`() = runTest {
        val resolver = EntryReferenceDisplayResolver(loadEntries = { emptyList() })

        val display = resolver.display(notes = "{REF:U@T:GitHub}", url = "")

        assertEquals("{REF:U@T:GitHub}", display.notes)
    }

    @Test
    fun `未命中的引用保持原文不静默吞掉`() = runTest {
        val github = kdbxEntry(title = "GitHub", username = "octocat")

        val display = resolver(github).display(notes = "{REF:U@T:NotExists}", url = "")

        assertEquals("{REF:U@T:NotExists}", display.notes)
    }

    @Test
    fun `批量解析只包含含引用的条目且整批共用一次快照`() = runTest {
        var snapshotLoads = 0
        val github = kdbxEntry(title = "GitHub", username = "octocat")
        val resolver = EntryReferenceDisplayResolver(loadEntries = { snapshotLoads++; listOf(github) })
        val entries = listOf(
            UiVaultEntry(id = "e1", title = "a", username = "", url = "", notes = "账号 {REF:U@T:GitHub}"),
            UiVaultEntry(id = "e2", title = "b", username = "", url = "https://example.com"),
            UiVaultEntry(id = "e3", title = "c", username = "", url = "{REF:A@T:GitHub}")
        )

        val displays = resolver.present(entries)

        assertEquals(setOf("e1", "e3"), displays.keys)
        assertEquals("账号 octocat", displays.getValue("e1").notes)
        assertEquals(1, snapshotLoads)
    }

    @Test
    fun `批量解析无引用条目时零开销直返`() = runTest {
        var snapshotLoads = 0
        val resolver = EntryReferenceDisplayResolver(loadEntries = { snapshotLoads++; emptyList() })

        val displays = resolver.present(
            listOf(UiVaultEntry(id = "e1", title = "a", username = "", url = "https://example.com"))
        )

        assertEquals(emptyMap<String, Any>(), displays)
        assertEquals(0, snapshotLoads)
    }
}
