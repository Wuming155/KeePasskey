package com.keepasskey.app.security

import com.keepasskey.app.autofill.AutofillPickerViewModel
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.detail.EntryDetailUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-68（审计 M6）回归：**数据类默认 `toString()` 的明文泄漏面**。
 *
 * 缺陷形态：Kotlin `data class` 的默认 `toString()` 会展开全部属性——这些类型分别持有
 * 条目明文（`revealedPassword` 等）、凭据内容（`username` / `totpCode` / `cardCvv`）、
 * 自动填充明文口令与页面字段当前值。**一次 `log("$state")`、一次异常消息插值、
 * 一次 IDE 求值**（或任何把对象塞进字符串模板的写法）就会把明文写进日志/崩溃报告/堆快照。
 *
 * 本用例以**唯一哨兵串**填充所有敏感字段，断言 `toString()` 输出中**不出现该哨兵**，
 * 从而在编译与运行两层锁住「只出结构摘要」这一契约（对齐 `KdbxEntry` / `ProtectedString`
 * 既有做法）。
 *
 * 覆盖说明（如实声明）：`ParsedAutofillNode` 持有 Android `AutofillId`（JVM 单测无法构造
 * 真实实例），其 `toString()` 由 [静态源码断言][`ParsedAutofillNode 的 toString 不得插值字段内容`]
 * 守护——该文件不得出现对 `text` / `label` / `htmlName` 的插值。
 */
class SensitiveToStringRedactionTest {

    /** 全字段共用的唯一哨兵：任何一处内容被展开都会命中 */
    private val sentinel = "S3NT1N3L-D0-N0T-LEAK"

    private fun assertNoSentinel(rendered: String, typeName: String) {
        assertFalse(
            "$typeName.toString() 不得展开任何条目内容/明文（实际：$rendered）",
            rendered.contains(sentinel, ignoreCase = true)
        )
    }

    @Test
    fun `UiVaultEntry 的 toString 不得泄漏条目内容`() {
        val entry = UiVaultEntry(
            id = "entry-1",
            title = sentinel,
            username = sentinel,
            passwordMasked = sentinel,
            url = sentinel,
            passkeyRpId = sentinel,
            totpCode = sentinel,
            notes = sentinel,
            updatedAt = sentinel,
            createdAt = sentinel,
            cardNumberMasked = sentinel,
            cardHolder = sentinel,
            cardExpiry = sentinel,
            cardCvv = sentinel,
            tags = listOf(sentinel),
            autoTypeSequence = sentinel,
            overrideUrl = sentinel,
            customFields = listOf(UiCustomField(id = "f1", key = sentinel, value = sentinel, isProtected = true)),
            attachments = listOf(UiAttachment(id = "a1", fileName = sentinel, fileSizeFormatted = sentinel)),
            revisions = listOf(
                UiEntryRevision(id = "r1", modifiedAt = sentinel, summary = sentinel, username = sentinel, notes = sentinel),
            ),
        )
        assertNoSentinel(entry.toString(), "UiVaultEntry")
    }

    @Test
    fun `EntryDetailUiState 的 toString 不得泄漏明文`() {
        val state = EntryDetailUiState(
            entry = UiVaultEntry(id = "entry-1", title = sentinel, username = sentinel, url = sentinel),
            groupPath = sentinel,
            revealedPassword = sentinel,
            revealedRevisionPasswords = mapOf("r1" to sentinel),
            revealedProtectedFields = mapOf("f1" to sentinel),
            liveTotpCode = sentinel,
            // 调用方包名属**非秘密元数据**（契约允许打印，用于三角定位），此处用真实形态值
            autofillBoundPackage = "com.example.caller",
        )
        val rendered = state.toString()
        assertNoSentinel(rendered, "EntryDetailUiState")
        assertTrue(
            "结构摘要仍应保留非秘密元数据（调用方包名）与条目 id，便于排障：$rendered",
            rendered.contains("com.example.caller") && rendered.contains("entry-1")
        )
    }

    @Test
    fun `AutofillPickerViewModel_Credentials 的 toString 不得泄漏用户名与口令`() {
        val credentials = AutofillPickerViewModel.Credentials(username = sentinel, password = sentinel)
        val rendered = credentials.toString()
        assertNoSentinel(rendered, "Credentials")
        assertTrue(
            "摘要应如实体现「已重写为长度描述」：$rendered",
            rendered.contains("redacted")
        )
    }

    @Test
    fun `四个类型均覆写 toString 且 ParsedAutofillNode 不得插值字段内容`() {
        val files = mapOf(
            "UiVaultEntry" to "app/src/main/java/com/keepasskey/app/ui/model/UiModels.kt",
            "EntryDetailUiState" to "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailUiState.kt",
            "Credentials" to "app/src/main/java/com/keepasskey/app/autofill/AutofillPickerViewModel.kt",
            "ParsedAutofillNode" to "app/src/main/java/com/keepasskey/app/autofill/AutofillStructureScan.kt",
        )
        for ((typeName, path) in files) {
            val source = readSource(path)
            assertTrue(
                "$typeName 所在文件必须覆写 toString()（ISSUE-P2-68）",
                source.contains("override fun toString(): String")
            )
        }

        // ParsedAutofillNode：text（页面字段当前值）/ label / htmlName 一律不得插值
        val scanSource = readSource(files.getValue("ParsedAutofillNode"))
        val marker = "override fun toString(): String"
        val start = scanSource.indexOf(marker)
        assertTrue("未找到 ParsedAutofillNode.toString 定义", start >= 0)
        // 取定义后的固定窗口（体内含 `${...}` 花括号，不能按 `}` 截断）
        val toStringBody = scanSource.substring(start, minOf(start + 800, scanSource.length))
        for (forbidden in listOf("\${text}", "\$text", "\${label}", "\$label", "\${htmlName}", "\$htmlName")) {
            assertFalse(
                "ParsedAutofillNode.toString() 不得插值 $forbidden（页面字段内容可能为明文）",
                toStringBody.contains(forbidden)
            )
        }
        assertTrue(
            "ParsedAutofillNode.toString() 应以长度替代内容（textLength）",
            toStringBody.contains("textLength=")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }

        const val ROOT_SEARCH_DEPTH = 6
    }
}
