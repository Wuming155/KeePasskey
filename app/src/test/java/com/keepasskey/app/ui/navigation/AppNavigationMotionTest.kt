package com.keepasskey.app.ui.navigation

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 全局导航与退出动效接线守卫（ISSUE-P3-222）。
 *
 * 锁定 Material 3 共享轴（Shared Axis X）下钻转场、顶层 Tab 贯穿淡入淡出（Fade Through）、
 * 以及 Android 14/15/16 预测性返回（Predictive Back）接线不变式。
 */
class AppNavigationMotionTest {

    @Test
    fun `动效规范时长与缓动常量必须符合Material3规范`() {
        assertEquals(300, AppNavigationMotion.ENTER_DURATION_MS)
        assertEquals(250, AppNavigationMotion.EXIT_DURATION_MS)
        assertEquals(220, AppNavigationMotion.FADE_THROUGH_ENTER_MS)
        assertEquals(150, AppNavigationMotion.FADE_THROUGH_EXIT_MS)

        assertNotNull(AppNavigationMotion.defaultEnterTransition)
        assertNotNull(AppNavigationMotion.defaultExitTransition)
        assertNotNull(AppNavigationMotion.defaultPopEnterTransition)
        assertNotNull(AppNavigationMotion.defaultPopExitTransition)
        assertNotNull(AppNavigationMotion.topLevelEnterTransition)
        assertNotNull(AppNavigationMotion.topLevelExitTransition)
    }

    @Test
    fun `AndroidManifest必须显式开启预测性返回`() {
        val manifest = readSource(MANIFEST_PATH)
        assertTrue(
            "[$MANIFEST_PATH] 未声明 android:enableOnBackInvokedCallback=\"true\"，" +
                "系统级 Back-to-home 桌面缩放预测性动画将无法生效",
            manifest.contains("android:enableOnBackInvokedCallback=\"true\"")
        )
    }

    @Test
    fun `KeePasskeyApp全局NavHost必须配置四向动效`() {
        val source = stripCommentsOnly(readSource(APP_SOURCE))
        assertTrue(
            "[$APP_SOURCE] NavHost 必须配置 defaultEnterTransition",
            source.contains("enterTransition = AppNavigationMotion.defaultEnterTransition")
        )
        assertTrue(
            "[$APP_SOURCE] NavHost 必须配置 defaultExitTransition",
            source.contains("exitTransition = AppNavigationMotion.defaultExitTransition")
        )
        assertTrue(
            "[$APP_SOURCE] NavHost 必须配置 defaultPopEnterTransition",
            source.contains("popEnterTransition = AppNavigationMotion.defaultPopEnterTransition")
        )
        assertTrue(
            "[$APP_SOURCE] NavHost 必须配置 defaultPopExitTransition",
            source.contains("popExitTransition = AppNavigationMotion.defaultPopExitTransition")
        )
    }

    @Test
    fun `顶层Tab与解锁页必须配置FadeThrough转场`() {
        val source = stripCommentsOnly(readSource(NAV_GRAPH_SOURCE))
        assertTrue(
            "[$NAV_GRAPH_SOURCE] Unlock 必须配置 topLevelEnterTransition",
            source.contains("route = Screen.Unlock.route") &&
                source.contains("enterTransition = AppNavigationMotion.topLevelEnterTransition")
        )
        assertTrue(
            "[$NAV_GRAPH_SOURCE] VaultList 必须配置 topLevelEnterTransition 与 defaultPopEnterTransition",
            source.contains("route = Screen.VaultList.route") &&
                source.contains("popEnterTransition = AppNavigationMotion.defaultPopEnterTransition")
        )
        assertTrue(
            "[$NAV_GRAPH_SOURCE] Authenticator 必须配置 topLevelEnterTransition",
            source.contains("route = Screen.Authenticator.route") &&
                source.contains("enterTransition = AppNavigationMotion.topLevelEnterTransition")
        )
        assertTrue(
            "[$NAV_GRAPH_SOURCE] Generator 必须配置 topLevelEnterTransition",
            source.contains("route = Screen.Generator.route") &&
                source.contains("enterTransition = AppNavigationMotion.topLevelEnterTransition")
        )
    }

    companion object {
        private const val MANIFEST_PATH = "app/src/main/AndroidManifest.xml"
        private const val APP_SOURCE = "app/src/main/java/com/keepasskey/app/ui/KeePasskeyApp.kt"
        private const val NAV_GRAPH_SOURCE = "app/src/main/java/com/keepasskey/app/ui/KeePasskeyNavGraph.kt"

        private val repositoryRoot: File by lazy {
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

        private fun readSource(relativePath: String): String =
            File(repositoryRoot, relativePath).readText(Charsets.UTF_8)
    }
}
