package com.keepasskey.app.testutil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain

/**
 * 测试调度器跨用例污染防护（`ISSUE-P3-189`，根因与三条候选路线见批次 §152 / §153；
 * 口径自 `ISSUE-P2-307` / §309 起由「只装不卸」修订为「**装新不卸**」）。
 *
 * **三件事，缺一不可**：
 * 1. **取消**（[track] / [trackScope] + [tearDown]）：`runTest` 结束**不**取消 `viewModelScope`，
 *    其真实线程（`Dispatchers.Default` / `IO`）上的在途工作必须显式终止，否则会在**别的用例**里回写状态；
 * 2. **永不 absent**：本守卫**绝不**调用 `Dispatchers.resetMain()`（§152 第 4 轮实测反证，见下）；
 * 3. **装新**（[tearDown] 收尾）：取消之后为下一个用例装上**新鲜默认** Main（[UnconfinedTestDispatcher]，
 *    eager 执行，等价于新 JVM 的 ServiceLoader 初始态），不把本用例 `@Before` 装的调度器留给后续类。
 *
 * 为什么不 reset（§152 第 4 轮实测反证）：`withContext(Dispatchers.Default)` 的块**正常跑完**后，
 * 回送结果给父协程时要对父作用域的 `Dispatchers.Main` 问一次 `isDispatchNeeded`
 * （栈：`DispatchedCoroutine.afterResume` → `safeIsDispatchNeeded` → `TestMainDispatcher.isDispatchNeeded`）。
 * 即「先 cancel 再 resetMain」**只保证续体不被执行，不保证不再访问 Main**——取消本身反而制造一次回跳访问，
 * 而 `resetMain()` 之后的 Main 处于「absent」态，任何访问即抛
 * （`IllegalStateException: Dispatchers.Main was accessed when the platform dispatcher was absent`），
 * 该异常落在真实线程上 ⇒ 被协程测试记给**下一个**用例（表现为无关用例偶发红）。
 *
 * 为什么光「只装」不够（`ISSUE-P2-307` / §309 定位的根因）：路线①原样只装不卸，本用例 `@Before` 装的
 * `StandardTestDispatcher`（依赖显式推进其 scheduler 才会执行排队协程）会**留给不装 Main 的后续类**。
 * 凡依赖 `viewModelScope.launch` **实时执行**的用例（`AutofillPickerViewModel` 两个测试类的实时等待模式），
 * 其 launch 落进无人推进的死调度器 ⇒ 5 s 实时等待超时 ⇒「缓存应命中却未命中」三连偶发红
 * （是否命中取决于 Gradle 的类执行顺序，隔离跑必绿——与实测现象完全吻合）。收尾装新后：忘装 Main 的
 * 后续类继承的是 eager 初始等价态，行为与新 JVM 首跑完全一致，泄漏被结构性消除。
 *
 * **代价与补偿**（须知，不得当作免费午餐）：一旦某个用例**忘装** Main，将静默继承收尾装上的
 * eager 默认态而不再立刻抛错（与 `ISSUE-P3-189` 时代相同的检测缺口，但后果从「继承死调度器」
 * 降级为「继承与初始一致的正常态」）。`MainDispatcherPollutionGuardTest` 钉死：
 * ① 守卫之外不得出现 `Dispatchers.resetMain()`；② 凡构造 ViewModel 的用例必须登记
 * `MainDispatcherGuard.track(`（§309 起不再以「有 setMain」为前提，关闭 §153 残余）；
 * ③ 凡登记到守卫的用例必须自行 `Dispatchers.setMain(`；④ [tearDown] 内「取消」必须发生在任何
 * Main 生命周期操作之前，且不得出现 `try {` / `runCatching`（两种「掩盖污染而非修复」形态明令禁止）；
 * ⑤ 收尾必须装新（本文件 [tearDown] 即其实现，运行期回归锁见 `MainDispatcherGuardNormalizationTest`）；
 * ⑥ 凡引用 `Dispatchers.Main` 的用例必须自行 `Dispatchers.setMain(`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
object MainDispatcherGuard {

    private val tracked = mutableListOf<ViewModel>()
    private val trackedScopes = mutableListOf<CoroutineScope>()

    /** 登记本用例创建的 ViewModel，并原样返回该实例。 */
    fun <T : ViewModel> track(viewModel: T): T = viewModel.also { tracked += it }

    /** 登记本用例为被测对象自持的作用域（非 ViewModel 的同类泄漏面），并原样返回。 */
    fun trackScope(scope: CoroutineScope): CoroutineScope = scope.also { trackedScopes += it }

    /**
     * 用例收尾：取消全部已登记作用域，随后**装新**——为下一个用例装上新鲜默认 Main
     * （eager，等价于新 JVM 初始态；`ISSUE-P2-307` / §309 起生效）。**不调用**
     * `Dispatchers.resetMain()`（永不 absent，见类 KDoc）。
     *
     * [nextDispatcher] 非空时改装**该**派发器——供确需以特定调度器衔接下一个用例的场景显式指定；
     * 缺省一律装 [UnconfinedTestDispatcher] 新实例（每次新建、自持调度器，无人推进也只会 eager 执行）。
     */
    fun tearDown(nextDispatcher: TestDispatcher? = null) {
        val pending = tracked.toList()
        val pendingScopes = trackedScopes.toList()
        tracked.clear()
        trackedScopes.clear()
        pending.forEach { it.viewModelScope.cancel() }
        pendingScopes.forEach { it.cancel() }
        // 取消先行；装新在后（顺序由 MainDispatcherPollutionGuardTest 钉死）。
        Dispatchers.setMain(nextDispatcher ?: UnconfinedTestDispatcher())
    }
}
