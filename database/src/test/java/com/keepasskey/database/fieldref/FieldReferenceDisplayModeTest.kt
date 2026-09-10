package com.keepasskey.database.fieldref

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段引用引擎**展示模式**单测（ISSUE-P3-02 / TASK-49，承接 TASK-17 残余）。
 *
 * 覆盖：展示侧仅展开公开字段、受保护字段（取值面 / 检索面）掩码且任何递归深度都不物化明文、
 * 循环引用与超深引用链由深度上限兜底不崩溃、取值模式（[FieldReferenceEngine.resolve]）语义保持不变。
 */
class FieldReferenceDisplayModeTest {

    private fun entry(
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

    private fun rootWith(vararg entries: KdbxEntry): KdbxGroup =
        KdbxGroup(id = KdbxUuid.random(), name = "Root", entries = entries.toList())

    // ===== 公开字段照常展开 =====

    @Test
    fun `展示模式展开公开字段引用`() {
        val github = entry(title = "GitHub", username = "octocat", url = "https://github.com", notes = "备注")
        val root = rootWith(github)

        assertEquals("octocat", FieldReferenceEngine.resolveForDisplay("{REF:U@T:GitHub}", root))
        assertEquals("https://github.com", FieldReferenceEngine.resolveForDisplay("{REF:A@T:GitHub}", root))
        assertEquals("备注", FieldReferenceEngine.resolveForDisplay("{REF:N@T:GitHub}", root))
        assertEquals(github.id.toHexString(), FieldReferenceEngine.resolveForDisplay("{REF:I@T:GitHub}", root))
    }

    @Test
    fun `展示模式未命中的引用保持原文`() {
        val root = rootWith(entry(title = "GitHub", username = "octocat"))

        assertEquals("{REF:U@T:NotExists}", FieldReferenceEngine.resolveForDisplay("{REF:U@T:NotExists}", root))
    }

    // ===== 受保护字段：掩码，不物化 =====

    @Test
    fun `取值面为受保护字段的引用输出掩码占位`() {
        val github = entry(title = "GitHub", username = "octocat", password = "gh_secret")
        val root = rootWith(github)

        val resolved = FieldReferenceEngine.resolveForDisplay("密码：{REF:P@T:GitHub}", root)

        assertEquals("密码：${FieldReferenceEngine.PROTECTED_PLACEHOLDER}", resolved)
        assertFalse(resolved.contains("gh_secret"))
    }

    @Test
    fun `检索面为受保护字段的引用同样输出掩码占位`() {
        val github = entry(title = "GitHub", password = "gh_secret")
        val root = rootWith(github)

        val resolved = FieldReferenceEngine.resolveForDisplay("{REF:U@P:gh_secret}", root)

        assertEquals(FieldReferenceEngine.PROTECTED_PLACEHOLDER, resolved)
    }

    @Test
    fun `掩码占位可自定义`() {
        val github = entry(title = "GitHub", password = "gh_secret")
        val root = rootWith(github)

        val resolved = FieldReferenceEngine.resolveForDisplay("{REF:P@T:GitHub}", root, protectedPlaceholder = "***")

        assertEquals("***", resolved)
    }

    @Test
    fun `嵌套链末端为受保护字段时任何深度都不物化明文`() {
        val c = entry(title = "C", password = "deep_secret")
        val b = entry(title = "B", notes = "前缀 {REF:P@T:C} 后缀")
        val root = rootWith(b, c)

        val resolved = FieldReferenceEngine.resolveForDisplay("{REF:N@T:B}", root)

        assertEquals("前缀 ${FieldReferenceEngine.PROTECTED_PLACEHOLDER} 后缀", resolved)
        assertFalse(resolved.contains("deep_secret"))
    }

    @Test
    fun `循环引用在展示模式下同样掩码且不崩溃`() {
        val a = entry(title = "A", password = "loop_secret", notes = "A 备注 {REF:P@T:B}")
        val b = entry(title = "B", password = "loop_secret_2", notes = "B 备注 {REF:P@T:A}")
        val root = rootWith(a, b)

        val resolved = FieldReferenceEngine.resolveForDisplay(a.notes, root)

        assertFalse(resolved.contains("loop_secret"))
        assertFalse(resolved.contains("loop_secret_2"))
    }

    // ===== 循环引用与超深引用的深度兜底 =====

    @Test
    fun `公开字段循环引用在深度上限后停止且不崩溃`() {
        val a = entry(title = "A", username = "{REF:U@T:B}")
        val b = entry(title = "B", username = "{REF:U@T:A}")
        val root = rootWith(a, b)

        val resolved = FieldReferenceEngine.resolveForDisplay(a.userName, root)

        assertTrue(
            "循环链无终结值：深度耗尽后原样返回，不崩溃、不挂起",
            resolved.contains("{REF:", ignoreCase = true) || resolved.isEmpty()
        )
    }

    @Test
    fun `超深引用链受深度上限约束不栈溢出`() {
        assertTrue(FieldReferenceEngine.MAX_DEPTH > 0)
        val depth = FieldReferenceEngine.MAX_DEPTH * 3
        val entries = (1..depth).map { index ->
            entry(
                title = "e$index",
                username = if (index == depth) "final_value" else "{REF:U@T:e${index + 1}}"
            )
        }
        val root = rootWith(*entries.toTypedArray())

        val resolved = FieldReferenceEngine.resolveForDisplay("{REF:U@T:e1}", root)

        assertTrue(
            "超出深度上限的引用链原样保留，且不得栈溢出：$resolved",
            resolved == "final_value" || resolved.contains("{REF:", ignoreCase = true)
        )
    }

    @Test
    fun `超深公开引用链末端为受保护字段时不物化明文`() {
        val depth = FieldReferenceEngine.MAX_DEPTH
        // e1 → e2 → ... → e(depth) 的 username 链，末端一跳落在受保护字段引用上
        val entries = (1..depth).map { index ->
            if (index == depth) {
                entry(title = "e$index", username = "{REF:P@T:secret_holder}")
            } else {
                entry(title = "e$index", username = "{REF:U@T:e${index + 1}}")
            }
        }
        val secretHolder = entry(title = "secret_holder", password = "tail_secret")
        val root = rootWith(*(entries + secretHolder).toTypedArray())

        val resolved = FieldReferenceEngine.resolveForDisplay("{REF:U@T:e1}", root)

        assertFalse("深层展开不得在末端物化受保护值", resolved.contains("tail_secret"))
        assertTrue(
            "末端应为掩码占位或（超出深度上限时）原样保留的引用：$resolved",
            resolved.contains(FieldReferenceEngine.PROTECTED_PLACEHOLDER) ||
                resolved.contains("{REF:", ignoreCase = true)
        )
    }

    // ===== 取值模式语义保持不变（回归保护） =====

    @Test
    fun `取值模式仍按既有语义展开受保护字段`() {
        val github = entry(title = "GitHub", username = "octocat", password = "gh_secret")
        val root = rootWith(github)

        assertEquals("gh_secret", FieldReferenceEngine.resolve("{REF:P@T:GitHub}", root))
    }

    @Test
    fun `取值模式与展示模式对公开字段结果一致`() {
        val github = entry(title = "GitHub", username = "octocat")
        val root = rootWith(github)

        assertEquals(
            FieldReferenceEngine.resolve("{REF:U@T:GitHub}", root),
            FieldReferenceEngine.resolveForDisplay("{REF:U@T:GitHub}", root)
        )
    }

    @Test
    fun `无引用文本在展示模式下零开销直返`() {
        val plain = "https://example.com/login?user=abc"

        assertEquals(plain, FieldReferenceEngine.resolveForDisplay(plain, rootWith()))
        assertFalse(FieldReferenceEngine.containsReference(plain))
    }
}
