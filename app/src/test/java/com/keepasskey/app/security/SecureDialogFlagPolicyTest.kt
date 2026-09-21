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

    /**
     * `ISSUE-P2-246`（2026-09-21 §254 修复）的静态守卫：**每个**敏感对话框调用点都必须显式要求
     * `securePolicy = SecureFlagPolicy.SecureOn`。
     *
     * 为什么非有这条不可：对话框窗口的 `FLAG_SECURE` 实际由 Compose 的 `SecureFlagPolicy` 决定，
     * 其默认 `Inherit` 以**宿主窗口**的该 flag 位为准（`AndroidDialog.android.kt` 取调用方
     * `LocalView` 作 `composeView` 求值），宿主窗口不带时 Compose 会**清除**本窗的 flag
     * ⇒ 本包装的 `addFlags` 单独不足以保住遮罩（真机实测：宿主不带时对话框窗口
     * `flags=0x1800002` 缺 `0x2000`；宿主带时含）。**删掉任一调用点的 `SecureOn` 即留下该缺口**，
     * 而这一删除在运行时**完全静默**（无异常、无日志），故只能以静态比对守护。
     *
     * 与上一条按「文件计数」同体例，但**独立成清单**：上一条锁的是「本包装接线存在」，
     * 本条锁的是「Compose 策略被判为 SecureOn」，两者失效方式不同，合并会互相掩盖。
     */
    @Test
    fun `敏感对话框调用点全部显式要求 SecureOn`() {
        val violations = SECURE_POLICY_CALL_SITES.mapNotNull { site ->
            val actual = readSource(site.path).windowedCountOf(SECURE_POLICY_REQUIREMENT)
            if (actual >= site.expectedCalls) {
                null
            } else {
                "${site.path}：期望 ≥${site.expectedCalls} 处 `$SECURE_POLICY_REQUIREMENT`，实际 $actual 处"
            }
        }

        assertTrue(
            "以下敏感对话框调用点未显式要求 SecureOn（宿主窗口不带 FLAG_SECURE 时其对话框窗口将不被遮罩，" +
                "见 ISSUE-P2-246）：$violations",
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

        /**
         * `ISSUE-P2-246`：调用点必须显式要求 `SecureOn`（与上一条**独立**，失效方式不同）——
         * 含 `securePolicy = SecureFlagPolicy.SecureOn` 的最少处数，按文件计。
         *
         * 计数依据（2026-09-21 现查 `grep -rn "    AlertDialog(" app/src/main/java`）：
         * 四个文件共 **7 个** `AlertDialog` 调用（`CreateVaultWizardDialog.kt` 2 =
         * 密钥文件备份提示 + 含主密码的建库向导；`ChildDatabaseDialogs.kt` 2 = 子库挂载 + 子库凭据补录；
         * `EntryDetailPreviewDiffComponents.kt` 2 = 修订差异 + 附件预览；`MasterKeyChangeDialog.kt` 1）。
         */
        val SECURE_POLICY_CALL_SITES = listOf(
            SecureDialogCallSite(
                path = "app/src/main/java/com/keepasskey/app/ui/screens/database/CreateVaultWizardDialog.kt",
                invocation = SECURE_POLICY_REQUIREMENT,
                expectedCalls = 2
            ),
            SecureDialogCallSite(
                path = "app/src/main/java/com/keepasskey/app/ui/screens/settings/MasterKeyChangeDialog.kt",
                invocation = SECURE_POLICY_REQUIREMENT,
                expectedCalls = 1
            ),
            SecureDialogCallSite(
                path = "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/ChildDatabaseDialogs.kt",
                invocation = SECURE_POLICY_REQUIREMENT,
                expectedCalls = 2
            ),
            SecureDialogCallSite(
                path = "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailPreviewDiffComponents.kt",
                invocation = SECURE_POLICY_REQUIREMENT,
                expectedCalls = 2
            )
        )

        /** 调用点必须出现的显式策略要求（Compose `DialogProperties.securePolicy`） */
        const val SECURE_POLICY_REQUIREMENT = "securePolicy = SecureFlagPolicy.SecureOn"

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
