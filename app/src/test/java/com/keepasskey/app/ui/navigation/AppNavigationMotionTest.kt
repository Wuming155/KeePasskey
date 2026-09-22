package com.keepasskey.app.ui.navigation

import androidx.compose.animation.core.CubicBezierEasing
import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 全局导航与退出动效接线守卫（`ISSUE-P3-222` 立规，`ISSUE-P3-261` 按定标口径重写）。
 *
 * 锁定四类转场的不变式：共享轴 X（下钻 / 返回镜像）、顶层 Tab 贯穿淡入淡出（Fade Through）、
 * 预测性返回全屏表面、内容层（`MotionScheme` spec + 列表项动效），以及
 * `ISSUE-P2-260` 的「顶层 Tab 切换必须真正替换返回栈」。
 *
 * **用例取名口径（`ISSUE-P3-261` AC⑨）**：原名「动效规范时长与缓动常量必须符合 Material3 规范」
 * 与取值不符（当时既非 M3 token 时长、也非 M3 缓动），现按**实际基准栏**取名——
 * 空间段对齐 `MotionScheme`（AndroidX 实现）、效果段对齐设计规范的顺序淡化时间轴。
 */
class AppNavigationMotionTest {

    @Test
    fun `效果段时长必须构成官方顺序淡化时间轴且阈值处两屏皆不可见`() {
        assertEquals(300, AppNavigationMotion.SHARED_AXIS_SLIDE_MS)
        assertEquals(90, AppNavigationMotion.FADE_OUT_MS)
        assertEquals(210, AppNavigationMotion.FADE_IN_MS)
        assertEquals(90, AppNavigationMotion.FADE_IN_DELAY_MS)
        assertEquals(35, AppNavigationMotion.FADE_THROUGH_THRESHOLD_PERCENT)

        // 不变式①：滑动结束 = 淡化结束（入向淡化收尾恰落在滑动终点）
        assertEquals(
            AppNavigationMotion.SHARED_AXIS_SLIDE_MS,
            AppNavigationMotion.FADE_IN_DELAY_MS + AppNavigationMotion.FADE_IN_MS
        )
        // 不变式②（官方原文「At the 35% mark, neither screen is showing」的**出向**半边）：
        // 出向淡化必须在阈值进度前结束，否则阈值处旧屏仍半透明 ⇒ 两屏叠加的亮度凹陷。
        val thresholdMs = AppNavigationMotion.SHARED_AXIS_SLIDE_MS *
            AppNavigationMotion.FADE_THROUGH_THRESHOLD_PERCENT / 100
        assertTrue(
            "出向淡化 ${AppNavigationMotion.FADE_OUT_MS}ms 必须 ≤ 阈值进度 ${thresholdMs}ms",
            AppNavigationMotion.FADE_OUT_MS <= thresholdMs
        )
        // 不变式③（同句的**入向**半边）：入向不早于出向结束开始 —— 两段顺序、不重叠。
        assertTrue(
            "入向淡化延迟 ${AppNavigationMotion.FADE_IN_DELAY_MS}ms 必须 ≥ 出向淡化 " +
                "${AppNavigationMotion.FADE_OUT_MS}ms",
            AppNavigationMotion.FADE_IN_DELAY_MS >= AppNavigationMotion.FADE_OUT_MS
        )
    }

    @Test
    fun `缩放起点与插值器必须取官方规范值且原无出处取值已删除`() {
        // fade through 由小涨上来（AndroidX MaterialFadeThrough.DEFAULT_START_SCALE）
        assertEquals(0.92f, AppNavigationMotion.FADE_THROUGH_START_SCALE)
        // 全屏表面 Exit Scale 100% → 90% / Enter Scale 110% → 100%
        assertEquals(0.9f, AppNavigationMotion.PREDICTIVE_EXIT_SCALE)
        assertEquals(1.1f, AppNavigationMotion.PREDICTIVE_ENTER_SCALE)
        // 共享轴视差比例为具名 token（前进 / 返回共用）
        assertEquals(4, AppNavigationMotion.PARALLAX_DIVISOR)

        // 缓动曲线以**字面量**重建后逐点比对：产线若改参数，此处取值即偏离（不比字符串、只看曲线）。
        assertEasingEquals("出向淡化缓动", 0.4f, 0f, 1f, 1f, AppNavigationMotion.FADE_OUT_EASING)
        assertEasingEquals("入向淡化缓动", 0f, 0f, 0.2f, 1f, AppNavigationMotion.FADE_IN_EASING)
        assertEasingEquals(
            "预测性返回插值器（官方 (.1, .1, 0, 1)）",
            0.1f, 0.1f, 0f, 1f,
            AppNavigationMotion.SYSTEM_UI_EASING
        )

        // 场景：原 `0.96f` 无任何官方出处 —— 断言它不再是产线取值。
        val motion = stripCommentsOnly(readSource(MOTION_SOURCE))
        assertFalse(
            "[$MOTION_SOURCE] 0.96f 无官方出处（既非 fade through 的 0.92f、也非全屏表面的 1.1f）",
            motion.contains("0.96f")
        )
    }

    @Test
    fun `转场实例必须提供四向加预测性返回两槽位共九条`() {
        val motion = AppNavigationMotion.from(androidx.compose.material3.MotionScheme.expressive())
        assertNotNull(motion.defaultEnterTransition)
        assertNotNull(motion.defaultExitTransition)
        assertNotNull(motion.defaultPopEnterTransition)
        assertNotNull(motion.defaultPopExitTransition)
        assertNotNull(motion.predictivePopEnterTransition)
        assertNotNull(motion.predictivePopExitTransition)
        assertNotNull(motion.topLevelEnterTransition)
        assertNotNull(motion.topLevelExitTransition)
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
    fun `KeePasskeyApp全局NavHost必须配置四向动效与预测性返回专属槽位`() {
        val source = stripCommentsOnly(readSource(APP_SOURCE))
        assertTrue(
            "[$APP_SOURCE] NavHost 必须配置 defaultEnterTransition",
            source.contains("enterTransition = motion.defaultEnterTransition")
        )
        assertTrue(
            "[$APP_SOURCE] NavHost 必须配置 defaultExitTransition",
            source.contains("exitTransition = motion.defaultExitTransition")
        )
        assertTrue(
            "[$APP_SOURCE] NavHost 必须配置 defaultPopEnterTransition",
            source.contains("popEnterTransition = motion.defaultPopEnterTransition")
        )
        assertTrue(
            "[$APP_SOURCE] NavHost 必须配置 defaultPopExitTransition",
            source.contains("popExitTransition = motion.defaultPopExitTransition")
        )
        // ISSUE-P3-261 AC④：手势驱动段必须落专属槽位，不得复用 pop 四向参数
        assertTrue(
            "[$APP_SOURCE] NavHost 必须配置 predictivePopEnterTransition",
            source.contains("predictivePopEnterTransition = motion.predictivePopEnterTransition")
        )
        assertTrue(
            "[$APP_SOURCE] NavHost 必须配置 predictivePopExitTransition",
            source.contains("predictivePopExitTransition = motion.predictivePopExitTransition")
        )
        // 转场参数必须来自主题 MotionScheme 构造的实例（而非绕过主题自造第二套语言）
        assertTrue(
            "[$APP_SOURCE] 转场实例必须由 AppNavigationMotion.from(motionScheme) 构造",
            source.contains("AppNavigationMotion.from(motionScheme)")
        )
    }

    @Test
    fun `顶层Tab与解锁页必须配置FadeThrough转场且覆盖设置页`() {
        val source = stripCommentsOnly(readSource(NAV_GRAPH_SOURCE))
        listOf(
            "Screen.Unlock.route",
            "Screen.VaultList.route",
            "Screen.Authenticator.route",
            "Screen.Generator.route",
            // ISSUE-P3-261 AC② / 偏差表第 8 项：设置页同为底栏顶层 Tab，此前缺席覆盖
            "Screen.Settings.route"
        ).forEach { route ->
            val routeIndex = source.indexOf("route = $route")
            assertTrue("[$NAV_GRAPH_SOURCE] 未找到顶层路由 $route", routeIndex >= 0)
            // 该路由声明块内必须出现 topLevel 进入 / 退出转场
            val declaration = source.substring(routeIndex, declarationEnd(source, routeIndex))
            assertTrue(
                "[$NAV_GRAPH_SOURCE] $route 必须配置 topLevelEnterTransition",
                declaration.contains("enterTransition = motion.topLevelEnterTransition")
            )
            assertTrue(
                "[$NAV_GRAPH_SOURCE] $route 必须配置 topLevelExitTransition",
                declaration.contains("exitTransition = motion.topLevelExitTransition")
            )
        }
        // ISSUE-P2-260 AC④：顶层路由的 popEnter 例外配置保留（唯一命中路径是「下钻页返回」）
        val vaultIndex = source.indexOf("route = Screen.VaultList.route")
        val vaultDeclaration = source.substring(vaultIndex, declarationEnd(source, vaultIndex))
        assertTrue(
            "[$NAV_GRAPH_SOURCE] VaultList 的 popEnterTransition 必须为共享轴还原（下钻返回语义）",
            vaultDeclaration.contains("popEnterTransition = motion.defaultPopEnterTransition")
        )
    }

    @Test
    fun `顶层Tab切换的popUpTo目标必须取自返回栈上的当前路由`() {
        val source = stripCommentsOnly(readSource(APP_SOURCE))
        val body = functionBody(source, "fun NavHostController.navigateToTopLevel(")
        // ISSUE-P2-260：起点目的地 `Unlock` 在解锁后即被弹出栈外，以它为 popUpTo 目标会被
        // NavController 静默忽略（`Ignoring popBackStack … as it was not found on the current back stack`）
        // ⇒ 每次切 Tab 都新压一个 entry。目标必须取**栈上真实存在**的当前路由。
        assertTrue(
            "[$APP_SOURCE] navigateToTopLevel 必须 popUpTo 当前路由（栈上目标）：\n$body",
            body.contains("popUpTo(stackRoute)")
        )
        assertTrue(
            "[$APP_SOURCE] navigateToTopLevel 必须以 inclusive + saveState 弹出并恢复：\n$body",
            body.contains("inclusive = true") && body.contains("saveState = true")
        )
        assertTrue(
            "[$APP_SOURCE] navigateToTopLevel 必须 restoreState：\n$body",
            body.contains("restoreState = true")
        )
        assertFalse(
            "[$APP_SOURCE] navigateToTopLevel 不得再用不在返回栈上的 findStartDestination 作为 popUpTo 目标：\n$body",
            body.contains("findStartDestination")
        )
    }

    @Test
    fun `内容层动效必须读主题MotionScheme且列表项必须启用animateItem`() {
        CONTENT_MOTION_SOURCES.forEach { path ->
            val source = stripCommentsOnly(readSource(path))
            assertTrue(
                "[$path] 内容层动效必须取 MaterialTheme.motionScheme 的 spec（消除同屏两套动效语言）",
                source.contains("MaterialTheme.motionScheme")
            )
            assertFalse(
                "[$path] 内容层动效不得再自定裸 tween 时长",
                source.contains("tween(")
            )
        }
        LIST_SOURCES.forEach { path ->
            val source = stripCommentsOnly(readSource(path))
            assertTrue(
                "[$path] 列表项必须启用 Modifier.animateItem（增删 / 重排不再瞬移）",
                source.contains("animateItem(")
            )
        }
    }

    @Test
    fun `全仓app主源集不得再出现裸tween时长`() {
        val offenders = File(repositoryRoot, MAIN_SOURCE_DIR)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.relativePathContains() !in TWEEN_EXEMPT_FILES }
            .filter { stripCommentsOnly(it.readText(Charsets.UTF_8)).contains("tween(") }
            .map { it.relativePathContains() }
            .sorted()
            .toList()
        assertTrue(
            "以下文件仍含裸 `tween(`——动效时长必须收敛到 AppNavigationMotion 的具名 token" +
                "（顺序淡化时间轴）或 MaterialTheme.motionScheme 的 spec：$offenders",
            offenders.isEmpty()
        )
    }

    private fun File.relativePathContains(): String =
        relativeTo(repositoryRoot).invariantSeparatorsPath

    private fun assertEasingEquals(
        label: String,
        a: Float,
        b: Float,
        c: Float,
        d: Float,
        actual: androidx.compose.animation.core.Easing
    ) {
        val expected = CubicBezierEasing(a, b, c, d)
        listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f).forEach { fraction ->
            assertEquals(
                "$label 在 fraction=$fraction 处的取值与 cubic-bezier($a, $b, $c, $d) 不符",
                expected.transform(fraction),
                actual.transform(fraction),
                1e-4f
            )
        }
    }

    /** 取 `signature` 起第一个配平花括号的完整函数体（须传入已剥注释的源码）。 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("未找到函数 $signature", start >= 0)
        val bodyStart = source.indexOf('{', start)
        assertTrue("未找到函数 $signature 的函数体", bodyStart >= 0)
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, i + 1)
                }
            }
        }
        throw AssertionError("函数体未闭合：$signature")
    }

    /** 自 `routeIndex` 处的 `route = …` 起，取到该次 `composable(` 调用闭合的右括号。 */
    private fun declarationEnd(source: String, routeIndex: Int): Int {
        val open = source.indexOf('(', routeIndex)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        return source.length
    }

    companion object {
        private const val MANIFEST_PATH = "app/src/main/AndroidManifest.xml"
        private const val APP_SOURCE = "app/src/main/java/com/keepasskey/app/ui/KeePasskeyApp.kt"
        private const val NAV_GRAPH_SOURCE = "app/src/main/java/com/keepasskey/app/ui/KeePasskeyNavGraph.kt"
        private const val MOTION_SOURCE = "app/src/main/java/com/keepasskey/app/ui/navigation/AppNavigationMotion.kt"
        private const val MAIN_SOURCE_DIR = "app/src/main"

        /** 内容层动效站点（`ISSUE-P3-261` 偏差表第 13 项列出的 5 处 + 生成器旋转角）。 */
        private val CONTENT_MOTION_SOURCES = listOf(
            "app/src/main/java/com/keepasskey/app/ui/components/ThemeToggleCapsule.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListComponents.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditFormSections.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/ThemeSettingsSections.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailCardSections.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorDisplayCard.kt"
        )

        /** 内容层列表站点（`ISSUE-P3-261` AC⑧）。 */
        private val LIST_SOURCES = listOf(
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListScreen.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/authenticator/AuthenticatorScreen.kt"
        )

        /**
         * 允许出现裸 `tween(` 的豁免清单。
         *
         * **唯一豁免**：`AppNavigationMotion` 的顺序淡化时间轴——出向 90ms / 入向 210ms + 90ms 延迟
         * 是官方规范的**确定时长**，且「阈值处两屏皆不可见」必须由时长表达，不能用 spring 替代。
         * 新增豁免须在批次文档登记理由（不得为让守卫变绿而加名单）。
         */
        private val TWEEN_EXEMPT_FILES = setOf(
            "app/src/main/java/com/keepasskey/app/ui/navigation/AppNavigationMotion.kt"
        )

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
