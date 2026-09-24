package com.keepasskey.app.quality

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 测试调度器跨用例污染防护的**接线守卫**（`ISSUE-P3-189`，根因与路线见批次 §152 / §153；
 * 口径自 `ISSUE-P2-307` / §309 起由「只装不卸」修订为「**装新不卸**」）。
 *
 * 本条缺陷的形态是「**偶发红、位置随执行顺序漂移**」（§152 实测命中率 1/4）——跑几轮绿不构成修复证据，
 * 而漏防护的用例污染的是**别人**（表现为无关用例红）。故验收不靠多轮实跑，而靠静态扫描把口径锁成
 * 编译期可见的硬判据。本仓静态源码守卫先例：`AlgoHotPathGuardsTest`。
 *
 * **背景（路线① → §309 修订）**：`withContext(Dispatchers.Default)` 的块正常跑完后，
 * 回送结果给父协程时仍要访问父作用域的 `Dispatchers.Main`（`DispatchedCoroutine.afterResume` →
 * `safeIsDispatchNeeded` → `TestMainDispatcher.isDispatchNeeded`）。所以「先 cancel 再 `resetMain()`」
 * 只保证续体不被执行、**不保证不再访问 Main**，而 `resetMain()` 之后 Main 处于 absent 态、访问即抛，
 * 异常又落在真实线程上 ⇒ 记给下一个用例。口径因此改为**只装不卸**：Main 由每个用例的 `@Before`
 * 各自 `setMain(新实例)` 覆盖，永不卸载。
 *
 * **§309 修订（装新不卸）**：`ISSUE-P2-307` 定位出「只装不卸」的残余危害——`@Before` 装的
 * `StandardTestDispatcher` 会留给不装 Main 的后续类，依赖 `viewModelScope.launch` **实时执行**的用例
 * 落进无人推进的死调度器 ⇒ 实时等待超时偶发假红。守卫 [tearDown][com.keepasskey.app.testutil.MainDispatcherGuard.tearDown]
 * 故改为收尾**装新**（装新鲜 eager 默认 Main，仍绝不 `resetMain`）；运行期回归锁见
 * `MainDispatcherGuardNormalizationTest`。
 *
 * 六条判据（断言前一律**剥离注释**，否则本文件与被守卫文件的 KDoc 引用会自造违例）：
 * 1. 守卫本体之外，`app/src/test` 不得出现 `Dispatchers.resetMain()`——一次 reset 就让整个 JVM 重新
 *    回到「absent 可抛」态，路线①即失效；
 * 2. 凡构造 ViewModel 的用例必须登记到 `MainDispatcherGuard.track(`（否则其在途作用域无人取消）。
 *    §309 起**不再以「有 setMain」为前提**——§153 残余「不装 Main 的仓库面不触达 Main」已被 autofill
 *    两个测试类证伪（它们经 `viewModelScope` 触达 Main），前提不成立即闭合；
 * 3. 凡登记到守卫的用例必须自行 `Dispatchers.setMain(`（安装与收尾两处必须同时存在）；
 * 4. 守卫内「取消作用域」必须早于任何 Main 生命周期操作，且不得出现 `try {` / `runCatching`
 *    （条目明令禁止的两种「掩盖污染而非修复」形态）；
 * 5. 守卫收尾必须**装新**（`tearDown` 内存在 `Dispatchers.setMain(` 且位于取消之后）——「只装不卸」
 *    原样会把死调度器留给后续类（`ISSUE-P2-307` 根因）；
 * 6. 凡引用 `Dispatchers.Main` 的用例必须自行 `Dispatchers.setMain(`——这是「装新」修复后唯一剩余的
 *    可污染面（不装 Main 而直接触达 Main 的类只能继承收尾默认态；该态已是 eager 初始等价态，但
 *    依赖它属于隐式耦合，静态点名强制显式化）。
 */
class MainDispatcherPollutionGuardTest {

    @Test
    fun `全测试源集不得在守卫之外卸载 Main 派发器`() {
        val offenders = testSources().filter { file ->
            // 守卫与本扫描器自身不属被管对象（本类的判据文本必然引用该字面量）
            relative(file) != GUARD && relative(file) != SELF && stripped(file).contains(RESET_CALL)
        }
        failIfNotEmpty(
            "以下用例调用了 " + RESET_CALL + "。Main 采「装新不卸」口径（ISSUE-P3-189 路线①，" +
                "ISSUE-P2-307 修订）：任何一次 reset 都会让同 JVM 重新回到「访问即抛」态，" +
                "迟到回跳即污染后续用例：\n",
            offenders
        )
    }

    @Test
    fun `构造 ViewModel 的用例必须把实例登记到守卫`() {
        val offenders = testSources().filter { file ->
            val source = stripped(file)
            // §309 起不再要求「有 setMain」为前提：§153 残余前提（不装 Main 的面不触达 Main）
            // 已被 autofill 两个测试类证伪——它们经 viewModelScope 触达 Main 而从不 setMain
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
            "以下用例登记了作用域取消却未自行 Dispatchers.setMain()（「装新不卸」口径下装与卸必须同处守卫一侧发生）：",
            offenders
        )
    }

    @Test
    fun `守卫必须先取消作用域且不得吞掉收尾异常`() {
        // 断言前剥注释：守卫 KDoc 会引用判据字面量（try { / runCatching），原始扫描会自造违例
        val source = stripped(guardFile)
        val cancelAt = source.indexOf(".cancel()")
        assertTrue("守卫须包含取消作用域这一步（定位失败即签名已变）", cancelAt >= 0)
        assertTrue(
            "守卫不得再卸载 Main（路线①）",
            !source.contains(RESET_CALL)
        )
        assertTrue(
            "守卫不得以 try/catch 或 runCatching 吞掉收尾异常（那是掩盖污染而非修复）",
            !source.contains("runCatching") && !source.contains("try {")
        )
    }

    @Test
    fun `守卫收尾必须装新且装新晚于取消`() {
        val source = stripped(guardFile)
        val setMainAt = source.indexOf("Dispatchers.setMain(")
        val cancelAt = source.indexOf(".cancel()")
        assertTrue(
            "守卫收尾必须为下一个用例装上新鲜默认 Main（ISSUE-P2-307：「只装不卸」原样会把 " +
                "StandardTestDispatcher 死调度器留给不装 Main 的后续类）——tearDown 内应有 Dispatchers.setMain(",
            setMainAt >= 0
        )
        assertTrue(
            "守卫的「装新」必须发生在「取消作用域」之后（先终止在途工作、再换装 Main；顺序即语义）",
            setMainAt > cancelAt
        )
    }

    @Test
    fun `引用 Main 的用例必须自行安装 Main 派发器`() {
        val offenders = testSources().filter { file ->
            val source = stripped(file)
            relative(file) != GUARD && relative(file) != SELF &&
                source.contains(MAIN_REFERENCE) &&
                !source.contains("Dispatchers.setMain(")
        }
        failIfNotEmpty(
            "以下用例引用了 Dispatchers.Main 却未自行 Dispatchers.setMain()——它将静默继承上一个用例" +
                "（或守卫收尾默认态）的派发器；显式安装是「装新不卸」口径下唯一被允许的 Main 获取方式：\n",
            offenders
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

        /** 守卫文件（判据四 / 五对它逐字符断言；经 [stripped] 剥注释后扫描） */
        private val guardFile: File by lazy { File(repositoryRoot, GUARD) }

        /** 拼接而成：本类自身源码若含完整字面量，扫描会自造违例（规则 1 的误报源） */
        val RESET_CALL = "Dispatchers." + "resetMain()"

        /**
         * 拼接而成（同 [RESET_CALL] 的自造违例防御）：「引用 Main」的判据字面量。
         * `Dispatchers.setMain(` 不含该子串（setMain 的 set 不匹配），无交叉误报。
         */
        val MAIN_REFERENCE = "Dispatchers." + "Main"

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
