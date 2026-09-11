package com.keepasskey.database.fieldref

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `{REF:...}` 字段引用引擎 · **设备侧回归锁**（ISSUE-P0-04）。
 *
 * ## 为什么必须单列一层设备侧用例
 * [FieldReferenceEngine] 的引用正则在 **JVM 的 `java.util.regex`** 上合法，但 **Android 运行时走
 * ICU4C**：结尾未转义的 `}` 被判为语法错误，使 `<clinit>` 抛 [ExceptionInInitializerError]。
 * 而 [FieldReferenceEngine] 的初始化点在**库列表逐条目投影**中
 * （`VaultListDecorationsProvider` → `EntryReferenceDisplayResolver.present`），
 * 于是「库内 ≥1 条目」即导致列表渲染崩溃——**宿主 JVM 单测永远测不到**（其正则引擎更宽松），
 * `app`/`sync` 又无 `androidTest` 源集，缺陷因此长期潜伏。
 *
 * 本类在真实 Android 运行时上完成**类初始化**与**语义**双重回归：
 * 若正则再次变得对 ICU 非法，第一个用例即会在 `<clinit>` 阶段硬失败。
 *
 * 敏感数据：本用例只使用虚构占位值，且展示侧断言**掩码不外泄明文**。
 */
@RunWith(AndroidJUnit4::class)
class FieldReferenceEngineDeviceTest {

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

    @Test
    fun `引用正则可在Android运行时完成类初始化（ICU 兼容回归锁）`() {
        // ISSUE-P0-04：此行若触发的 <clinit> 正则在 ICU 上非法，会直接抛 ExceptionInInitializerError
        assertTrue(
            "含 {REF:...} 的文本必须被识别为引用",
            FieldReferenceEngine.containsReference("{REF:U@T:GitHub}")
        )
        assertFalse(
            "普通文本不应被误判为引用",
            FieldReferenceEngine.containsReference("这是一个普通备注，没有字段引用")
        )
    }

    @Test
    fun `设备上字段引用按标题检索解析正确`() {
        val github = entry(title = "GitHub", username = "octocat", password = "gh_secret")
        val consumer = entry(title = "消费条目", username = "{REF:U@T:GitHub}")
        val resolved = FieldReferenceEngine.resolve(consumer.userName, rootWith(github, consumer))
        assertEquals("octocat", resolved)
    }

    @Test
    fun `展示侧受保护引用只输出掩码不物化明文`() {
        val github = entry(title = "GitHub", password = "gh_secret")
        val raw = "{REF:P@T:GitHub}"
        val displayed = FieldReferenceEngine.resolveForDisplay(raw, rootWith(github))
        assertEquals(FieldReferenceEngine.PROTECTED_PLACEHOLDER, displayed)
        assertFalse("展示结果不得包含密码明文", displayed.contains("gh_secret"))
    }
}
