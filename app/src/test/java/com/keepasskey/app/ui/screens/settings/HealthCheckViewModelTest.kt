package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SettingsViewModel 真实健康检查审计集成测试 (Wave 3-E P1-13)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthCheckViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class TestAuditVaultRepository(
        private val entriesToReturn: List<KdbxEntry>,
        private val delegate: VaultRepository = FakeVaultRepository()
    ) : VaultRepository by delegate {
        override suspend fun getKdbxEntries(): List<KdbxEntry> = entriesToReturn
    }

    @Test
    fun `默认态为未扫描，触发扫描后真实计算审计分数与弱密码复用统计`() = runTest(testDispatcher) {
        // 构造测试数据：
        // 1 个空密码（弱密码）
        val entryWeak1 = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Weak Entry 1", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("", false)
            )
        )
        // 2 个相同口令（复用密码）
        val entryReused1 = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Site A", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("LongSecurePass123!", false)
            )
        )
        val entryReused2 = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Site B", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("LongSecurePass123!", false)
            )
        )

        val cacheDir = java.nio.file.Files.createTempDirectory("health_cache").toFile()
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
        val credentialsStore = com.keepasskey.app.sync.SyncCredentialsStore(fakeContext, null)
        val coordinator = com.keepasskey.app.sync.SyncCoordinator(fakeContext, com.keepasskey.database.session.DatabaseSession(), credentialsStore, com.keepasskey.app.data.logger.DebugLogBuffer())

        val testRepo = TestAuditVaultRepository(listOf(entryWeak1, entryReused1, entryReused2))
        val viewModel = SettingsViewModel(FakeSettingsRepository(), testRepo, credentialsStore, coordinator, com.keepasskey.app.data.logger.DebugLogBuffer())
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        testScheduler.runCurrent()

        // 验证默认态
        val initialHealth = viewModel.uiState.value
        assertEquals("未扫描", initialHealth.healthStatus)
        assertEquals(0, initialHealth.healthScore)
        assertFalse(initialHealth.isHealthScanning)

        // 触发真实审计扫描
        viewModel.rescanHealth()
        testScheduler.runCurrent()

        val scannedHealth = viewModel.uiState.value
        assertFalse(scannedHealth.isHealthScanning)

        // 审计结果断言：
        // weakCount: 1 (entryWeak1)
        // reusedCount: 2 (entryReused1 和 entryReused2 均属于复用 issue)
        assertEquals(1, scannedHealth.weakPasswordCount)
        assertEquals(2, scannedHealth.reusedPasswordCount)

        // score 公式: 100 - 1*5 - 2*10 = 75 分
        val expectedScore = 100 - (1 * 5) - (2 * 10)
        assertEquals(expectedScore, scannedHealth.healthScore)
        // 75 分在 [70, 90) 区间，分档为 "良好"
        assertEquals("良好", scannedHealth.healthStatus)
        assertTrue(scannedHealth.lastHealthScanTime.startsWith("今天 "))
    }
}
