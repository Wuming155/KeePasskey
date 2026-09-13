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
        val resolved = FieldReferenceEngine.resolve(
            consumer.userName, rootWith(github, consumer), FieldReferenceEngine.RefField.USER_NAME
        )
        assertEquals("octocat", resolved)
    }

    @Test
    fun `密码引用在消费点展开为引用目标的密码明文`() {
        val github = entry(title = "GitHub", username = "octocat", password = "gh_secret")
        val consumer = entry(title = "消费条目", password = null)
        val resolved = FieldReferenceEngine.resolve(
            "{REF:P@T:GitHub}", rootWith(github, consumer), FieldReferenceEngine.RefField.PASSWORD
        )
        assertEquals("gh_secret", resolved)
    }

    @Test
    fun `UUID 检索与字段代码大小写不敏感`() {
        val github = entry(title = "GitHub", username = "octocat")
        val consumer = entry(
            title = "消费条目",
            username = "{ref:u@i:${github.id.toHexString()}}"
        )
        val resolved = FieldReferenceEngine.resolve(
            consumer.userName, rootWith(github, consumer), FieldReferenceEngine.RefField.USER_NAME
        )
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
        assertEquals(
            "https://bank.example.com",
            FieldReferenceEngine.resolve(consumer.userName, root, FieldReferenceEngine.RefField.USER_NAME)
        )
        assertEquals(
            "https://bank.example.com",
            FieldReferenceEngine.resolve(consumer.url, root, FieldReferenceEngine.RefField.URL)
        )
    }

    @Test
    fun `取值面字段为空时解析为空串`() {
        // 检索命中但目标 Notes 为空：按 KeePass 语义解析为空串
        val bank = entry(title = "Bank", username = "alice")
        val consumer = entry(title = "消费条目", url = "{REF:N@U:alice}")
        assertEquals(
            "", FieldReferenceEngine.resolve(consumer.url, rootWith(bank, consumer), FieldReferenceEngine.RefField.URL)
        )
    }

    @Test
    fun `引用链递归展开至终结值`() {
        val c = entry(title = "C", username = "final_value")
        val b = entry(title = "B", username = "{REF:U@T:C}")
        val a = entry(title = "A", username = "{REF:U@T:B}")
        val resolved = FieldReferenceEngine.resolve(
            a.userName, rootWith(a, b, c), FieldReferenceEngine.RefField.USER_NAME
        )
        assertEquals("final_value", resolved)
    }

    @Test
    fun `循环引用在深度上限后停止不无限递归`() {
        val a = entry(title = "A", username = "{REF:U@T:B}")
        val b = entry(title = "B", username = "{REF:U@T:A}")
        val resolved = FieldReferenceEngine.resolve(
            a.userName, rootWith(a, b), FieldReferenceEngine.RefField.USER_NAME
        )
        // 循环链无终结值：深度耗尽后原样返回，不崩溃、不挂起
        assertTrue(resolved.contains("{REF:", ignoreCase = true) || resolved == "final" || resolved.isEmpty())
    }

    @Test
    fun `未命中的引用保持原文不静默吞掉`() {
        val consumer = entry(title = "消费条目", username = "{REF:U@T:NotExists}")
        val resolved = FieldReferenceEngine.resolve(
            consumer.userName, rootWith(consumer), FieldReferenceEngine.RefField.USER_NAME
        )
        assertEquals("{REF:U@T:NotExists}", resolved)
    }

    @Test
    fun `普通文本零开销直返且短路判断正确`() {
        val plain = "https://example.com/login?user=abc"
        assertFalse(FieldReferenceEngine.containsReference(plain))
        assertTrue(FieldReferenceEngine.containsReference("前缀 {REF:U@T:X} 后缀"))
        assertEquals(plain, FieldReferenceEngine.resolve(plain, rootWith(), FieldReferenceEngine.RefField.USER_NAME))
    }

    // ===== 消费点面白名单（ISSUE-P0-08） =====

    @Test
    fun `非口令消费点遇密码取值面输出掩码不外泄明文`() {
        // AC③ 正例构造：UserName = {REF:P@A:target} → 用户名通道不得物化被引用口令
        val github = entry(
            title = "GitHub",
            password = "gh_secret",
            url = "https://github.com"
        )
        val consumer = entry(title = "消费条目", username = "{REF:P@A:https://github.com}")
        val resolved = FieldReferenceEngine.resolve(
            consumer.userName, rootWith(github, consumer), FieldReferenceEngine.RefField.USER_NAME
        )
        assertEquals(FieldReferenceEngine.PROTECTED_PLACEHOLDER, resolved)
        assertFalse("用户名通道不得包含被引用口令明文", resolved.contains("gh_secret"))
    }

    @Test
    fun `非口令消费点遇密码检索面同样输出掩码`() {
        val github = entry(title = "GitHub", username = "octocat", password = "gh_secret")
        val consumer = entry(title = "消费条目", username = "{REF:U@P:gh_secret}")
        val resolved = FieldReferenceEngine.resolve(
            consumer.userName, rootWith(github, consumer), FieldReferenceEngine.RefField.USER_NAME
        )
        assertEquals(FieldReferenceEngine.PROTECTED_PLACEHOLDER, resolved)
    }

    @Test
    fun `非口令消费点的公开字段引用行为不变`() {
        // AC③ 负例：{REF:U@…} / {REF:T@…} 在用户名通道照常解析
        val github = entry(title = "GitHub", username = "octocat")
        val consumer = entry(
            title = "消费条目",
            username = "{REF:U@T:GitHub} / {REF:T@U:octocat}"
        )
        val resolved = FieldReferenceEngine.resolve(
            consumer.userName, rootWith(github, consumer), FieldReferenceEngine.RefField.USER_NAME
        )
        assertEquals("octocat / GitHub", resolved)
    }

    @Test
    fun `非口令消费点掩码随递归链全程约束`() {
        // 引用目标的 UserName 自身含 {REF:P@…}：用户名通道在递归深度亦不得物化口令
        val secret = entry(title = "Secret", password = "deep_secret")
        val relay = entry(title = "Relay", username = "{REF:P@T:Secret}")
        val consumer = entry(title = "消费条目", username = "{REF:U@T:Relay}")
        val resolved = FieldReferenceEngine.resolve(
            consumer.userName, rootWith(secret, relay, consumer), FieldReferenceEngine.RefField.USER_NAME
        )
        assertEquals(FieldReferenceEngine.PROTECTED_PLACEHOLDER, resolved)
        assertFalse(resolved.contains("deep_secret"))
    }
}
