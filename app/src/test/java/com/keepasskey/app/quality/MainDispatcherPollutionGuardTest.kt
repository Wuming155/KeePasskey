package com.keepasskey.app.quality

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 测试调度器跨用例污染防护的**接线守卫**（`ISSUE-P3-189`，根因同归档批次 §18 / §150）。
 *
 * 本条缺陷的形态是「**偶发红、位置随执行顺序漂移**」——跑一次绿不构成修复证据，
 * 而漏防护的用例污染的是**别人**（表现为无关用例红）。故验收不靠多轮实跑，
 * 而靠静态扫描把「凡切换过 Main 派发器的用例必须按序收尾」锁成编译期可见的硬判据。
 * 本仓静态源码守卫先例：`AlgoHotPathGuardsTest` / `AutofillAuthResultWiringTest`。
 *
 * 判据三条：
 * 1. `app/src/test` 下任何调用 `Dispatchers.resetMain()` 的文件，只能是守卫本体、
 *    或带「污染防护豁免（ISSUE-P3-189」注记并写明依据的文件；
 * 2. 任何 `Dispatchers.setMain(` **且**构造 ViewModel 的用例，必须把该 ViewModel 登记到
 *    守卫（`MainDispatcherGuard.track(`）——否则 `@After` 无从取消其在途作用域；
 * 3. 守卫本体的收尾**顺序不可颠倒**：先取消作用域、后 `resetMain()`。
 *    反序即本条缺陷本体；「给 `resetMain()` 包 try/catch 吞异常」或「调大等待超时」属
 *    掩盖污染而非修复，故守卫本体亦禁止出现 `runCatching` / `try {` 兜底。
 */
class MainDispatcherPollutionGuardTest {

    @Test
    fun `resetMain 调用点必须经统一守卫收尾或留有依据的豁免`() {
        val offenders = testSources().filter { file ->
            val source = file.readText()
            relative(file) != GUARD &&
                source.contains("Dispatchers.resetMain()") &&
                !source.contains("MainDispatcherGuard.tearDown()") &&
                !source.contains(EXEMPT_MARKER)
        }
        if (offenders.isNotEmpty()) {
            fail(
                "以下用例直接调用 Dispatchers.resetMain() 而未经 MainDispatcherGuard.tearDown()" +
                    "（顺序：先取消 ViewModel 作用域、再恢复 Main）：\n" +
                    offenders.joinToString("\n") { relative(it) } +
                    "\n无泄漏面的用例请按 `ISSUE-P3-189` 就地注明「$EXEMPT_MARKER + 依据」。"
            )
        }
    }

    @Test
    fun `切换过 Main 派发器且构造 ViewModel 的用例必须登记到守卫`() {
        val offenders = testSources().filter { file ->
            val source = file.readText()
            source.contains("Dispatchers.setMain(") &&
                VIEWMODEL_CONSTRUCTION.containsMatchIn(source) &&
                !source.contains("MainDispatcherGuard.track(")
        }
        if (offenders.isNotEmpty()) {
            fail(
                "以下用例构造了 ViewModel 却未登记到 MainDispatcherGuard.track()：" +
                    "`runTest` 结束不取消 viewModelScope，其真实线程上的在途工作会在 resetMain()" +
                    " 之后回跳 Main 并污染同 JVM 后续用例（ISSUE-P3-189）。请在构造处登记：\n" +
                    offenders.joinToString("\n") { relative(it) }
            )
        }
    }

    @Test
    fun `守卫必须先取消作用域再恢复 Main 且不得吞异常`() {
        val source = readSource(GUARD)
        val cancelAt = source.indexOf(".cancel()")
        val resetAt = source.indexOf("Dispatchers.resetMain()")
        assertTrue("守卫须包含取消作用域与恢复 Main 两步（定位失败即签名已变）", cancelAt >= 0 && resetAt >= 0)
        assertTrue("必须先取消作用域、再 resetMain()（反序即 ISSUE-P3-189 的缺陷本体）", cancelAt < resetAt)
        assertTrue(
            "守卫不得以 try/catch 或 runCatching 吞掉收尾异常（那是掩盖污染而非修复）",
            !source.contains("runCatching") && !source.contains("try {")
        )
    }

    private fun testSources(): List<File> {
        val root = File(repositoryRoot, TEST_SOURCE_ROOT)
        assertTrue("测试源集目录不存在：$TEST_SOURCE_ROOT", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().also {
            assertTrue("测试源集为空（目录定位异常）", it.isNotEmpty())
        }
    }

    private fun relative(file: File): String =
        file.absolutePath
            .replace(repositoryRoot.absolutePath + File.separator, "")
            .replace('\\', '/')

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val TEST_SOURCE_ROOT = "app/src/test/java"
        const val GUARD = "app/src/test/java/com/keepasskey/app/testutil/MainDispatcherGuard.kt"
        const val EXEMPT_MARKER = "污染防护豁免（ISSUE-P3-189"

        /** 形如 `val viewModel = EntryDetailViewModel(` 的直接构造点 */
        val VIEWMODEL_CONSTRUCTION = Regex("=\\s*[A-Z]\\w*ViewModel\\(")

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
}
