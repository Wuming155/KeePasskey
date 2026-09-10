package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.autofill.testHmacFieldSignatureSource
import com.keepasskey.app.data.breach.BreachCheckCoordinator
import com.keepasskey.app.data.breach.BreachCheckException
import com.keepasskey.app.data.breach.BreachCheckStatus
import com.keepasskey.app.data.breach.BreachRangeClient
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TASK-47：健康度「已泄露密码」指标真实化后的设置页集成回归。
 *
 * 验收要点：
 * 1. **关闭态零外联**：开关默认关闭，扫描不发起任何泄露查询请求，指标为 null（不以 0 冒充安全）；
 * 2. **开启态真实计数**：命中泄露库时计数与状态如实下发，并计入健康分扣减；
 * 3. **失败如实上浮**：查询失败转 FAILED 并透出原因，绝不静默回落为「未泄露」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BreachCheckHealthTest {

    private val testDispatcher = StandardTestDispatcher()

    /** 记录查询次数并可控失败的假范围查询客户端 */
    private class RecordingRangeClient(
        private val breachedSuffixes: Set<String> = emptySet(),
        private val failure: String? = null
    ) : BreachRangeClient {
        var queryCount = 0
        override suspend fun queryRange(prefix: String): Set<String> {
            queryCount++
            if (failure != null) throw BreachCheckException(failure)
            return breachedSuffixes
        }
    }

    private class TestVaultRepository(
        private val entriesToReturn: List<KdbxEntry>,
        private val delegate: VaultRepository = FakeVaultRepository()
    ) : VaultRepository by delegate {
        override suspend fun getKdbxEntries(): List<KdbxEntry> = entriesToReturn
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun breachedEntry(): KdbxEntry = KdbxEntry(
        id = KdbxUuid(ByteArray(16) { 1 }),
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("Breached Site", isProtected = false),
            // 公开常量口令：SHA-1 = 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8
            KdbxConstants.Fields.PASSWORD to ProtectedString("password", isProtected = true)
        )
    )

    private fun buildViewModel(rangeClient: BreachRangeClient): SettingsViewModel {
        val cacheDir = java.nio.file.Files.createTempDirectory("breach_cache").toFile()
        val fakeContext: android.content.Context = object : android.content.ContextWrapper(null) {
            override fun getCacheDir(): java.io.File = cacheDir
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences =
                java.lang.reflect.Proxy.newProxyInstance(
                    android.content.SharedPreferences::class.java.classLoader,
                    arrayOf(android.content.SharedPreferences::class.java)
                ) { _, method, args ->
                    if (method.name == "getString") args.getOrNull(1) else null
                } as android.content.SharedPreferences
        }
        val credentialsStore = SyncCredentialsStore(fakeContext, null)
        val coordinator = SyncCoordinator(
            fakeContext,
            DatabaseSession(),
            credentialsStore,
            com.keepasskey.app.data.logger.DebugLogBuffer(),
            TEST_STRINGS
        )
        return SettingsViewModel(
            FakeSettingsRepository(),
            TestVaultRepository(listOf(breachedEntry())),
            credentialsStore,
            coordinator,
            com.keepasskey.app.data.logger.DebugLogBuffer(),
            ExtendedSettingsStore(null),
            com.keepasskey.app.sync.PeriodicSyncScheduler(fakeContext, ExtendedSettingsStore(null)),
            com.keepasskey.app.data.repository.AutofillBlocklistStore(null),
            // ISSUE-P3-43：保存侧黑名单与字段级屏蔽（内存语义）
            com.keepasskey.app.autofill.AutofillSaveBlocklistStore(null),
            com.keepasskey.app.autofill.AutofillFieldBlocklistStore(null, testHmacFieldSignatureSource()),
            BreachCheckCoordinator(rangeClient),
            stringsProvider = TEST_STRINGS
        )
    }

    @Test
    fun `关闭态不发起任何泄露查询且指标为 null（不以 0 冒充安全）`() = runTest(testDispatcher) {
        val rangeClient = RecordingRangeClient(breachedSuffixes = ALL_SUFFIXES)
        val viewModel = buildViewModel(rangeClient)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        runCurrent()

        viewModel.rescanHealth()
        runCurrent()

        assertEquals(0, rangeClient.queryCount)
        val state = viewModel.uiState.value
        assertEquals(BreachCheckStatus.DISABLED, state.breachCheckStatus)
        assertNull(state.compromisedPasswordCount)

        job.cancel()
    }

    @Test
    fun `开启后命中泄露库时如实计数并扣减健康分`() = runTest(testDispatcher) {
        val rangeClient = RecordingRangeClient(breachedSuffixes = setOf(BREACHED_SUFFIX))
        val viewModel = buildViewModel(rangeClient)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        runCurrent()

        viewModel.setBreachCheckEnabled(true)
        runCurrent()
        assertTrue(viewModel.uiState.value.breachCheckEnabled)

        viewModel.rescanHealth()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(BreachCheckStatus.BREACHED, state.breachCheckStatus)
        assertEquals(1, state.compromisedPasswordCount ?: -1)
        // 基础 100 - 弱密码 1×5（"password" 属常见弱口令）- 泄露 1×20 = 75
        assertEquals(75, state.healthScore)
        assertTrue(rangeClient.queryCount >= 1)

        job.cancel()
    }

    @Test
    fun `查询失败时状态为 FAILED 且原因如实上浮，绝不回落为未泄露`() = runTest(testDispatcher) {
        val rangeClient = RecordingRangeClient(failure = "模拟网络不可达")
        val viewModel = buildViewModel(rangeClient)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        runCurrent()

        viewModel.setBreachCheckEnabled(true)
        runCurrent()
        viewModel.rescanHealth()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(BreachCheckStatus.FAILED, state.breachCheckStatus)
        assertNull(state.compromisedPasswordCount)
        assertEquals("模拟网络不可达", state.breachCheckMessage)

        job.cancel()
    }

    private companion object {
        /** TASK-21：单测注入按资源 ID 映射的假 StringsProvider（无 Android 资源环境） */
        private val TEST_STRINGS = com.keepasskey.app.ui.model.StringsProvider { id, args ->
            when (id) {
                com.keepasskey.app.R.string.health_status_not_scanned -> "未扫描"
                com.keepasskey.app.R.string.health_scan_hint_idle -> "待扫描"
                com.keepasskey.app.R.string.health_breach_error_unknown -> "未知错误"
                com.keepasskey.app.R.string.health_scan_failed -> "失败: %1\$s"
                else -> "s$id:${args.joinToString()}"
            }
        }

        /** 口令 "password" 的 SHA-1 后缀（公开常量，非真实凭据） */
        private const val BREACHED_SUFFIX = "1E4C9B93F3F0682250B6CF8331B7EE68FD8"

        private val ALL_SUFFIXES = setOf(BREACHED_SUFFIX)
    }
}

/**
 * 测试用空范围查询客户端：泄露检测默认关闭，扫描期本不应被调用；
 * 任何误调用也不会产生真实外联（仅返回空结果）。
 */
object NoOpBreachRangeClient : BreachRangeClient {
    override suspend fun queryRange(prefix: String): Set<String> = emptySet()
}
