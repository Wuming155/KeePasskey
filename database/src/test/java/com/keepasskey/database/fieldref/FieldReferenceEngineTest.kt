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

    // ── ISSUE-P3-163：一次解析内共享「引用目标索引」，语义须与逐引用现场展平逐字等价 ──

    @Test
    fun `同一文本中的多个引用与嵌套链共享同一索引且结果不变`() {
        val a = entry(title = "A", username = "user-a")
        val b = entry(title = "B", username = "{REF:U@T:A}")
        val root = rootWith(a, b)

        assertEquals(
            "三个引用（含经 B 间接指向 A 的嵌套链）必须全部解析出来",
            "user-a|user-a|user-a",
            FieldReferenceEngine.resolve(
                "{REF:U@T:A}|{REF:U@T:B}|{REF:U@T:A}",
                root,
                FieldReferenceEngine.RefField.USER_NAME
            )
        )
    }

    @Test
    fun `同值多条目时取文档序首个`() {
        val first = entry(title = "Same", username = "first")
        val second = entry(title = "SAME", username = "second")
        val root = rootWith(first, second)

        assertEquals(
            "文档序首个胜出（索引同值只保留首个）",
            "first",
            FieldReferenceEngine.resolve("{REF:U@T:Same}", root, FieldReferenceEngine.RefField.USER_NAME)
        )
        assertEquals(
            "检索文本自身的大小写不影响结果",
            "first",
            FieldReferenceEngine.resolve("{REF:U@T:sAmE}", root, FieldReferenceEngine.RefField.USER_NAME)
        )
    }

    @Test
    fun `忽略大小写的等价类与逐字符折叠一致`() {
        // 希腊语：'Σ'（大写）与 'ς'（词尾小写）在 equalsIgnoreCase 下相等，但 `lowercase()` 不相等
        // （'ς'.lowercase() == 'ς' 而 'Σ'.lowercase() == 'σ'）。
        // 索引因此必须用 String.CASE_INSENSITIVE_ORDER 而非 lowercase 归一化做键——
        // 否则该引用会从「命中」退化为「保持原文」，属静默语义漂移。
        val target = entry(title = "Σ", username = "sigma-user")
        val root = rootWith(target)

        assertEquals(
            "非 ASCII 码点上的大小写等价类必须与 equalsIgnoreCase 一致",
            "sigma-user",
            FieldReferenceEngine.resolve("{REF:U@T:ς}", root, FieldReferenceEngine.RefField.USER_NAME)
        )
    }

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

    // ===== ENG-20261028-13：口令检索面（`SearchIn=P`）不物化全库口令索引 =====

    @Test
    fun `口令检索命中目标后不再读取其后条目的口令`() {
        // 可观察契约（非源码结构断言）：旧实现按口令建 `TreeMap<String, KdbxEntry>`，构建时会对
        // **全库**条目调用 `readString()`——于是一处已清零（不可读）的口令就让整次解析抛
        // IllegalStateException，且全库口令明文以不可擦除的 String 键驻留到索引释放。
        // 现实现改为逐条 Char 区域比较、用毕清零、命中即停：命中之后的条目根本不被读取，
        // 因此同一输入必须照常解析成功。
        val hit = entry(title = "Hit", username = "octocat", password = "gh_secret")
        val erased = entry(title = "Erased", username = "ghost", password = "stale_secret")
        erased.password!!.clear()

        assertEquals(
            "口令检索只解密到首个命中为止，不得遍历全库口令",
            "octocat",
            FieldReferenceEngine.resolve(
                "{REF:U@P:gh_secret}", rootWith(hit, erased), FieldReferenceEngine.RefField.PASSWORD
            )
        )
    }

    @Test
    fun `口令检索面大小写不敏感且同值取文档序首个`() {
        val first = entry(title = "First", username = "user-first", password = "shared_secret")
        val second = entry(title = "Second", username = "user-second", password = "shared_secret")
        val root = rootWith(first, second)

        for (searchText in listOf("SHARED_SECRET", "Shared_Secret", "shared_secret")) {
            assertEquals(
                "口令检索的等值口径须与公开字段索引同为逐字符大小写折叠（$searchText）",
                "user-first",
                FieldReferenceEngine.resolve(
                    "{REF:U@P:$searchText}", root, FieldReferenceEngine.RefField.PASSWORD
                )
            )
        }
    }

    @Test
    fun `口令检索面未命中保守回退原文且递归继续展开`() {
        val secret = entry(title = "Secret", password = "final_secret")
        // 取值目标（Relay）的 UserName 自身仍是引用：链式继续展开（与 Char 通道同语义）
        val relay = entry(title = "Relay", username = "{REF:P@T:Secret}", password = "relay_secret")
        val root = rootWith(secret, relay)

        assertEquals(
            "按口令命中 Relay 后取其 UserName，须随引用链展开至链终端",
            "final_secret",
            FieldReferenceEngine.resolve("{REF:U@P:relay_secret}", root, FieldReferenceEngine.RefField.PASSWORD)
        )
        assertEquals(
            "未命中的口令检索保持原文（保守不吞）",
            "{REF:U@P:nope}",
            FieldReferenceEngine.resolve("{REF:U@P:nope}", root, FieldReferenceEngine.RefField.PASSWORD)
        )
    }

    @Test
    fun `空口令条目与空检索文本按首个空口令条目命中`() {
        // 无口令字段（password == null）的条目在扫描中被跳过，不得因缺字段失败；
        // 空口令（长度 0）是合法检索目标，与旧 TreeMap 键为 "" 的行为逐字一致。
        val noPass = entry(title = "NoPass", username = "u-no-pass", password = null)
        val emptyPass = entry(title = "EmptyPass", username = "u-empty", password = "")

        assertEquals(
            "空检索文本命中首个空口令条目",
            "u-empty",
            FieldReferenceEngine.resolve(
                "{REF:U@P:}", rootWith(noPass, emptyPass), FieldReferenceEngine.RefField.PASSWORD
            )
        )
        assertEquals(
            "检索文本非空时不得命中空口令条目",
            "{REF:U@P:gh_secret}",
            FieldReferenceEngine.resolve(
                "{REF:U@P:gh_secret}", rootWith(noPass, emptyPass), FieldReferenceEngine.RefField.PASSWORD
            )
        )
    }

    // ===== ISSUE-P1-25 AC①：口令面引用检测（复制通道敏感分流依据） =====

    @Test
    fun `口令面检测取值面为P时命中`() {
        assertTrue(FieldReferenceEngine.containsPasswordFaceReference("{REF:P@T:GitHub}"))
    }

    @Test
    fun `口令面检测检索面为P时命中`() {
        assertTrue(FieldReferenceEngine.containsPasswordFaceReference("{REF:U@P:secret}"))
    }

    @Test
    fun `口令面检测大小写不敏感`() {
        assertTrue(FieldReferenceEngine.containsPasswordFaceReference("{ref:p@t:GitHub}"))
    }

    @Test
    fun `口令面检测公开字段引用不命中`() {
        assertFalse(FieldReferenceEngine.containsPasswordFaceReference("{REF:U@T:GitHub}"))
        assertFalse(FieldReferenceEngine.containsPasswordFaceReference("{REF:T@A:example.com}"))
        assertFalse(FieldReferenceEngine.containsPasswordFaceReference("{REF:I@T:GitHub}"))
    }

    @Test
    fun `口令面检测混合文本中逐引用判定`() {
        val mixed = "前缀 {REF:U@T:GitHub} 中缀 {REF:P@T:Secret} 后缀"
        assertTrue(FieldReferenceEngine.containsPasswordFaceReference(mixed))
    }

    @Test
    fun `口令面检测无引用文本零开销直返`() {
        assertFalse(FieldReferenceEngine.containsPasswordFaceReference("plain_username"))
        assertFalse(FieldReferenceEngine.containsPasswordFaceReference(""))
    }
}
