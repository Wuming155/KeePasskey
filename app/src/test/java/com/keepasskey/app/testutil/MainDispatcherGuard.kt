package com.keepasskey.app.testutil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain

/**
 * 测试调度器跨用例污染防护（`ISSUE-P3-189`；根因同归档批次 §18 / §150）。
 *
 * 机理：`runTest` 结束**不**取消 `viewModelScope`。ViewModel 内在真实线程
 * （`Dispatchers.Default` / `Dispatchers.IO`）上执行的在途工作，若在 `resetMain()` **之后**
 * 才回跳 Main，会打到 `android.jar` 的 `Looper` 桩并抛 `IllegalStateException`，被协程测试
 * 记为「用例开始前已有未捕获异常」⇒ 污染同一 JVM 内的后续用例（表现位置随执行顺序漂移）。
 *
 * 用法：用例创建 ViewModel 处调用 [track]（在构造表达式上直接包裹，不改变语义），
 * `@After` 调用 [tearDown]。**顺序不可颠倒**，且**不得**以「给 `resetMain()` 包 try/catch 吞异常」
 * 或「调大 `awaitOffMainComputation` 超时」处置——那是掩盖污染而非修复。
 */
@OptIn(ExperimentalCoroutinesApi::class)
object MainDispatcherGuard {

    private val tracked = mutableListOf<ViewModel>()
    private val trackedScopes = mutableListOf<CoroutineScope>()

    /** 登记本用例创建的 ViewModel，并原样返回该实例。 */
    fun <T : ViewModel> track(viewModel: T): T = viewModel.also { tracked += it }

    /** 登记本用例为被测对象自持的作用域（非 ViewModel 的同类泄漏面），并原样返回。 */
    fun trackScope(scope: CoroutineScope): CoroutineScope = scope.also { trackedScopes += it }

    /** 先取消各 ViewModel / 作用域、再恢复 `Dispatchers.Main`。 */
    fun tearDown() {
        val pending = tracked.toList()
        val pendingScopes = trackedScopes.toList()
        tracked.clear()
        trackedScopes.clear()
        pending.forEach { it.viewModelScope.cancel() }
        pendingScopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }
}
