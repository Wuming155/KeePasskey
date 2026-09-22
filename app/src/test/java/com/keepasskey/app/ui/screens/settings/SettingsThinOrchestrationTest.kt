package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.data.breach.BreachCheckCoordinator
import com.keepasskey.app.data.breach.BreachRangeClient
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `ISSUE-P3-257`（§261 批次）：`SettingsViewModel` 4 处薄编排下沉后的行为与接线验收。
 *
 * 覆盖 4 处落点：
 * 1. [SettingsSyncController.verifyConnectionThenSync] 三分支——忙态零动作 / 已验证直接同步 /
 *    未验证且测试失败时上浮 `sync_gate_test_failed` 且不同步；
 * 2/3. [SettingsPrivilegedBrowserController]——store 缺失恒空（不谎报）+ 刷新 / 启停接线源码判据；
 * 4. [SettingsHealthController.enableBreachCheckAndScan]——**先持久化后扫描**的顺序
 *    （Unconfined 急跑近似生产 `Main.immediate` 语义），且扫描读到已持久化的新值。
 *
 * 另含 ViewModel 侧 4 处**单语句委托**的静态判据（`PD-23` 收窄授权：不得新增含实现体的成员）。
 * 与 `OneTapInteractionWiringTest` 的分工：该类守卫「一次点击」批次的历史契约，
 * 本类守卫本批下沉后的落点与行为。
 *
 * **调度器口径**：同步三支的协程挂在 [TestScope.backgroundScope] 上（测试结束自动取消）。
 * 推进一律用 [pumpUntil]（`runCurrent` 轮询），**不得**改用 `advanceUntilIdle` /
 * 既有 `awaitOffMainComputation`——后者的停止条件是 `events.none(isForeground)`（只推进
 * 前台事件），而 backgroundScope 任务携带 `BackgroundWork` 标记（后台事件）会被整体跳过
 * （本批实测：同一链路 `runCurrent` 推得动、`advanceUntilIdle` 推不动）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsThinOrchestrationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        // 同步三支用 runTest 的 backgroundScope（自动取消），健康顺序用例在 finally 内自 cancel；
        // 此处按「只装不卸」口径收尾 Main。
        MainDispatcherGuard.tearDown()
    }

    // =============================== 1 · verifyConnectionThenSync 三分支 ===============================

    @Test
    fun `忙态时顺序编排零动作不追加连接测试`() = runTest(testDispatcher) {
        val provider = RecordingSyncProvider().apply { holdTestConnection = true }
        val controller = buildSyncController(backgroundScope, provider)

        // 建立忙态：门闩挂住第一次连接测试，isSyncing 保持 true
        controller.testSyncConnection()
        pumpUntil { provider.testCalls == 1 }
        assertTrue("测试前提：连接测试进行中（忙态）", controller.state.value.isSyncing)

        val feedbackBefore = controller.state.value.syncFeedbackMessage
        controller.verifyConnectionThenSync()

        // 忙态早退是同步路径：不得产生任何新反馈 / 新协程动作
        assertEquals("忙态下不得上浮新反馈", feedbackBefore, controller.state.value.syncFeedbackMessage)
        assertEquals("忙态下不得追加连接测试", 1, provider.testCalls)

        // 推进 + 真实时间缓冲后复核（若忙态守卫与下游守卫同时失守，此处必须红）
        testScheduler.runCurrent()
        Thread.sleep(50)
        testScheduler.runCurrent()
        assertEquals("忙态下不得追加连接测试（推进后复核）", 1, provider.testCalls)
        assertEquals("忙态下不得上浮新反馈（推进后复核）", feedbackBefore, controller.state.value.syncFeedbackMessage)
        assertTrue("忙态标志必须保持", controller.state.value.isSyncing)

        provider.releaseTestGate()
        pumpUntil { !controller.state.value.isSyncing }
        assertFalse("门闩放行后第一次测试应正常收尾", controller.state.value.isSyncing)
    }

    @Test
    fun `已验证连接直接同步且不再重复测试`() = runTest(testDispatcher) {
        val provider = RecordingSyncProvider()
        val controller = buildSyncController(backgroundScope, provider)

        // 先经真实测试连接把 isConnectionVerified 置位
        controller.testSyncConnection()
        pumpUntil { controller.state.value.isConnectionVerified }
        assertTrue(controller.state.value.isConnectionVerified)
        provider.testCalls = 0

        controller.verifyConnectionThenSync()
        testScheduler.runCurrent()

        assertEquals(
            "已验证时必须跳过重复测试（守卫 ②）",
            0,
            provider.testCalls
        )
        // 未打开库 ⇒ 同步周期必然以 Error 反馈收尾——该反馈本身即「同步已被触发」的证据
        // （若直接同步被移除，反馈会停留在上一轮的 sync_feedback_done，断言必红）。
        assertEquals(
            "已验证时必须直接触发同步",
            R.string.sync_feedback_error,
            controller.state.value.syncFeedbackMessage?.resId
        )
        assertFalse(controller.state.value.isSyncing)
        assertTrue("直接同步不得清掉已验证结论", controller.state.value.isConnectionVerified)
    }

    @Test
    fun `未验证且测试失败时上浮sync_gate_test_failed且不同步`() = runTest(testDispatcher) {
        val provider = RecordingSyncProvider().apply { reachable = false }
        val controller = buildSyncController(backgroundScope, provider)

        controller.verifyConnectionThenSync()
        pumpUntil {
            controller.state.value.syncFeedbackMessage?.resId == R.string.sync_gate_test_failed
        }

        val state = controller.state.value
        assertEquals(
            "测试未通过必须明确上浮「已跳过同步」（守卫 ③，绝不静默中止）",
            R.string.sync_gate_test_failed,
            state.syncFeedbackMessage?.resId
        )
        assertFalse("失败路径不得进入同步（isSyncing 须已复位）", state.isSyncing)
        assertFalse("失败路径不得置位已验证", state.isConnectionVerified)
        assertEquals("连接测试恰好执行一次", 1, provider.testCalls)
    }

    // =============================== 2/3 · 特权浏览器 store-null 恒空 ===============================

    @Test
    fun `store缺失时特权浏览器列表恒空且刷新与启停零动作`() = runTest(testDispatcher) {
        val controller = SettingsPrivilegedBrowserController(store = null, scope = backgroundScope)

        assertTrue("初值必须为空列表", controller.privilegedBrowsers.value.isEmpty())

        controller.refresh()
        controller.setEnabled("com.example.browser", true)
        testScheduler.runCurrent()
        Thread.sleep(30)
        testScheduler.runCurrent()

        assertTrue(
            "store 缺失时列表必须恒空（如实「未检测到」，不谎报）",
            controller.privilegedBrowsers.value.isEmpty()
        )
    }

    // =============================== 2/3 · 刷新接线 + 4 处单语句委托（源码判据） ===============================

    @Test
    fun `特权浏览器扫描启停接线与ViewModel四处单语句委托（源码判据）`() {
        val viewModel = readSource(SETTINGS_VIEW_MODEL)
        val privileged = readSource(PRIVILEGED_CONTROLLER)
        val health = readSource(HEALTH_CONTROLLER)
        val sync = readSource(SYNC_CONTROLLER)

        // PD-23 收窄授权：4 处必须逐字收为单语句委托（含属性 get 委托）
        assertTrue(
            "verifyConnectionThenSync 必须是单语句委托",
            viewModel.contains("fun verifyConnectionThenSync() = syncController.verifyConnectionThenSync()")
        )
        assertTrue(
            "refreshPrivilegedBrowsers 必须是单语句委托",
            viewModel.contains("fun refreshPrivilegedBrowsers() = privilegedBrowserController.refresh()")
        )
        assertTrue(
            "setPrivilegedBrowserEnabled 必须委托控制器",
            viewModel.contains("fun setPrivilegedBrowserEnabled(packageName: String, enabled: Boolean) =") &&
                viewModel.contains("privilegedBrowserController.setEnabled(packageName, enabled)")
        )
        assertTrue(
            "enableBreachCheckAndScan 必须是单语句委托",
            viewModel.contains("fun enableBreachCheckAndScan() = healthController.enableBreachCheckAndScan()")
        )
        assertTrue(
            "privilegedBrowsers 必须是 get 委托",
            viewModel.contains("get() = privilegedBrowserController.privilegedBrowsers")
        )
        assertTrue(
            "4 的持久化回调必须接线到 extendedPreferences（形态同 lambda 注入）",
            viewModel.contains("setBreachCheckEnabled = { extendedPreferences.setBreachCheckEnabled(it) }")
        )

        // 落点 1：实现体在同步控制器
        val verify = functionBody(sync, "fun verifyConnectionThenSync()")
        assertTrue("三分支守卫实现必须在同步控制器内", verify.contains("isConnectionVerified"))
        assertTrue("失败上浮文案必须随迁", verify.contains("sync_gate_test_failed"))

        // 落点 4：实现体在健康控制器且顺序不可倒置
        val enable = functionBody(health, "fun enableBreachCheckAndScan()")
        val persistAt = enable.indexOf("setBreachCheckEnabled(true)")
        assertTrue("必须先写偏好", persistAt >= 0)
        assertTrue("必须先写偏好再扫描", persistAt < enable.indexOf("rescanHealth()"))

        // 落点 2/3：控制器接线（store-null 早退 + IO 刷新 + 先启停后重扫）
        val refresh = functionBody(privileged, "fun refresh()")
        assertTrue("store 缺失必须早退不谎报", refresh.contains("store ?: return"))
        assertTrue("扫描必须在 IO 上执行", refresh.contains("Dispatchers.IO"))
        assertTrue(
            "刷新必须把 store 读数回写状态",
            refresh.contains("privilegedBrowsersState.value = store.installedBrowsers()")
        )

        val setEnabled = functionBody(privileged, "fun setEnabled(packageName: String, enabled: Boolean)")
        assertTrue("store 缺失必须早退不谎报", setEnabled.contains("store ?: return"))
        val toggleAt = setEnabled.indexOf("store.setEnabled(packageName, enabled)")
        assertTrue("必须先启停 store", toggleAt >= 0)
        assertTrue(
            "启停后必须重扫列表回写状态",
            toggleAt < setEnabled.indexOf("privilegedBrowsersState.value = store.installedBrowsers()")
        )
    }

    // =============================== 4 · 先持久化后扫描 ===============================

    @Test
    fun `开启泄露检测必须先持久化后扫描且扫描读到已持久化的新值`() {
        val gate = CompletableDeferred<Unit>()
        val persisted = AtomicBoolean(false)
        val persistEvents = CopyOnWriteArrayList<String>()
        val persistSawScanning = AtomicBoolean(false)
        val scanReadValues = CopyOnWriteArrayList<Boolean>()
        val rangeClient = CountingRangeClient()

        // 仓库在门闩上挂起：扫描一旦启动即保持 isHealthScanning=true，
        // 使「持久化发生时扫描是否已启动」成为可判定的顺序探针。
        // 返回**非空**条目：BreachCheckCoordinator 对空口令集合直接 CLEAN、不发起范围查询
        // （公开常量口令，非真实凭据）。
        val repo = object : VaultRepository by FakeVaultRepository() {
            override suspend fun getKdbxEntries(): List<KdbxEntry> {
                gate.await()
                return listOf(
                    KdbxEntry(
                        id = KdbxUuid(ByteArray(16) { 1 }),
                        fields = mapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("OrderProbe", isProtected = false),
                            KdbxConstants.Fields.PASSWORD to ProtectedString("password", isProtected = true)
                        )
                    )
                )
            }
        }

        lateinit var controller: SettingsHealthController
        // Unconfined 急跑 ≈ 生产 viewModelScope（Main.immediate）语义：
        // rescanHealth 的 launch 体会在调用点就地执行到首个真实挂起点。
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            controller = SettingsHealthController(
                vaultRepository = repo,
                breachCheckCoordinator = BreachCheckCoordinator(rangeClient),
                strings = StringsProvider { id, _ -> "s$id" },
                breachCheckEnabled = {
                    persisted.get().also { scanReadValues += it }
                },
                scope = scope,
                setBreachCheckEnabled = { enabled ->
                    persistEvents += "persist:$enabled"
                    if (controller.state.value.isHealthScanning) persistSawScanning.set(true)
                    persisted.set(enabled)
                }
            )

            controller.enableBreachCheckAndScan()

            assertEquals("必须恰好持久化一次「开启」", listOf("persist:true"), persistEvents.toList())
            assertFalse(
                "先持久化后扫描：持久化发生时扫描不得已启动（顺序倒置即红）",
                persistSawScanning.get()
            )
            assertTrue(
                "测试前提：Unconfined 下扫描协程应已急跑至挂起（扫描中）",
                controller.state.value.isHealthScanning
            )

            gate.complete(Unit)
            val deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS
            while (!controller.state.value.hasScanned && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertTrue("扫描必须完成（hasScanned 回写）", controller.state.value.hasScanned)
            assertFalse("完成后扫描中标志必须复位", controller.state.value.isHealthScanning)
            assertTrue(
                "扫描读取开关时必须已读到持久化的新值 true（安全边界 ③：无竞态）",
                scanReadValues.isNotEmpty() && scanReadValues.all { it }
            )
            assertEquals(
                "开启方向恰好发起一次泄露范围查询（安全边界 ①）",
                1,
                rangeClient.calls
            )
        } finally {
            scope.cancel()
        }
    }

    // =============================== helpers ===============================

    /**
     * 轮询推进调度器直至条件成立（真实时间兜底，语义同 `awaitOffMainComputation`）。
     *
     * **必须用 [TestCoroutineScheduler][kotlinx.coroutines.test.TestCoroutineScheduler].runCurrent**：
     * `advanceUntilIdle` 的停止条件为「队列中已无前台事件」，而 [TestScope.backgroundScope]
     * 的任务携带 `BackgroundWork` 标记（后台事件）——纯后台队列会被它整体跳过
     * （kotlinx-coroutines-test 1.11 源码：`advanceUntilIdleOr { events.none(TestDispatchEvent::isForeground) }`）。
     */
    private fun TestScope.pumpUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            testScheduler.runCurrent()
            Thread.sleep(5)
        }
        testScheduler.runCurrent()
    }

    /** 可门闩 / 可计数的假云 Provider：testConnection 计数、可挂起；同步周期在无打开库时不会触达它。 */
    private class RecordingSyncProvider : SyncProvider {
        @Volatile var reachable = true
        @Volatile var testCalls = 0
        @Volatile var holdTestConnection = false
        private val testGate = CompletableDeferred<Unit>()

        fun releaseTestGate() {
            testGate.complete(Unit)
        }

        override suspend fun testConnection(): Result<Unit> {
            testCalls++
            if (holdTestConnection) testGate.await()
            return if (reachable) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("unreachable"))
            }
        }

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> =
            Result.failure(IOException("not used"))

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> =
            Result.failure(IOException("not used"))

        override suspend fun upload(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> = Result.failure(IOException("not used"))

        override suspend fun delete(remotePath: String): Result<Unit> =
            Result.failure(IOException("not used"))
    }

    /** 可计数的假泄露范围查询客户端（无真实外联）。 */
    private class CountingRangeClient : BreachRangeClient {
        @Volatile var calls = 0

        override suspend fun queryRange(prefix: String): Set<String> {
            calls++
            return emptySet()
        }
    }

    private fun buildSyncController(
        scope: CoroutineScope,
        provider: SyncProvider
    ): SettingsSyncController {
        val context = fakeAndroidContext()
        val credentials = SyncCredentialsStore(context, null)
        val coordinator = SyncCoordinator(
            context,
            DatabaseSession(),
            credentials,
            DebugLogBuffer(),
            TEST_STRINGS
        )
        coordinator.testSyncProvider = provider
        return SettingsSyncController(
            credentials, coordinator, ExtendedSettingsStore(null), TEST_STRINGS, scope
        )
    }

    /** 宿主单测可用的最小 Context：仅覆盖链路上真实触达的两处（缓存目录 / 偏好读取）。 */
    private fun fakeAndroidContext(): android.content.Context {
        val cacheDir = java.nio.file.Files.createTempDirectory("thin_orchestration").toFile()
        return object : android.content.ContextWrapper(null) {
            override fun getCacheDir(): java.io.File = cacheDir

            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences =
                java.lang.reflect.Proxy.newProxyInstance(
                    android.content.SharedPreferences::class.java.classLoader,
                    arrayOf(android.content.SharedPreferences::class.java)
                ) { _, method, args ->
                    if (method.name == "getString") args?.getOrNull(1) else null
                } as android.content.SharedPreferences
        }
    }

    /** 摘出某个函数**声明行之后**的花括号配平函数体（沿用 `OneTapInteractionWiringTest` 同源手法）。 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "未找到函数签名：$signature" }
        val open = source.indexOf('{', start)
        if (open < 0) return source.substring(start)
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
        val file = java.io.File(repositoryRoot, path)
        assertTrue("扫描目标不存在（路径已漂移）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        /** TASK-21：单测注入按资源 ID 映射的假 StringsProvider（无 Android 资源环境） */
        private val TEST_STRINGS = StringsProvider { id, _ -> "s$id" }

        private const val SCAN_TIMEOUT_MS = 5_000L

        const val SETTINGS_VIEW_MODEL =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt"
        const val PRIVILEGED_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsPrivilegedBrowserController.kt"
        const val HEALTH_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsHealthController.kt"
        const val SYNC_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt"

        const val ROOT_SEARCH_DEPTH = 6

        val repositoryRoot: java.io.File by lazy {
            var dir: java.io.File? = java.io.File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (java.io.File(candidate, "app/src/main/java").isDirectory &&
                    java.io.File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
