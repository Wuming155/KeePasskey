package com.keepasskey.app.testutil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.setMain

/**
 * 测试调度器跨用例污染防护（`ISSUE-P3-189`，根因与三条候选路线见批次 §152 / §153）。
 *
 * **两件事，缺一不可**：
 * 1. **取消**（[track] / [trackScope] + [tearDown]）：`runTest` 结束**不**取消 `viewModelScope`，
 *    其真实线程（`Dispatchers.Default` / `IO`）上的在途工作必须显式终止，否则会在**别的用例**里回写状态；
 * 2. **不卸载 Main**（路线①）：本守卫**刻意不**调用 `Dispatchers.resetMain()`。
 *
 * 为什么不 reset（§152 第 4 轮实测反证）：`withContext(Dispatchers.Default)` 的块**正常跑完**后，
 * 回送结果给父协程时要对父作用域的 `Dispatchers.Main` 问一次 `isDispatchNeeded`
 * （栈：`DispatchedCoroutine.afterResume` → `safeIsDispatchNeeded` → `TestMainDispatcher.isDispatchNeeded`）。
 * 即「先 cancel 再 resetMain」**只保证续体不被执行，不保证不再访问 Main**——取消本身反而制造一次回跳访问，
 * 而 `resetMain()` 之后的 Main 处于「absent」态，任何访问即抛
 * （`IllegalStateException: Dispatchers.Main was accessed when the platform dispatcher was absent`），
 * 该异常落在真实线程上 ⇒ 被协程测试记给**下一个**用例（表现为无关用例偶发红）。
 *
 * 改为「只装不卸」后：Main 永不进入 absent 态，故回跳访问不再抛错；每个用例的 `@Before` 仍各自
 * `setMain(新实例)`，迟到的回跳落入**当时那个**用例自己的调度器，其父作用域已取消 ⇒ 续体被丢弃、不执行。
 *
 * **代价与补偿**（须知，不得当作免费午餐）：一旦某个用例**忘装** Main，将静默继承上一个用例的派发器
 * 而不再立刻抛错。故 `MainDispatcherPollutionGuardTest` 同批钉死：
 * ① 守卫之外不得出现 `Dispatchers.resetMain()`；② 凡构造 ViewModel 的用例必须自行 `Dispatchers.setMain(`；
 * ③ [tearDown] 内「取消」必须发生在任何 Main 生命周期操作之前。
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
     * 用例收尾：取消全部已登记作用域。**不调用** `Dispatchers.resetMain()`（见类 KDoc 路线①）。
     *
     * [nextDispatcher] 非空时顺带为**下一个**用例装好派发器——本仓各用例的 `@Before` 已各自 `setMain`，
     * 形参保留是为无需 `@Before` 的用例提供同一条路径。
     */
    fun tearDown(nextDispatcher: TestDispatcher? = null) {
        val pending = tracked.toList()
        val pendingScopes = trackedScopes.toList()
        tracked.clear()
        trackedScopes.clear()
        pending.forEach { it.viewModelScope.cancel() }
        pendingScopes.forEach { it.cancel() }
        nextDispatcher?.let { Dispatchers.setMain(it) }
    }
}
