package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.breach.BreachCheckCoordinator
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.app.testutil.MainDispatcherGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P2-58（审计 RUST-03）AC④ 回归：**整库健康扫描不得占用主线程派发器**。
 *
 * 背景：`getKdbxEntries()`（全库投影）与 `HealthCheckEngine.analyzeEntries()`（对**每条口令**
 * 跑一遍模式扫描）都是纯 CPU 工作；原实现在 `viewModelScope`（Main 派发器）上裸 `launch`
 * 直接执行，恶意大库 / 超长口令可把主线程卡住直至扫描结束（配合原生侧 O(n²) 路径则更甚）。
 *
 * 本用例以**真实运行现场**断言（非源码字符串断言）：让 `Dispatchers.Main` 指向测试派发器，
 * 记录仓库真被调用的线程名，要求它落在 `DefaultDispatcher-worker-*` 上——若有人把
 * `withContext(Dispatchers.Default)` 摘掉，断言立即失败。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthScanOffMainThreadTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        // 先取消本用例登记的作用域、再恢复 Main（ISSUE-P3-189，见 MainDispatcherGuard）。
        MainDispatcherGuard.tearDown()
    }

    @Test
    fun `整库扫描在 Default 派发器执行而非 Main 派发器`() = runBlocking {
        val mainDispatcherThread = Thread.currentThread().name
        var fetchThread: String? = null

        val repo = object : VaultRepository by FakeVaultRepository() {
            override suspend fun getKdbxEntries(): List<KdbxEntry> {
                fetchThread = Thread.currentThread().name
                return emptyList()
            }
        }

        val controller = SettingsHealthController(
            vaultRepository = repo,
            breachCheckCoordinator = BreachCheckCoordinator(NoOpBreachRangeClient),
            strings = StringsProvider { _, _ -> "" },
            breachCheckEnabled = { false }, // 关闭态：零外联，聚焦 CPU 归属断言
            scope = MainDispatcherGuard.trackScope(CoroutineScope(Dispatchers.Main))
        )

        controller.rescanHealth()
        // 主派发器为 StandardTestDispatcher（虚拟时间）：需显式推进；`withContext(Default)`
        // 的真线程工作不在虚拟时间调度器内，故用轮询等待其完成与续体回写。
        var waited = 0L
        while (fetchThread == null && waited < WAIT_TIMEOUT_MS) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        testDispatcher.scheduler.advanceUntilIdle()

        val observed = fetchThread
        assertNotNull("测试前提：仓库必须已被调用", observed)
        assertTrue(
            "整库投影不得在 Main 派发器线程（$mainDispatcherThread）执行，实际=$observed",
            observed != mainDispatcherThread
        )
        assertTrue(
            "应落在 Default 派发器工作线程，实际=$observed",
            observed!!.startsWith("DefaultDispatcher-worker")
        )
        assertTrue("扫描完成后应回写 hasScanned", controller.state.value.hasScanned)
        assertTrue("扫描完成后应复位扫描中标志", !controller.state.value.isHealthScanning)
    }

    private companion object {
        const val WAIT_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 10L
    }
}
