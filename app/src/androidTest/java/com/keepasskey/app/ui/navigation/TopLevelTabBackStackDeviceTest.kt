package com.keepasskey.app.ui.navigation

import android.content.Context
import androidx.navigation.NavController
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.ui.components.BottomNavItem
import com.keepasskey.app.ui.navigateToTopLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P2-260` AC③ 不变式：**连续切顶层 Tab 不得增长返回栈，且切回时 Tab 状态必须恢复**。
 *
 * ## 为什么必须落在设备侧
 *
 * 该不变式的判据全在 `NavController` 的运行时行为里（`popUpTo` 是否命中栈上目标、
 * `saveState` / `restoreState` 是否成对生效、返回栈是否只剩一个顶层 entry）——宿主 JVM
 * 无法构造 `NavController`（`navigation-runtime` 是 Android 库），故按条目 AC③ 的
 * 「宿主纯逻辑或真机 instrumented 均可」取后者。
 *
 * ## 载体选择：为什么不用 `NavHost` + Compose 测试规则
 *
 * 判据与 `ComposeNavigator` 的**渲染**无关（`popUpTo` / `saveState` / `restoreState` 全部实现于
 * navigator 无关的 `NavControllerImpl`），但路由解析必须有 `ComposeNavigator` 参与
 * （`composable()` 建目的地要经它）。故本用例手工装配 `NavHostController` +
 * `ComposeNavigator` + 与生产**同名**的路由图，**不**引入 `ui-test-junit4` 依赖、
 * 也不起 Composition —— 判据更聚焦，且新增依赖面为零。
 *
 * ## 与产线的接线关系（不得读作「已覆盖生产接线」）
 *
 * 生产侧由宿主静态守卫锁定「`NavHost` 四向 + 预测性返回接线」「顶层路由的 `topLevel*` 覆盖」；
 * 本用例锁定**同一份** `navigateToTopLevel` 函数在真实 `NavController` 上的行为契约
 * （函数体经 `internal` 直接复用，非复制实现）。
 *
 * ## 为什么整条用例在**主线程**跑（`onMain`）
 *
 * `NavController.setGraph` / `navigate` 会驱动 `NavBackStackEntry` 的 `LifecycleRegistry`
 * 注册观察者，而 `LifecycleRegistry.addObserver` **强制主线程**——首轮直接在测试线程调用时，
 * 真机读数为 `IllegalStateException: Method addObserver must be called on the main thread`。
 * 故每条用例体经 `Instrumentation.runOnMainSync` 在主线程执行，与生产（Composition 在主线程）同线程模型。
 * **不用** `androidx.test.annotation.UiThreadTest`：本模块 androidTest 类路径上没有该注解
 * （实测 `Unresolved reference`，且仓内 `androidx.test:*` 各构件里均无 `annotation/UiThreadTest.class`），
 * 为它新增依赖不划算。
 */
@RunWith(AndroidJUnit4::class)
class TopLevelTabBackStackDeviceTest {

    @Test
    fun 连续切顶层Tab必须替换而非压栈且Tab状态必须恢复() = onMain {
        val navController = newTopLevelController()
        // 模拟解锁成功：`Unlock` 被 inclusive 弹出（与 `KeePasskeyNavGraph` 的解锁路径一致）
        navController.navigate(FIRST_TAB) { popUpTo(UNLOCK_ROUTE) { inclusive = true } }
        assertEquals("解锁态下栈上只应有一个顶层 entry", 1, navController.topLevelEntryCount())

        // 连续切满两轮（每个 Tab 各访问两次），返回栈不得增长
        repeat(2) {
            BottomNavItem.routes.forEach { route ->
                navController.navigateToTopLevel(route, navController.currentTopLevelRoute())
                assertEquals(
                    "切到 $route 后返回栈的顶层 entry 数必须恒为 1（popUpTo 命中栈上目标）",
                    1,
                    navController.topLevelEntryCount()
                )
                assertEquals("切到 $route 后当前路由必须是它", route, navController.currentTopLevelRoute())
            }
        }

        // `saveState` / `restoreState` 成对生效的最强判据：恢复出来的 entry 沿用**同一 id**，
        // 即该 Tab 的 `NavBackStackEntry`（含其 `ViewModelStore` / savedState）被原样保留，
        // 而非每次切换新建一个（原缺陷下每次切换都是新 entry ⇒ id 必然不同）。
        navController.navigateToTopLevel(BottomNavItem.GENERATOR.route, navController.currentTopLevelRoute())
        val generatorEntryIdWhileCurrent =
            navController.currentBackStackEntryIdOf(BottomNavItem.GENERATOR.route)
        assertNotNull("密码生成器 Tab 切过去后必须在栈上", generatorEntryIdWhileCurrent)
        navController.navigateToTopLevel(BottomNavItem.VAULT.route, BottomNavItem.GENERATOR.route)
        assertNull(
            "切走后密码生成器 Tab 必须已离开返回栈（状态已被 saveState 收走）",
            navController.currentBackStackEntryIdOf(BottomNavItem.GENERATOR.route)
        )
        navController.navigateToTopLevel(BottomNavItem.GENERATOR.route, BottomNavItem.VAULT.route)
        assertEquals(
            "切走再切回后，密码生成器 Tab 的 NavBackStackEntry id 必须不变（saveState / restoreState 生效）",
            generatorEntryIdWhileCurrent,
            navController.currentBackStackEntryIdOf(BottomNavItem.GENERATOR.route)
        )

        // 返回键熔断语义：顶层只剩一个非图目的地时，`NavController` 不应再接管系统返回键
        // （`destinationCountOnBackStack > 1` 才会启用，见 NavController 的
        // `updateOnBackPressedCallbackEnabled`）——本用例以同一口径反证返回栈无界增长已消除。
        assertEquals(
            "顶层路由下非图目的地数必须为 1，返回键才交由系统 / 外壳层 BackHandler 熔断",
            1,
            navController.topLevelEntryCount()
        )
    }

    @Test
    fun 弹出目标必须命中栈上路由否则整个切换都不生效() = onMain {
        // 反向反校的前提：以「不在返回栈上」的目标 popUpTo 时，NavController **静默忽略**，
        // 返回栈不缩短 —— 这正是 `ISSUE-P2-260` 的原缺陷形态。
        val navController = newTopLevelController()
        navController.navigate(FIRST_TAB) { popUpTo(UNLOCK_ROUTE) { inclusive = true } }
        val before = navController.topLevelEntryCount()

        // 以已被弹出栈外的 `Unlock` 为目标（原实现用的是它的 id，等价）
        navController.navigate(BottomNavItem.AUTHENTICATOR.route) {
            popUpTo(UNLOCK_ROUTE) {
                inclusive = true
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
        assertEquals(
            "以栈外目标 popUpTo 时不得发生任何 pop（旧实现形态：返回栈只增不减）",
            before + 1,
            navController.topLevelEntryCount()
        )

        // 同一栈态下改用「栈上当前路由」为目标 ⇒ 弹出真实发生：被弹的 Authenticator 离开返回栈，
        // 弹 1 压 1 故总数不变（**不**回到 1——修复只保证此后不再增长，不会追溯清理已存在的重复项）。
        navController.navigateToTopLevel(BottomNavItem.GENERATOR.route, BottomNavItem.AUTHENTICATOR.route)
        assertEquals(
            "以栈上当前路由为目标 popUpTo 时必须是「弹 1 压 1」，总数不变",
            before + 1,
            navController.topLevelEntryCount()
        )
        assertEquals(
            "当前路由必须已是目标 Tab",
            BottomNavItem.GENERATOR.route,
            navController.currentTopLevelRoute()
        )
        assertFalse(
            "被弹出的 Authenticator 不得再留在返回栈上（popUpTo 真实命中）",
            navController.backStackRoutes().contains(BottomNavItem.AUTHENTICATOR.route)
        )
    }

    /**
     * 在**主线程**执行 [block]，并把其中的失败原样抛回测试线程。
     *
     * 显式捕获再重抛：`AssertionError` 是 `Error` 而非 `RuntimeException`，
     * 依赖 `runOnMainSync` 的默认传播路径会把断言失败的**类型与消息**一并改形，
     * 翻车时读不出判据原文。
     */
    private fun onMain(block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
    }

    private fun newTopLevelController(): NavHostController {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val navController = NavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }
        navController.graph = navController.createGraph(startDestination = UNLOCK_ROUTE) {
            composable(UNLOCK_ROUTE) {}
            BottomNavItem.routes.forEach { route -> composable(route) {} }
        }
        return navController
    }

    /** 返回栈上的**顶层** entry 数（剔除图本身，与 `NavController.destinationCountOnBackStack` 同口径）。 */
    private fun NavController.topLevelEntryCount(): Int =
        currentBackStack.value.count { it.destination !is NavGraph }

    /** 当前栈顶的顶层路由；栈顶不是顶层路由时返回 null。 */
    private fun NavController.currentTopLevelRoute(): String? =
        currentBackStack.value
            .mapNotNull { it.destination.route }
            .lastOrNull { it in BottomNavItem.routes }

    private fun NavController.currentBackStackEntryIdOf(route: String): String? =
        currentBackStack.value.lastOrNull { it.destination.route == route }?.id

    private fun NavController.backStackRoutes(): List<String> =
        currentBackStack.value.mapNotNull { it.destination.route }

    companion object {
        private const val UNLOCK_ROUTE = "unlock"
        private val FIRST_TAB = BottomNavItem.VAULT.route
    }
}
