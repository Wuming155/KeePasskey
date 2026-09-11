package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.childdb.ChildDatabaseCredentialStore
import com.keepasskey.app.data.childdb.ChildDatabaseFixtures
import com.keepasskey.app.data.childdb.ChildDatabaseMount
import com.keepasskey.app.data.childdb.ChildDatabaseMountStore
import com.keepasskey.app.data.childdb.ChildDatabaseSessionManager
import com.keepasskey.app.data.childdb.FakeChildDatabaseStreamSource
import com.keepasskey.app.data.childdb.RecordingContextFactory
import com.keepasskey.app.data.childdb.validLocalSource
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.security.FakeUnlockThrottleStore
import com.keepasskey.app.security.UnlockThrottleManager
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
 * ISSUE-P3-30：子库条目投影接入库列表的**端到端**单测（ViewModel 级）。
 *
 * 与 `ChildDatabaseSessionManagerTest` 的分工：那边证明「子库能真实解密并产出投影」，
 * 这边证明「投影接入列表后仍然只是只读分区」——四条验收口径逐一落到断言：
 *
 * ① 已解锁子库条目以真实投影（含子库内分组路径）下发，且**不进入**根库条目流；
 * ② 根库批量写路径（全选 / 批量删除）**只作用于根库条目**，子库投影不被改动；
 * ③ 根库锁定后分区**即时消失**（挂载登记保留，符合核心层语义）；
 * ④ 搜索态**不参与**（子库条目不出现在搜索结果里），分区隐藏并如实给出已挂载计数。
 *
 * 子库会话与语料复用 `data/childdb` 的共享替身：语料由生产写入管线 [com.keepasskey.database.file.KdbxFile.save]
 * 现场生成、由生产读取管线真实解密，故「真实解密链路」不是假数据。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultListChildDatabaseTest {

    private companion object {
        /** 与 VaultListViewModel.SEARCH_DEBOUNCE_MS 对齐的搜索防抖窗口（毫秒） */
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    private val testDispatcher = StandardTestDispatcher()

    private val factory = RecordingContextFactory()

    private val childSource = FakeChildDatabaseStreamSource()

    private val rootSession = DatabaseSession()

    private val childPassword = "child-master-pw".toCharArray()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newChildManager(): ChildDatabaseSessionManager = ChildDatabaseSessionManager(
        mountStore = ChildDatabaseMountStore(factory.context),
        credentials = ChildDatabaseCredentialStore(),
        streamSource = childSource,
        databaseSession = rootSession,
        debugLog = DebugLogBuffer(),
        unlockThrottleManager = UnlockThrottleManager(FakeUnlockThrottleStore())
    )

    /** 构造最小可用的 SyncCoordinator（假 Context + 空会话）；本测试不触发真实同步 */
    private fun buildTestCoordinator(): com.keepasskey.app.sync.SyncCoordinator {
        val cacheDir = java.nio.file.Files.createTempDirectory("kp_cache").toFile()
        val filesDir = java.nio.file.Files.createTempDirectory("kp_files").toFile()
        val context = object : android.content.ContextWrapper(null) {
            override fun getCacheDir(): java.io.File = cacheDir
            override fun getFilesDir(): java.io.File = filesDir
            override fun getApplicationContext(): android.content.Context = this
        }
        return com.keepasskey.app.sync.SyncCoordinator(
            context,
            com.keepasskey.database.session.DatabaseSession(),
            com.keepasskey.app.sync.SyncCredentialsStore(context, null),
            DebugLogBuffer()
        )
    }

    /** 创建被测 ViewModel 并后台订阅 uiState；展示装配调度器落在虚拟时间轴上以保证断言确定性 */
    private fun TestScope.createViewModel(manager: ChildDatabaseSessionManager): VaultListViewModel {
        val viewModel = VaultListViewModel(
            vaultRepository = FakeVaultRepository(),
            settingsRepository = FakeSettingsRepository(),
            clipboardSecurityManager = null,
            syncCoordinator = buildTestCoordinator(),
            stringsProvider = null,
            displayDispatcher = UnconfinedTestDispatcher(testScheduler),
            extendedSettingsSource = null,
            childDatabaseSessionManager = manager
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.advanceUntilIdle()
        return viewModel
    }

    /** 以真实密文作来源完成一次子库挂载（真实解密 + 真实投影） */
    private suspend fun mountChild(manager: ChildDatabaseSessionManager): ChildDatabaseMount {
        childSource.payload = ChildDatabaseFixtures.kdbxBytes(childPassword)
        val result = manager.mount("工作子库", validLocalSource(), childPassword, null)
        assertTrue("子库应挂载成功但实际失败: $result", result.isSuccess)
        return result.getOrThrow()
    }

    @Test
    fun `已解锁子库条目以只读分区下发且不进入根库条目流`() = runTest {
        val manager = newChildManager()
        val viewModel = createViewModel(manager)
        val rootIdsBefore = viewModel.uiState.value.entries.map { it.id }.toSet()
        val totalBefore = viewModel.uiState.value.totalEntriesCount

        mountChild(manager)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.childEntryGroups.size)
        val group = state.childEntryGroups.single()
        assertEquals("工作子库", group.mountAlias)
        assertEquals(ChildDatabaseFixtures.ENTRY_COUNT, group.entries.size)
        assertEquals(1, state.mountedChildDatabaseCount)
        assertTrue("顶层未搜索时应展示子库分区", state.childEntrySectionVisible)

        // 真实解密结果落地：子分组条目带子库内路径，根级条目回退为挂载别名
        assertTrue(
            "子分组条目应带子库内分组路径",
            group.entries.any {
                it.title == ChildDatabaseFixtures.SUB_ENTRY_TITLE &&
                    it.displayPath.contains(ChildDatabaseFixtures.SUB_GROUP_NAME)
            }
        )
        assertTrue(
            "根级条目路径应回退为挂载别名",
            group.entries.any {
                it.title == ChildDatabaseFixtures.ROOT_ENTRY_TITLE && it.displayPath == "工作子库"
            }
        )

        // 结构隔离：根库条目流与总数不因子库挂载而改变
        val childUuids = group.entries.map { it.entryUuid }.toSet()
        assertTrue("子库条目 UUID 不得出现在根库条目流", state.entries.none { it.id in childUuids })
        assertEquals(rootIdsBefore, state.entries.map { it.id }.toSet())
        assertEquals(totalBefore, state.totalEntriesCount)
    }

    @Test
    fun `根库锁定后子库只读分区即时消失但挂载登记保留`() = runTest {
        val manager = newChildManager()
        val viewModel = createViewModel(manager)
        mountChild(manager)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.childEntrySectionVisible)

        rootSession.lock()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("锁定即终止子库会话，分区必须同步消失", state.childEntryGroups.isEmpty())
        assertFalse(state.childEntrySectionVisible)
        assertEquals("挂载登记属非敏感配置，锁定后仍保留", 1, state.mountedChildDatabaseCount)
    }

    @Test
    fun `卸载后子库只读分区消失`() = runTest {
        val manager = newChildManager()
        val viewModel = createViewModel(manager)
        val record = mountChild(manager)
        testScheduler.advanceUntilIdle()

        manager.unmount(record.id)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.childEntryGroups.isEmpty())
        assertFalse(state.childEntrySectionVisible)
        assertEquals(0, state.mountedChildDatabaseCount)
    }

    @Test
    fun `搜索态不把子库条目混入结果且分区隐藏并如实提示`() = runTest {
        val manager = newChildManager()
        val viewModel = createViewModel(manager)
        mountChild(manager)
        testScheduler.advanceUntilIdle()

        // 关键词命中子库条目标题（根库语料不可能命中），据此断言「未参与搜索」
        viewModel.onSearchQueryChange(ChildDatabaseFixtures.SUB_ENTRY_TITLE)
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("子库条目不得作为搜索结果出现", state.entries.isEmpty())
        assertFalse("搜索态下不展示子库分区", state.childEntrySectionVisible)
        assertEquals("仍需如实告知存在已挂载子库（提示行依据）", 1, state.mountedChildDatabaseCount)
    }

    @Test
    fun `批量全选与批量删除只作用于根库条目且子库投影不受影响`() = runTest {
        val manager = newChildManager()
        val viewModel = createViewModel(manager)
        mountChild(manager)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        val rootEntryIds = state.entries.map { it.id }.toSet()
        val childUuids = state.childEntryGroups.flatMap { it.entries }.map { it.entryUuid }.toSet()
        assertTrue("前置：根库顶层应有条目可供全选", rootEntryIds.isNotEmpty())

        viewModel.selectAllEntries()
        testScheduler.advanceUntilIdle()

        assertEquals("全选只能覆盖根库条目", rootEntryIds, viewModel.uiState.value.selectedEntryIds)
        assertTrue(
            "子库条目 UUID 不得进入选中集合（否则会进批量写路径）",
            viewModel.uiState.value.selectedEntryIds.none { it in childUuids }
        )

        viewModel.batchDeleteSelected()
        testScheduler.advanceUntilIdle()

        val afterDelete = viewModel.uiState.value
        assertEquals(
            "根库批量删除不得改动子库投影",
            ChildDatabaseFixtures.ENTRY_COUNT,
            afterDelete.childEntryGroups.flatMap { it.entries }.size
        )
        assertTrue(afterDelete.childEntrySectionVisible)
    }
}
