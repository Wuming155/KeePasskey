package com.keepasskey.app.ui.screens.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-360 AC④ + AC⑤ 接线守卫（静态源码比对，同 `OneTapInteractionWiringTest` 先例）。
 *
 * 宿主 JVM 无法渲染菜单 / 骨架 / Snackbar 引导，故锁「接线不被静默改回」：
 * 1. 溢出菜单有「选择」项并接回调；Screen 以空 id 进入批量（不预选）；
 * 2. 首次长按引导经全局通道发布且经持久化标记消费；
 * 3. 行 / 分组组件的高亮接的是**已生效过滤词**（`uiState.searchQuery`，防抖后）；
 * 4. 批量空 id 不污染选中集（ActionController 分支）；
 * 5. AC⑤：列表骨架调用点 + 空态排除加载窗；详情页骨架分支保留读屏语义。
 * 6. 新文案 values + values-en 双写。
 */
class VaultBatchDiscoverabilityWiringTest {

    private val topBars by lazy { readSource(TOP_BARS) }
    private val screen by lazy { readSource(SCREEN) }
    private val controller by lazy { readSource(ACTION_CONTROLLER) }

    @Test
    fun `溢出菜单含选择项且接入批量入口`() {
        assertTrue(
            "溢出菜单必须渲染「选择」DropdownMenuItem（AC④a 第二入口）",
            topBars.contains("R.string.vault_menu_select_entries")
        )
        assertTrue(
            "「选择」项必须走 onSelectEntriesClick 回调",
            topBars.contains("onSelectEntriesClick()")
        )
        assertTrue(
            "Screen 必须以空 id 进入批量（不预选任何条目）",
            screen.contains("viewModel.startBatchMode(\"\")")
        )
    }

    @Test
    fun `首次长按引导经全局通道且标记被消费`() {
        assertTrue(
            "长按进入批量必须消费一次性引导标记",
            screen.contains("batchGuide.consumeFirstGuide()")
        )
        assertTrue(
            "引导必须经全局 Snackbar 通道发布（不新建屏内宿主）",
            readSource(BATCH_GUIDE).contains("AppSnackbarChannel.trySend")
        )
        assertTrue(
            "引导标记必须持久化（SharedPreferences 置位）",
            readSource(BATCH_GUIDE).contains("putBoolean(KEY_GUIDED, true)")
        )
    }

    @Test
    fun `高亮接已生效过滤词而非输入回显`() {
        assertTrue(
            "条目行高亮必须取 uiState.searchQuery（防抖后的过滤词）",
            screen.contains("highlightQuery = uiState.searchQuery")
        )
        assertEquals(
            "条目行与分组行都必须接高亮词",
            2,
            Regex("highlightQuery = uiState\\.searchQuery").findAll(screen).count()
        )
    }

    @Test
    fun `批量空 id 分支不污染选中集`() {
        val startBatch = functionBody(controller, "fun startBatchMode(")
        assertTrue(
            "空 id 必须映射为空选中集（Menu 入口不预选）",
            startBatch.contains("if (initialEntryId.isEmpty()) emptySet()")
        )
    }

    @Test
    fun `首载骨架与空态排除接线`() {
        assertTrue(
            "库列表加载窗必须渲染骨架而非空态",
            screen.contains("vaultLoadingSkeletonItems()")
        )
        assertTrue(
            "空态判定必须排除首载加载窗（防空态闪现）",
            screen.contains("!uiState.isLoading && uiState.currentGroups.isEmpty()")
        )
        val detail = readSource(DETAIL_SCREEN)
        assertTrue(
            "详情页加载分支必须渲染骨架（保留读屏 loading 语义）",
            detail.contains("EntryDetailLoadingSkeleton(")
        )
        assertTrue(
            "详情页骨架必须保留 contentDescription 读屏语义",
            detail.contains("contentDescription = loadingLabel")
        )
    }

    @Test
    fun `新文案 values 与 values-en 双写`() {
        listOf(
            "vault_menu_select_entries",
            "vault_batch_select_guide",
            "detail_totp_copied_expiring",
            "clipboard_clear_suffix_seconds",
            "clipboard_clear_suffix_minutes"
        ).forEach { name ->
            listOf("app/src/main/res/values/strings.xml" to "zh", "app/src/main/res/values-en/strings.xml" to "en")
                .forEach { (path, lang) ->
                    assertTrue(
                        "$name 必须在 $lang 语料双写（$path）",
                        readSource(path).contains("name=\"$name\"")
                    )
                }
        }
    }

    // ---------------- helpers ----------------

    /** 声明行之后的花括号配平函数体（同 `OneTapInteractionWiringTest` 口径）。 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "未找到函数签名：$signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        var end = open
        while (end < source.length) {
            when (source[end]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, end + 1)
                }
            }
            end++
        }
        return source.substring(open)
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val TOP_BARS = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListTopBars.kt"
        const val SCREEN = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListScreen.kt"
        const val ACTION_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListActionController.kt"
        const val BATCH_GUIDE = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListBatchGuide.kt"
        const val DETAIL_SCREEN = "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailScreen.kt"

        const val ROOT_SEARCH_DEPTH = 6

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
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 $ROOT_SEARCH_DEPTH 层）")
        }
    }
}
