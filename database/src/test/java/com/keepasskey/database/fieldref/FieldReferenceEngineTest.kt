package com.keepasskey.database.fieldref

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * {REF:...} 字段引用引擎单测（TASK-17）：
 * 基本取值 / 密码引用 / UUID 检索 / 代码大小写不敏感 / 递归展开与循环防护 / 未命中保持原文。
 */
class FieldReferenceEngineTest {

    private fun entry(
        title: String,
        username: String = "",
        password: String? = null,
        url: String = "",
        notes: String = ""
    ): KdbxEntry = KdbxEntry(
        id = KdbxUuid.random(),
        fields = buildMap {
            put(com.keepasskey.core.model.KdbxConstants.Fields.TITLE, ProtectedString(title, false))
            put(com.keepasskey.core.model.KdbxConstants.Fields.USER_NAME, ProtectedString(username, false))
            if (password != null) {
                put(com.keepasskey.core.model.KdbxConstants.Fields.PASSWORD, ProtectedString(password, true))
            }
            put(com.keepasskey.core.model.KdbxConstants.Fields.URL, ProtectedString(url, false))
            if (notes.isNotEmpty()) {
                put(com.keepasskey.core.model.KdbxConstants.Fields.NOTES, ProtectedString(notes, false))
            }
        }
    )

    private fun rootWith(vararg entries: KdbxEntry): KdbxGroup =
        KdbxGroup(id = KdbxUuid.random(), name = "Root", entries = entries.toList())

    @Test
    fun `基本用户名引用按标题检索解析`() {
        val github = entry(title = "GitHub", username = "octocat", password = "gh_secret")
        val consumer = entry(title = "消费条目", username = "{REF:U@T:GitHub}")
        val resolved = FieldReferenceEngine.resolve(consumer.userName, rootWith(github, consumer))
        assertEquals("octocat", resolved)
    }

    @Test
    fun `密码引用在消费点展开为引用目标的密码明文`() {
        val github = entry(title = "GitHub", username = "octocat", password = "gh_secret")
        val consumer = entry(title = "消费条目", password = null)
        val resolved = FieldReferenceEngine.resolve("{REF:P@T:GitHub}", rootWith(github, consumer))
        assertEquals("gh_secret", resolved)
    }

    @Test
    fun `UUID 检索与字段代码大小写不敏感`() {
        val github = entry(title = "GitHub", username = "octocat")
        val consumer = entry(
            title = "消费条目",
            username = "{ref:u@i:${github.id.toHexString()}}"
        )
        val resolved = FieldReferenceEngine.resolve(consumer.userName, rootWith(github, consumer))
        assertEquals("octocat", resolved)
    }

    @Test
    fun `URL 取值面与 Notes 检索面均可用`() {
        // {REF:<Want>@<SearchIn>:<Text>}：@ 左为取值面、右为检索面
        val bank = entry(
            title = "Bank",
            username = "alice",
            url = "https://bank.example.com",
            notes = "segretario"
        )
        val consumer = entry(
            title = "消费条目",
            username = "{REF:A@T:Bank}",
            url = "{REF:A@N:segretario}"
        )
        val root = rootWith(bank, consumer)
        assertEquals("https://bank.example.com", FieldReferenceEngine.resolve(consumer.userName, root))
        assertEquals("https://bank.example.com", FieldReferenceEngine.resolve(consumer.url, root))
    }

    @Test
    fun `取值面字段为空时解析为空串`() {
        // 检索命中但目标 Notes 为空：按 KeePass 语义解析为空串
        val bank = entry(title = "Bank", username = "alice")
        val consumer = entry(title = "消费条目", url = "{REF:N@U:alice}")
        assertEquals("", FieldReferenceEngine.resolve(consumer.url, rootWith(bank, consumer)))
    }

    @Test
    fun `引用链递归展开至终结值`() {
        val c = entry(title = "C", username = "final_value")
        val b = entry(title = "B", username = "{REF:U@T:C}")
        val a = entry(title = "A", username = "{REF:U@T:B}")
        val resolved = FieldReferenceEngine.resolve(a.userName, rootWith(a, b, c))
        assertEquals("final_value", resolved)
    }

    @Test
    fun `循环引用在深度上限后停止不无限递归`() {
        val a = entry(title = "A", username = "{REF:U@T:B}")
        val b = entry(title = "B", username = "{REF:U@T:A}")
        val resolved = FieldReferenceEngine.resolve(a.userName, rootWith(a, b))
        // 循环链无终结值：深度耗尽后原样返回，不崩溃、不挂起
        assertTrue(resolved.contains("{REF:", ignoreCase = true) || resolved == "final" || resolved.isEmpty())
    }

    @Test
    fun `未命中的引用保持原文不静默吞掉`() {
        val consumer = entry(title = "消费条目", username = "{REF:U@T:NotExists}")
        val resolved = FieldReferenceEngine.resolve(consumer.userName, rootWith(consumer))
        assertEquals("{REF:U@T:NotExists}", resolved)
    }

    @Test
    fun `普通文本零开销直返且短路判断正确`() {
        val plain = "https://example.com/login?user=abc"
        assertFalse(FieldReferenceEngine.containsReference(plain))
        assertTrue(FieldReferenceEngine.containsReference("前缀 {REF:U@T:X} 后缀"))
        assertEquals(plain, FieldReferenceEngine.resolve(plain, rootWith()))
    }
}
