package com.keepasskey.app.quality

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 测试调度器跨用例污染防护的**接线守卫**（`ISSUE-P3-189`；根因与路线见批次 §152 / §153）。
 *
 * 本条缺陷的形态是「**偶发红、位置随执行顺序漂移**」（§152 实测命中率 1/4）——跑几轮绿不构成修复证据，
 * 而漏防护的用例污染的是**别人**（表现为无关用例红）。故验收不靠多轮实跑，而靠静态扫描把口径锁成
 * 编译期可见的硬判据。本仓静态源码守卫先例：`AlgoHotPathGuardsTest`。
 *
 * **背景（路线①，§152 第 4 轮实测反证）**：`withContext(Dispatchers.Default)` 的块正常跑完后，
 * 回送结果给父协程时仍要访问父作用域的 `Dispatchers.Main`（`DispatchedCoroutine.afterResume` →
 * `safeIsDispatchNeeded` → `TestMainDispatcher.isDispatchNeeded`）。所以「先 cancel 再 `resetMain()`」
 * 只保证续体不被执行、**不保证不再访问 Main**，而 `resetMain()` 之后 Main 处于 absent 态、访问即抛，
 * 异常又落在真实线程上 ⇒ 记给下一个用例。口径因此改为**只装不卸**：Main 由每个用例的 `@Before`
 * 各自 `setMain(新实例)` 覆盖，永不卸载。
 *
 * 四条判据（断言前一律**剥离注释**，否则本文件与被守卫文件的 KDoc 引用会自造违例）：
 * 1. 守卫本体之外，`app/src/test` 不得出现 `Dispatchers.resetMain()`——一次 reset 就让整个 JVM 重新
 *    回到「absent 可抛」态，路线①即失效；
 * 2. 凡构造 ViewModel 的用例必须登记到 `MainDispatcherGuard.track(`（否则其在途作用域无人取消）；
 * 3. 凡登记到守卫的用例必须自行 `Dispatchers.setMain(`（安装与收尾两处必须同时存在）。
 *    > **已知残余（如实登记，不得当作已解决）**：路线①换掉的是「忘装 Main 会立刻抛错」这一检测。
 *    > 本批**不**强制「凡构造 ViewModel 者必须装 Main」——现不装 Main 的仓库面确实不触达 `Main`，
 *    > 而为它们装 `StandardTestDispatcher` 会一并改掉 `Main.immediate` 的**就地执行**语义（属行为改动）。
 *    > 残余后果：忘装 Main 时迟到回跳会落进**上一个**用例的调度器且无人推进 ⇒ 该用例多半因「工作没跑」
 *    > 而自身变红（仍会暴露，只是不再是干净的报错）。已作为残余登记 `ISSUE-P3-189`。
 * 4. 守卫内「取消作用域」必须早于任何 Main 生命周期操作，且不得出现 `try {` / `runCatching`
 *    （条目明令禁止的两种「掩盖污染而非修复」形态）。
 */
class MainDispatcherPollutionGuardTest {

    @Test
    fun `全测试源集不得在守卫之外卸载 Main 派发器`() {
        val offenders = testSources().filter { file ->
            // 守卫与本扫描器自身不属被管对象（本类的判据文本必然引用该字面量）
            relative(file) != GUARD && relative(file) != SELF && stripped(file).contains(RESET_CALL)
        }
        failIfNotEmpty(
            "以下用例调用了 " + RESET_CALL + "。Main 采「只装不卸」口径（ISSUE-P3-189 路线①）：" +
                "任何一次卸载都会让同 JVM 重新回到「访问即抛」态，迟到回跳即污染后续用例：\n",
            offenders
        )
    }

    @Test
    fun `构造 ViewModel 的用例必须把实例登记到守卫`() {
        val offenders = testSources().filter { file ->
            val source = stripped(file)
            source.contains("Dispatchers.setMain(") &&
                VIEWMODEL_CONSTRUCTION.containsMatchIn(source) &&
                !source.contains("MainDispatcherGuard.track(")
        }
        failIfNotEmpty(
            "以下用例构造了 ViewModel 却未登记到 MainDispatcherGuard.track()——`runTest` 结束不取消 " +
                "viewModelScope，其真实线程上的在途工作会活到别的用例里：\n",
            offenders
        )
    }

    @Test
    fun `登记到守卫的用例必须自行安装 Main 派发器`() {
        val offenders = testSources().filter { file ->
            val source = stripped(file)
            relative(file) != GUARD &&
                (source.contains("MainDispatcherGuard.track(") || source.contains("MainDispatcherGuard.trackScope(")) &&
                !source.contains("Dispatchers.setMain(")
        }
        failIfNotEmpty(
            "以下用例登记了作用域取消却未自行 Dispatchers.setMain()（「只装不卸」口径下装与卸必须同处守卫一侧发生）：",
            offenders
        )
    }

    @Test
    fun `守卫必须先取消作用域且不得吞掉收尾异常`() {
        val source = readSource(GUARD)
        val cancelAt = source.indexOf(".cancel()")
        assertTrue("守卫须包含取消作用域这一步（定位失败即签名已变）", cancelAt >= 0)
        assertTrue(
            "守卫不得再卸载 Main（路线①）",
            !source.lines()
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .any { it.contains(RESET_CALL) }
        )
        assertTrue(
            "守卫不得以 try/catch 或 runCatching 吞掉收尾异常（那是掩盖污染而非修复）",
            !source.contains("runCatching") && !source.contains("try {")
        )
    }

    private fun failIfNotEmpty(message: String, offenders: List<File>) {
        if (offenders.isNotEmpty()) {
            fail(message + offenders.joinToString("\n") { relative(it) })
        }
    }

    /** 剥离 `/* … */` 块注释与整行 `//` 注释（§116 的教训：KDoc 里引用旧写法不得被判成违例） */
    private fun stripped(file: File): String = stripCommentsOnly(readSource(file))
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

    private fun readSource(pathOrFile: Any): String {
        val file = if (pathOrFile is File) pathOrFile else File(repositoryRoot, pathOrFile as String)
        assertTrue("源文件不存在（是否被重命名/移动）：$pathOrFile", file.isFile)
        return file.readText()
    }

    private companion object {
        const val TEST_SOURCE_ROOT = "app/src/test/java"
        const val GUARD = "app/src/test/java/com/keepasskey/app/testutil/MainDispatcherGuard.kt"
        const val SELF = "app/src/test/java/com/keepasskey/app/quality/MainDispatcherPollutionGuardTest.kt"

        /** 拼接而成：本类自身源码若含完整字面量，扫描会自造违例（规则 1 的误报源） */
        val RESET_CALL = "Dispatchers." + "resetMain()"

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
