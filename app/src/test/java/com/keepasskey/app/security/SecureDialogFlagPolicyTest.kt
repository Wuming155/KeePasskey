package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 敏感对话框窗口 `FLAG_SECURE`（[SecureDialog]）的裁决内核与接线守护。
 *
 * 缺口背景：`FLAG_SECURE` 是**窗口级**属性，不会从 Activity 窗口传播到该 Activity 创建的
 * 其它窗口。官方 Assist 指南要求「每个由 activity 创建的窗口（包括对话框）都必须显式设置」
 * （<https://developer.android.com/training/articles/assistant#excluding_views>）。
 * Compose 的 `AlertDialog` 由 `Dialog` 创建独立窗口，故本仓原先只施加于 Activity 窗口的
 * 全部 `FLAG_SECURE` 施加点留下了对话框盲区（主密码修改 / 子库凭据 / 附件预览 / 修订差异）。
 *
 * 覆盖范围（本仓可判定部分）：
 * 1. 纯逻辑 [SecureDialogFlagPolicy]：fail-safe 与「只撤销自己施加的 flag」两条不变式；
 * 2. 静态接线：四个敏感对话框确实经 [SecureDialog] / [SecureDialogWindowEffect] 接线
 *    （该防护的失效形态是接线被静默删除，JVM 无法构造真实对话框窗口）。
 *
 * 未覆盖（需设备侧验证）：真实 `addFlags` / `clearFlags` 对对话框窗口的生效与截图屏蔽效果，
 * 需要 window token 与真实 WindowManager 服务。
 */
class SecureDialogFlagPolicyTest {

    // ===== 1. 裁决内核 =====

    @Test
    fun `解析到对话框窗口时施加 FLAG_SECURE`() {
        assertEquals(
            SecureDialogFlagAction.ADD_SECURE,
            SecureDialogFlagPolicy.onEnter(dialogWindowResolved = true)
        )
    }

    @Test
    fun `取不到对话框窗口时 fail-safe 不动作`() {
        // 绝不为了让 flag「看起来生效」而改写 Activity 窗口：那会破坏用户开关键语义
        assertEquals(
            SecureDialogFlagAction.NONE,
            SecureDialogFlagPolicy.onEnter(dialogWindowResolved = false)
        )
    }

    @Test
    fun `仅撤销本包装自己施加过的 flag`() {
        assertEquals(
            SecureDialogFlagAction.CLEAR_SECURE,
            SecureDialogFlagPolicy.onDispose(addedByThisWrapper = true)
        )
        assertEquals(
            "未施加过（含 fail-safe 空操作）时不得清理他人设置的 FLAG_SECURE",
            SecureDialogFlagAction.NONE,
            SecureDialogFlagPolicy.onDispose(addedByThisWrapper = false)
        )
    }

    @Test
    fun `进入与撤销互为逆操作`() {
        // 施加过 → 撤销；未施加（取不到窗口）→ 不动作
        assertEquals(
            SecureDialogFlagAction.CLEAR_SECURE,
            SecureDialogFlagPolicy.onDispose(
                addedByThisWrapper =
                    SecureDialogFlagPolicy.onEnter(dialogWindowResolved = true) ==
                        SecureDialogFlagAction.ADD_SECURE
            )
        )
        assertEquals(
            SecureDialogFlagAction.NONE,
            SecureDialogFlagPolicy.onDispose(
                addedByThisWrapper =
                    SecureDialogFlagPolicy.onEnter(dialogWindowResolved = false) ==
                        SecureDialogFlagAction.ADD_SECURE
            )
        )
    }

    // ===== 2. 静态接线守护 =====

    @Test
    fun `敏感对话框全部接入对话框窗口防护`() {
        val violations = mutableListOf<String>()

        SECURE_DIALOG_CALL_SITES.forEach { site ->
            val actual = readSource(site.path).windowedCountOf(site.invocation)
            if (actual < site.expectedCalls) {
                violations += "${site.path}：期望 ≥${site.expectedCalls} 处 `$site.invocation`，实际 $actual 处"
            }
        }

        assertTrue(
            "以下敏感对话框缺少对话框窗口 FLAG_SECURE 接线：$violations",
            violations.isEmpty()
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("清单文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private fun String.windowedCountOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    private companion object {
        /**
         * 敏感对话框文件 → 期望的接线（调用形态 + 最少处数）。
         *
         * 只匹配**调用形态**（含 `{` 或 `()`），避免 import 行与说明注释造成假通过：
         * - 主密码修改（内含两个 SecurePasswordField）：`SecureDialog { }` 包装 1 处；
         * - 子库挂载（含子库主密码）+ 子库凭据补录：`SecureDialogWindowEffect()` 2 处；
         * - 修订差异（密码明文对比）+ 附件预览：`SecureDialog { }` 包装 2 处。
         */
        val SECURE_DIALOG_CALL_SITES = listOf(
            SecureDialogCallSite(
                path = "app/src/main/java/com/keepasskey/app/ui/screens/settings/MasterKeyChangeDialog.kt",
                invocation = "SecureDialog {",
                expectedCalls = 1
            ),
            SecureDialogCallSite(
                path = "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/ChildDatabaseDialogs.kt",
                invocation = "SecureDialogWindowEffect()",
                expectedCalls = 2
            ),
            SecureDialogCallSite(
                path = "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailPreviewDiffComponents.kt",
                invocation = "SecureDialog {",
                expectedCalls = 2
            )
        )

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        const val ROOT_SEARCH_DEPTH = 4
    }

    /** 单条接线清单项：文件路径 + 调用形态 + 最少处数 */
    private data class SecureDialogCallSite(
        val path: String,
        val invocation: String,
        val expectedCalls: Int
    )
}
