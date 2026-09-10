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

        /** 函数体起始行（Kotlin 约定的 `) {`） */
        val BODY_OPEN = Regex("""^\s*\)\s*\{\s*$""")

        /** `setContent {` 根（要求行首非 `*`，避免命中 KDoc 中对 setContent 的引用） */
        val SET_CONTENT_OPEN = Regex("""^\s*setContent\s*\{\s*$""")

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

        /** 自工作目录向上回溯的层数（app 模块测试工作目录为 app/，1 层即仓库根） */
        const val ROOT_SEARCH_DEPTH = 4
    }
}
