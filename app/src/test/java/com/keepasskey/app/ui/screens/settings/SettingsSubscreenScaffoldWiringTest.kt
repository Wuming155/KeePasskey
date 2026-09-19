package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 二级设置页共用骨架的**配色接线守卫**（`ISSUE-P3-195` 的直接产物）。
 *
 * 起因：§188 把十页各写的 `Scaffold + TopAppBar` 收敛为 `SettingsSubscreenScaffold` 时，
 * 前提「九页顶栏配色逐字相同」是**目测**登记的——实际其中一页（`PrivilegedBrowserSettingsScreen`）
 * 用的是 `containerColor = background` 且**不传** `titleContentColor`，于是被顺手拉平成
 * `surface` / `onSurface`，构成一处真实的呈现回归（浅色下近乎不可辨、深色下可辨）。
 * §189 为此新增了分桶指纹工具并查出该回归，但**指纹是一次性核对**，挡不住未来有人再改参数默认值
 * 或在本页改回传值 ⇒ 本用例把「十页各自实际拿到什么配色」变成可执行的静态断言。
 *
 * 判据形态与本仓其余接线守卫一致：读源码文本比对，不依赖运行时（Compose 呈现无法在 JVM 断言）。
 * 刻意**只断言接线存在与差异保留**，不断言具体色值的美学优劣。
 */
class SettingsSubscreenScaffoldWiringTest {

    @Test
    fun `骨架必须把顶栏配色以参数暴露，默认值复刻多数页`() {
        val source = readSource(SCAFFOLD)
        assertTrue(
            "骨架不得把顶栏 containerColor 写死——十页里存在真实差异（见 ISSUE-P3-195）",
            source.contains("topBarContainerColor: Color = MaterialTheme.colorScheme.surface")
        )
        assertTrue(
            "titleContentColor 必须以**可空**参数暴露：null 表示「不传该参数」，" +
                "换成 Color.Unspecified 会变成显式写入未定色、与原页推导链不等价",
            source.contains("topBarTitleContentColor: Color? = MaterialTheme.colorScheme.onSurface")
        )
        assertTrue(
            "null 分支必须整体省略 titleContentColor（走 TopAppBar 自身推导）",
            source.contains("TopAppBarDefaults.topAppBarColors(containerColor = topBarContainerColor)")
        )
    }

    /**
     * 少数页的例外**必须显式存在**：它一旦被删（或被拉平回默认值），就是 §188 那处回归复活。
     * 这条断言同时钉住「为什么不同」的注释锚点，防止有人当无用注释清掉。
     */
    @Test
    fun `例外页必须显式复原 background 与不传标题色`() {
        val source = readSource("$SUBSCREEN_DIR/PrivilegedBrowserSettingsScreen.kt")
        assertTrue(
            "例外页须显式传 topBarContainerColor = background（ISSUE-P3-195 复原，不得回落到 surface）",
            source.contains("topBarContainerColor = MaterialTheme.colorScheme.background")
        )
        assertTrue(
            "例外页须显式传 topBarTitleContentColor = null（原实现根本不传该参数）",
            source.contains("topBarTitleContentColor = null")
        )
        assertTrue(
            "复原处必须留下「为何不同」的说明，避免被当作冗余参数删除",
            source.contains("ISSUE-P3-195")
        )
    }

    /**
     * 多数页**不得**各自再写一份顶栏配色：骨架的默认值就是它们的现状，页面里出现这两个具名实参
     * 即意味着「差异又长回来了」——正是本目要消除的形态。
     */
    @Test
    fun `多数页不得重新各写顶栏配色参数`() {
        val offenders = MAJORITY_PAGES.mapNotNull { name ->
            val source = readSource("$SUBSCREEN_DIR/$name.kt")
            val writes = TOP_BAR_ARGS.filter { source.contains(it) }
            if (writes.isEmpty()) null else "$name 重新写了 ${writes.joinToString("、")}"
        }
        assertTrue("这些页应沿用骨架默认配色：$offenders", offenders.isEmpty())
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("清单文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SUBSCREEN_DIR =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens"

        const val SCAFFOLD = "$SUBSCREEN_DIR/SettingsSubscreenScaffold.kt"

        val TOP_BAR_ARGS = listOf("topBarContainerColor =", "topBarTitleContentColor =")

        /** 沿用默认配色的其余九页（例外页不在其中，由第二个用例单独钉） */
        val MAJORITY_PAGES = listOf(
            "AboutSettingsScreen", "ThemeSettingsScreen", "AutofillSettingsScreen", "TotpSettingsScreen",
            "DebugSettingsScreen", "CloudSyncScreen", "SecuritySettingsScreen",
            "HealthCheckScreen", "DatabaseSettingsScreen"
        )

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
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
    }

    /** 反向哨兵：例外页绝不该出现在「多数页」清单里，否则第三个用例会自我满足地空扫。 */
    @Test
    fun `多数页清单不得把例外页也算进去`() {
        assertFalse(
            "例外页混进多数页清单会让上一条判据静默失效",
            MAJORITY_PAGES.contains("PrivilegedBrowserSettingsScreen")
        )
        assertFalse("骨架自身也不得算作页面", MAJORITY_PAGES.contains("SettingsSubscreenScaffold"))
    }
}
