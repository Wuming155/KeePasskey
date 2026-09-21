package com.keepasskey.app.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-12 遮挡触摸过滤「接线清单 ⇄ 实际调用点」一致性检查（静态源码比对）。
 *
 * 本项防护的失效形态不是「判定错」，而是**接线被静默移除**：JVM 单测无法构造被遮挡的窗口，
 * Compose 副作用也无法在不引入 instrumentation 的前提下断言，故以源码文本比对守护接线本身
 * （仓库根定位法沿用 [com.keepasskey.app.log.LogHygieneTest]，为同源静态检查先例）。
 *
 * 守护两条不变式：
 * 1. 清单内的每个敏感窗口文件仍存在，且其根节点（具名根 Composable 的函数体 / `setContent {}` 的
 *    lambda 体）**首条语句**就是 [ApplyObscuredTouchFilter]——防止接线被删除、或下沉到
 *    不覆盖整窗的深层子组件；
 * 2. 首条语句判定跳过空行与注释行，允许在其上方保留说明注释（本项要求「根节点首行调用 + 中文注释」）。
 *
 * 不作反向断言：对「判定为可不接」的纯信息展示屏**不断言无调用**——那些文件由其他工作组认领，
 * 反向断言会与其合法改动互相打架（例如设置页后续接入纵深防御即会误报失败）。
 */
class ObscuredTouchWiringTest {

    /** 以「具名根 Composable 函数体」为接线锚点的敏感屏：app 模块相对路径 → 根函数名 */
    private val rootComposableScreens = mapOf(
        "app/src/main/java/com/keepasskey/app/ui/screens/database/DatabasePickerScreen.kt" to
            "DatabasePickerScreen",
        "app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditScreen.kt" to
            "EntryEditScreen",
        "app/src/main/java/com/keepasskey/app/ui/screens/authenticator/AuthenticatorScreen.kt" to
            "AuthenticatorScreen",
        "app/src/main/java/com/keepasskey/app/ui/screens/conflict/ConflictResolutionScreen.kt" to
            "ConflictResolutionScreen"
    )

    /** 以「`setContent {}` lambda 体」为接线锚点的敏感窗口（独立 Activity 入口，无具名根 Composable） */
    private val setContentWindows = listOf(
        // Passkey / Credential 凭据流程：三处宿主（PasswordFill / PasskeyAssertion / PasskeyCreate）
        // 共用此手动确认窗口，且其父类未施加窗口级过滤，属本项防护的真实缺口
        "app/src/main/java/com/keepasskey/app/passkey/CredentialVerificationLauncher.kt",
        // Credential Manager 链式解锁窗口
        "app/src/main/java/com/keepasskey/app/passkey/CredentialUnlockActivity.kt",
        // ISSUE-P2-220：创建链路 fail-closed 拒绝原因页（渲染在基类自己的受保护窗口内）
        "app/src/main/java/com/keepasskey/app/passkey/BaseCredentialActivity.kt",
        // 自动填充两窗口（ISSUE-P2-09 既有接线，纳入清单防止回归移除）
        "app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockActivity.kt",
        "app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmActivity.kt"
    )

    @Test
    fun `敏感屏根 Composable 首部调用遮挡触摸过滤`() {
        val violations = rootComposableScreens.mapNotNull { (path, functionName) ->
            val head = headStatementOfRootFunction(path, functionName)
            if (head == OBSCURED_FILTER_CALL) null else "$path#fun $functionName 的首条语句为「$head」"
        }
        assertTrue(
            "以下敏感屏根 Composable 首部未调用 $OBSCURED_FILTER_CALL：$violations",
            violations.isEmpty()
        )
    }

    @Test
    fun `敏感窗口 setContent 根首部调用遮挡触摸过滤`() {
        val violations = setContentWindows.mapNotNull { path ->
            val head = headStatementOfSetContent(path)
            if (head == OBSCURED_FILTER_CALL) null else "$path#setContent 的首条语句为「$head」"
        }
        assertTrue(
            "以下敏感窗口 setContent 根首部未调用 $OBSCURED_FILTER_CALL：$violations",
            violations.isEmpty()
        )
    }

    /**
     * `ISSUE-P2-245`（2026-09-21 接线）：**对话框窗口**的加固接线必须是**成对**的——
     * `FLAG_SECURE`（防截屏）与遮挡触摸过滤（防点击劫持）属**同形的窗口级缺口**
     * （两者都是「不跨窗口传播」的属性），只接其一即留下盲区。
     *
     * 失效形态与本类既有两条一致：判定不会算错，错的是**接线被静默删掉**（Compose 对话框窗口
     * 无法在宿主 JVM 上构造，故仍以源码文本比对守护）。判据四项：
     * 1. `FLAG_SECURE` 的**施加**（`addFlags(… FLAG_SECURE)`）；
     * 2. `FLAG_SECURE` 的**撤销**（`clearFlags(… FLAG_SECURE)`）；
     * 3. 遮挡触摸过滤的**施加**（必须有 `filterTouchesWhenObscured = true` 这一行；仅剩「读原值 / 还原」
     *    两处出现**不算**通过——那正是「把施加那行删掉」的故障形态）；
     * 4. **对话框窗口解析**（`dialogWindowOrNull()` 或 `DialogWindowProvider`）——没有第 4 项则
     *    前三条可能作用在 Activity 窗口上（`FLAG_SECURE` 会破坏用户开关语义，过滤则不覆盖对话框）。
     * 不变量之外的内容（策略函数、KDoc）不在此断言范围内。
     */
    @Test
    fun `对话框窗口加固同时施加防截屏与遮挡触摸过滤`() {
        val source = readSource(SECURE_DIALOG_WINDOW)
        val violations = mutableListOf<String>()

        if (!source.contains("addFlags(WindowManager.LayoutParams.FLAG_SECURE)")) {
            violations += "缺少 FLAG_SECURE 施加（addFlags）"
        }
        if (!source.contains("clearFlags(WindowManager.LayoutParams.FLAG_SECURE)")) {
            violations += "缺少 FLAG_SECURE 撤销（clearFlags）"
        }
        if (!source.contains("filterTouchesWhenObscured = true")) {
            violations += "缺少遮挡触摸过滤的施加（须有 `filterTouchesWhenObscured = true` 一行）"
        }
        if (source.windowedCountOf("filterTouchesWhenObscured") < 2) {
            violations += "遮挡触摸过滤未成对（施加 + 还原原值，`filterTouchesWhenObscured` 应至少出现 2 次）"
        }
        if (!source.contains("dialogWindowOrNull()") && !source.contains("DialogWindowProvider")) {
            violations += "缺少对话框窗口解析（既无 dialogWindowOrNull() 也无 DialogWindowProvider）"
        }

        assertTrue(
            "对话框窗口加固接线不完整（$SECURE_DIALOG_WINDOW）：$violations" +
                "——FLAG_SECURE 与遮挡触摸过滤必须成对施加于**对话框窗口**（见 ISSUE-P2-245）",
            violations.isEmpty()
        )
    }

    /** 子串出现次数（源码文本比对用；不引入正则以免转义歧义） */
    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    /** [occurrencesOf] 的可读别名（与 `SecureDialogFlagPolicyTest` 同名的本类局部实现） */
    private fun String.windowedCountOf(needle: String): Int = occurrencesOf(needle)

    /** 具名根 Composable 的函数体首条语句（自 `fun <name>(` 找到 `) {` 起算） */
    private fun headStatementOfRootFunction(path: String, functionName: String): String {
        val lines = readSource(path).lines()
        val anchor = lines.indexOfFirst { Regex("""^\s*fun $functionName\s*\(""").containsMatchIn(it) }
        assertTrue("$path：未找到根 Composable `fun $functionName(`", anchor >= 0)
        val bodyStart = anchor + lines.drop(anchor).indexOfFirst { BODY_OPEN.containsMatchIn(it) }
        assertTrue("$path：未找到 `fun $functionName` 的 `) {` 函数体起始行", bodyStart > anchor)
        return firstStatement(lines, bodyStart + 1)
    }

    /** `setContent {}` lambda 体的首条语句 */
    private fun headStatementOfSetContent(path: String): String {
        val lines = readSource(path).lines()
        val anchor = lines.indexOfFirst { SET_CONTENT_OPEN.containsMatchIn(it) }
        assertTrue("$path：未找到 `setContent {` 根节点", anchor >= 0)
        return firstStatement(lines, anchor + 1)
    }

    /** 自 [fromLine]（0 基）向下跳过空行与注释行后的首条语句，去除行首缩进 */
    private fun firstStatement(lines: List<String>, fromLine: Int): String = lines
        .drop(fromLine)
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !isCommentLine(it) }
        .orEmpty()

    private fun isCommentLine(line: String): Boolean =
        line.startsWith("//") || line.startsWith("/*") || line.startsWith("*") || line.startsWith("*/")

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("清单文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val OBSCURED_FILTER_CALL = "ApplyObscuredTouchFilter()"

        /** `ISSUE-P2-245` 的对话框窗口加固入口（app 模块相对路径） */
        const val SECURE_DIALOG_WINDOW = "app/src/main/java/com/keepasskey/app/security/SecureDialog.kt"

        /** 函数体起始行（Kotlin 约定的 `) {`） */
        val BODY_OPEN = Regex("""^\s*\)\s*\{\s*$""")

        /** `setContent {` 根（要求行首非 `*`，避免命中 KDoc 中对 setContent 的引用） */
        val SET_CONTENT_OPEN = Regex("""^\s*setContent\s*\{\s*$""")

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

        /** 自工作目录向上回溯的层数（app 模块测试工作目录为 app/，1 层即仓库根） */
        const val ROOT_SEARCH_DEPTH = 4
    }
}
