package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P3-371` 三处 `{REF:}` 消费点展开的接线守卫（静态源码比对）。
 *
 * 该条目的三处漏网各有独立形态（CM 回传 / 选择器用户名侧 / 自定义字段复制），
 * 其中两处位于不可直接宿主化的 Activity 回调与剪贴板协程内——按本仓接线守卫先例，
 * 以「消费点必须出现 resolveFieldReferences 且声明正确 RefField 面」为判据，
 * 再由选择器侧的功能用例（`AutofillPickerViewModelCredentialLookupTest`）提供行为级证据。
 */
class FieldRefConsumerExpansionWiringTest {

    private fun readSource(path: String): String {
        // 测试 JVM 工作目录不保证在仓库根（体例同 AutofillChannelSwitchWiringTest：向上找仓库根）
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(ROOT_SEARCH_DEPTH) {
            val candidate = dir ?: return@repeat
            val file = File(candidate, path)
            if (file.isFile) return file.readText()
            dir = candidate.parentFile
        }
        error("源码缺失：$path（自 ${System.getProperty("user.dir")} 向上 $ROOT_SEARCH_DEPTH 层未定位仓库根）")
    }

    @Test
    fun `选择器用户名侧经 USER_NAME 面展开`() {
        val source = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillPickerViewModel.kt")
        val resolveCount = Regex("""resolveFieldReferences\(""").findAll(source).count()
        assertTrue(
            "用户名与密码两侧都应经 resolveFieldReferences（同函数口径统一）",
            resolveCount >= 2
        )
        assertTrue(
            "用户名侧必须声明 USER_NAME 非口令面",
            source.contains("FieldReferenceEngine.RefField.USER_NAME")
        )
        assertTrue(
            "密码侧既有 PASSWORD 面不得回退",
            source.contains("FieldReferenceEngine.RefField.PASSWORD")
        )
    }

    @Test
    fun `CM 回传两通道都补展开`() {
        val source = readSource("app/src/main/java/com/keepasskey/app/passkey/PasswordFillActivity.kt")
        assertTrue(
            "回传必须经 resolveFieldReferences 展开",
            source.contains("resolveFieldReferences(")
        )
        val userFace = Regex("""resolveFieldReferences\(\s*entryIdHex, rawUsername, RefField\.USER_NAME""")
            .containsMatchIn(source)
        val passwordFace = Regex("""resolveFieldReferences\(\s*entryIdHex, rawPassword, RefField\.PASSWORD""")
            .containsMatchIn(source)
        assertTrue("用户名通道须以 USER_NAME 面展开", userFace)
        assertTrue("密码通道须以 PASSWORD 面展开", passwordFace)
        assertTrue(
            "展开仍须位于验证通过之后的回传路径（onVerified → deliverPassword）",
            source.contains("onVerified = { deliverPassword(entry) }")
        )
    }

    @Test
    fun `自定义字段复制经 USER_NAME 面展开且口令面掩码`() {
        val source = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailCopyCoordinator.kt"
        )
        assertTrue(
            "自定义字段复制必须经 resolveFieldReferences 展开",
            source.contains("resolveFieldReferences(")
        )
        assertTrue(
            "自定义字段为非口令消费点：必须声明 USER_NAME 面（{REF:P@…} 掩码输出）",
            Regex("""resolveFieldReferences\(\s*entryId, raw,\s*FieldReferenceEngine\.RefField\.USER_NAME""")
                .containsMatchIn(source)
        )
    }

    @Test
    fun `三处消费点各自恰有一次展开调用防止重复解析`() {
        // 每个文件的 resolveFieldReferences 调用数与消费点数对齐（防复制粘贴出双解析）
        val expectedCalls = mapOf(
            "app/src/main/java/com/keepasskey/app/autofill/AutofillPickerViewModel.kt" to 2,
            "app/src/main/java/com/keepasskey/app/passkey/PasswordFillActivity.kt" to 2,
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailCopyCoordinator.kt" to 3
        )
        expectedCalls.forEach { (path, expected) ->
            val count = Regex("""resolveFieldReferences\(""").findAll(readSource(path)).count()
            assertEquals("$path 展开调用数漂移", expected, count)
        }
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 6
    }
}
