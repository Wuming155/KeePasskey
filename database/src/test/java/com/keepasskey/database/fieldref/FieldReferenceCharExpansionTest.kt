package com.keepasskey.database.fieldref

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

/**
 * {REF} 字段引用展开 **Char 通道** 单测（ISSUE-P3-394）：
 * 单级 / 多级 / 环引用（终止不悬挂）、混合文本、未命中与伪引用回退、口令检索面、
 * 大小写不敏感——每个可解析场景都与 String 引擎（[FieldReferenceEngine.resolve] 的
 * `PASSWORD` 消费点）**同答对照**，锁定两条通道不漂移。
 * 测试数据全部为虚构假凭据；断言中物化 String 仅用于比对，不涉及真实口令。
 */
internal class FieldReferenceCharExpansionTest {

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

    /** Char 通道展开（虚构测试数据） */
    private fun expand(raw: String, root: KdbxGroup): CharArray? =
        FieldReferenceCharExpansion.resolvePasswordFace(raw.toCharArray(), root)

    /** 断言：Char 通道结果 == 期望值，且与 String 引擎同答（两通道对照锁） */
    private fun assertExpandsTo(raw: String, root: KdbxGroup, expected: String) {
        val out = expand(raw, root)!!
        val actual = String(out)
        Arrays.fill(out, '0')
        assertEquals("Char 通道展开结果", expected, actual)
        assertEquals(
            "Char 通道必须与 String 引擎（PASSWORD 消费点）同答",
            FieldReferenceEngine.resolve(raw, root, FieldReferenceEngine.RefField.PASSWORD),
            actual
        )
    }

    @Test
    fun `无引用返回 null 调用方零成本直用原文`() {
        val root = rootWith(entry(title = "GitHub", password = "gh_secret"))
        assertNull(expand("plain_password", root))
        assertNull(expand("", root))
        // 引用语法之外的普通花括号文本不算引用
        assertNull(expand("{not_a_ref}", root))
    }

    @Test
    fun `单级口令引用展开为被引用条目的真实口令`() {
        val github = entry(title = "GitHub", username = "octocat", password = "gh_secret")
        assertExpandsTo("{REF:P@T:GitHub}", rootWith(github), "gh_secret")
    }

    @Test
    fun `多级引用链展开至链终端值`() {
        val c = entry(title = "C", password = "final_secret")
        val b = entry(title = "B", password = "{REF:P@T:C}")
        val a = entry(title = "A", password = "{REF:P@T:B}")
        assertExpandsTo(a.password!!.readString(), rootWith(a, b, c), "final_secret")
    }

    @Test(timeout = 10_000)
    fun `环引用在深度上限后原样终止不悬挂`() {
        val a = entry(title = "A", password = "{REF:P@T:B}")
        val b = entry(title = "B", password = "{REF:P@T:A}")
        val root = rootWith(a, b)
        val raw = a.password!!.readString()
        // 能走到断言即「终止不悬挂」（timeout 兜底）；深度耗尽后按保守语义保留引用原文
        val out = expand(raw, root)!!
        val actual = String(out)
        Arrays.fill(out, '0')
        assertTrue(
            "环引用链无终结值，产物应保留引用原文形态（实为 $actual）",
            actual.startsWith("{REF:")
        )
        assertEquals(
            "环引用回退形态必须与 String 引擎同答",
            FieldReferenceEngine.resolve(raw, root, FieldReferenceEngine.RefField.PASSWORD),
            actual
        )
    }

    @Test
    fun `混合文本按字面段与展开段拼接`() {
        val github = entry(title = "GitHub", password = "gh_secret")
        assertExpandsTo("pre-{REF:P@T:GitHub}-post", rootWith(github), "pre-gh_secret-post")
    }

    @Test
    fun `同一文本中的多个引用全部展开`() {
        val github = entry(title = "GitHub", username = "octocat", password = "gh_secret")
        assertExpandsTo(
            "{REF:U@T:GitHub}|{REF:P@T:GitHub}",
            rootWith(github),
            "octocat|gh_secret"
        )
    }

    @Test
    fun `未命中的引用保守回退原文不吞数据`() {
        val consumer = entry(title = "消费条目", password = "{REF:P@T:NotExists}")
        assertExpandsTo(
            consumer.password!!.readString(),
            rootWith(consumer),
            "{REF:P@T:NotExists}"
        )
    }

    @Test
    fun `伪引用保留原文且不影响后续真引用展开`() {
        val github = entry(title = "GitHub", password = "gh_secret")
        // 首个 {REF: 缺字段代码（伪引用）：该位置只消费一个 '{'，字面段照录后，
        // 随后的真引用照常展开——对齐正则「匹配失败右移一格」的语义，两侧均得 {REF:gh_secret
        assertExpandsTo("{REF:{REF:P@T:GitHub}", rootWith(github), "{REF:gh_secret")
        // 无闭合花括号 / 字段代码不合法：均按原文保留
        assertExpandsTo("{REF:P@T:GitHub", rootWith(github), "{REF:P@T:GitHub")
        assertExpandsTo("{REF:X@T:GitHub}", rootWith(github), "{REF:X@T:GitHub}")
        assertExpandsTo("{REF:PXT:GitHub}", rootWith(github), "{REF:PXT:GitHub}")
    }

    @Test(timeout = 10_000)
    fun `口令检索面按 Char 区域比较定位目标不物化 String 索引`() {
        val github = entry(title = "GitHub", username = "octocat", password = "gh_secret")
        val consumer = entry(title = "消费条目", password = "{REF:U@P:gh_secret}")
        assertExpandsTo(consumer.password!!.readString(), rootWith(github, consumer), "octocat")
        // 检索文本大小写不敏感（与 String 索引的 CASE_INSENSITIVE_ORDER 同口径）
        val consumerUpper = entry(title = "消费条目2", password = "{REF:U@P:GH_SECRET}")
        assertExpandsTo(
            consumerUpper.password!!.readString(),
            rootWith(github, consumerUpper),
            "octocat"
        )
    }

    @Test
    fun `字段代码与前缀大小写不敏感`() {
        val github = entry(title = "GitHub", password = "gh_secret")
        assertExpandsTo("{ref:p@t:GitHub}", rootWith(github), "gh_secret")
        assertExpandsTo("{Ref:P@t:GitHub}", rootWith(github), "gh_secret")
    }

    @Test
    fun `口令消费点的公开字段引用不做掩码照常展开`() {
        // ISSUE-P0-08 白名单的 P 面：Want=U 在口令消费点允许展开（掩码只属非口令消费点）
        val github = entry(title = "GitHub", username = "octocat", password = "gh_secret")
        assertExpandsTo("{REF:U@T:GitHub}", rootWith(github), "octocat")
    }

    @Test
    fun `引用目标口令内嵌引用时随链展开`() {
        // 目标口令本身是「字面 + 引用」混合体：随递归继续展开（引擎同语义）
        val github = entry(title = "GitHub", password = "gh_secret")
        val relay = entry(title = "Relay", password = "head-{REF:P@T:GitHub}")
        assertExpandsTo(
            "{REF:P@T:Relay}",
            rootWith(github, relay),
            "head-gh_secret"
        )
    }

    @Test
    fun `取值目标口令为空或缺失时解析为空`() {
        val noPassword = entry(title = "NoPass", password = null)
        val root = rootWith(noPassword)
        val out = expand("{REF:P@T:NoPass}", root)!!
        assertEquals("", String(out))
        Arrays.fill(out, '0')
    }
}
