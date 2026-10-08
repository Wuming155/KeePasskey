package com.keepasskey.app.ui.screens.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P2-544` 接线守卫（静态源码比对，沿用 `VaultBatchDiscoverabilityWiringTest` 先例）。
 *
 * 立规缘由（2026-10-08 真机实测取证）：高级搜索入口原是顶栏**独立图标钮**，其展开面挂在
 * 列表 `LazyColumn` 的 item 上 ⇒ 用户在**已滚动**的列表里点它时，面板被插到视口**上方**，
 * 状态确实翻转、界面却零变化（真机读数：点击后视口内文本零变化，滚回顶部才见面板），
 * 表现为「点了没反应」。整改＝入口收进溢出菜单、展开面改为对话框（不受滚动位置影响）。
 *
 * 本用例锁四件事，均为「被后人改回即复发同一缺陷」的形态：
 * 1. 入口在溢出菜单内，且与「排序」「扫码」同槽（用户明确要求的收敛形态）；
 * 2. 顶栏**不再**有独立的 `Tune` 图标钮（`onToggleAdvanced` 形参随之下线）；
 * 3. 点击打开的是对话框，且对话框由 [VaultListDialogHost] 渲染（不在列表 item 内）；
 * 4. 列表装配层不再挂载页内面板；`VaultSearchAdvancedPanel.kt` 整文件不得回流。
 *
 * 宿主 JVM 无法渲染菜单 / 对话框，故采用源码比对（同 `OneTapInteractionWiringTest`）；
 * 「点击后界面必然可见」这一条只有对话框形态本身能保证（模态窗口与列表滚动位置无关）。
 */
class VaultAdvancedSearchEntryWiringTest {

    private val topBars by lazy { readSource(TOP_BARS) }
    private val screen by lazy {
        readSource(SCREEN) + "\n" + readSource(CONTENT)
    }
    private val dialogHost by lazy { readSource(DIALOG_HOST) }
    private val dialog by lazy { readSource(DIALOG) }

    @Test
    fun `高级搜索入口位于溢出菜单且与排序扫码同槽`() {
        val menuBlocks = Regex("DropdownMenu\\(").findAll(topBars).count()
        assertTrue("顶栏必须仍有溢出菜单（防空扫）：实际 DropdownMenu 调用 $menuBlocks 处", menuBlocks >= 1)

        // 三个入口同处一个菜单块：排序 / 扫码 / 高级搜索
        listOf(
            "R.string.cd_sort",
            "R.string.vault_scan_menu",
            "R.string.search_advanced_title"
        ).forEach { key ->
            assertTrue("溢出菜单必须含入口 $key", topBars.contains(key))
        }
        assertTrue(
            "「高级搜索」项必须走 onAdvancedSearchClick 回调",
            topBars.contains("onAdvancedSearchClick()")
        )
        assertTrue(
            "「高级搜索」项必须是 DropdownMenuItem（不再是与菜单并列的独立图标钮）",
            Regex("DropdownMenuItem\\([^)]*?search_advanced_title", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(topBars)
        )
    }

    @Test
    fun `顶栏不再有独立的高级搜索图标钮`() {
        assertFalse(
            "顶栏不得再出现 onToggleAdvanced 形参（展开态改由对话框自身承载）",
            topBars.contains("onToggleAdvanced")
        )
        assertFalse(
            "顶栏不得再出现 advancedActive 形参（独立图标钮的展开态高亮随之下线）",
            topBars.contains("advancedActive")
        )
        assertEquals(
            "顶栏只应在溢出菜单项里用到 Tune 图标（独立 IconButton(onClick = …Tune…) 已删除）",
            1,
            Regex("Icons.Default.Tune").findAll(topBars).count()
        )
    }

    @Test
    fun `点击入口打开由对话框宿主渲染的选项对话框`() {
        assertTrue(
            "列表装配层必须把入口回调接到对话框开关",
            screen.contains("onAdvancedSearchClick = { dialogs.showSearchAdvancedDialog = true }")
        )
        assertTrue(
            "对话框开关必须定义在 VaultListDialogController",
            dialogHost.contains("var showSearchAdvancedDialog by mutableStateOf(false)")
        )
        assertTrue(
            "对话框必须由 VaultListDialogHost 渲染（模态窗口 → 不受列表滚动位置影响）",
            dialogHost.contains("VaultSearchAdvancedDialog(")
        )
        assertTrue(
            "对话框必须挂 AlertDialog 容器（与排序 / 扫码同族的模态形态）",
            dialog.contains("AlertDialog(")
        )
        assertTrue(
            "对话框必须接收当前选项快照（uiState.searchAdvanced）",
            dialogHost.contains("options = uiState.searchAdvanced")
        )
    }

    @Test
    fun `页内面板形态不得回流`() {
        assertFalse(
            "列表装配层不得再挂页内面板（其被插进已滚动视口上方正是本条目根因）",
            screen.contains("VaultSearchAdvancedPanel(")
        )
        assertFalse(
            "展开态不得再回落到列表 item 键（面板不再参与列表装配）",
            screen.contains("SEARCH_ADVANCED_PANEL_KEY")
        )
        assertFalse(
            "VaultSearchAdvancedPanel.kt 不得回流（其职责已由 VaultSearchAdvancedDialog.kt 承接）",
            File(repositoryRoot, PANEL).isFile
        )
    }

    @Test
    fun `菜单项文案双语双写`() {
        listOf("app/src/main/res/values/strings.xml" to "zh", "app/src/main/res/values-en/strings.xml" to "en")
            .forEach { (path, lang) ->
                assertTrue(
                    "search_advanced_title 必须在 $lang 语料双写（$path）",
                    readSource(path).contains("name=\"search_advanced_title\"")
                )
            }
    }

    // ---------------- helpers ----------------

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val TOP_BARS = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListTopBars.kt"
        const val SCREEN = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListScreen.kt"
        const val CONTENT = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListContent.kt"
        const val DIALOG_HOST = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListDialogHost.kt"
        const val DIALOG = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultSearchAdvancedDialog.kt"
        const val PANEL = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultSearchAdvancedPanel.kt"

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
