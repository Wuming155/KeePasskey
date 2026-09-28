package com.keepasskey.app.passkey

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 就地新建页「生成密码」入口接线守卫（ISSUE-P3-373 AC③，体例同本仓源码守卫先例）。
 *
 * Compose 按钮的**渲染**宿主不可证伪（截图/真机承担）；但两条接线事实可静态锁定：
 * 1. 屏幕必须把 `onGeneratePassword` 呈为可点入口（草稿表单内）；
 * 2. 宿主必须复用**编辑页生成器**（`generatePasswordChars`），不得出现第二套生成逻辑
 *    （DRY 红线：生成器只允许存在一份字符池实现）。
 */
class PasswordDraftGenerateWiringTest {

    private fun readSource(path: String): String {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(6) {
            val candidate = dir ?: return@repeat
            val file = File(candidate, path)
            if (file.isFile) return file.readText()
            dir = candidate.parentFile
        }
        error("源码缺失：$path")
    }

    @Test
    fun `草稿表单呈现生成密码入口`() {
        val screen = readSource("app/src/main/java/com/keepasskey/app/passkey/PasswordDraftScreen.kt")
        assertTrue(
            "屏幕必须声明 onGeneratePassword 回调",
            screen.contains("onGeneratePassword: () -> Unit")
        )
        assertTrue(
            "回调必须接成可点入口（TextButton）",
            Regex("""TextButton\(onClick = onGeneratePassword""").containsMatchIn(screen)
        )
        assertTrue(
            "入口必须引用生成文案资源",
            screen.contains("R.string.cred_draft_generate")
        )
        assertTrue(
            "预览必须覆盖该参数（三态预览齐备）",
            Regex("""onGeneratePassword = \{\}""").findAll(screen).count() == 3
        )
    }

    @Test
    fun `宿主复用编辑页生成器且不经String中转`() {
        val activity = readSource("app/src/main/java/com/keepasskey/app/passkey/PasswordDraftActivity.kt")
        assertTrue(
            "必须复用 generatePasswordChars（禁止第二套生成逻辑）",
            activity.contains("generatePasswordChars(EntryEditUiState(), SecureRandom())")
        )
        assertTrue(
            "生成结果直接进 CharArray 通道（不经 String）",
            activity.contains("draftPassword = generated")
        )
        assertTrue(
            "旧口令数组必须先清零再替换",
            Regex("""draftPassword\.fill\('0'\)\s*draftPassword = generated""")
                .containsMatchIn(activity)
        )
    }
}
