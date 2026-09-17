package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.core.otp.OtpEngine
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * VaultListViewModel 状态流转单元测试：
 * 覆盖文件夹导航、搜索过滤、排序、批量管理、回收站操作、TOTP 实时倒计时与消息资源。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultListViewModelTest {

    private companion object {
        /** 与 VaultListViewModel.SEARCH_DEBOUNCE_MS 对齐的搜索防抖窗口（毫秒） */
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    /** 各用例创建的 ViewModel；teardown 时统一取消其作用域（见 [tearDown] 说明）。 */
    private val createdViewModels = mutableListOf<VaultListViewModel>()

    @After
    fun tearDown() {
        // 对齐 §18 批次确立的口径：**先取消各 ViewModel 作用域、再 resetMain()**。
        // 本期的 TOTP 节拍由 `stateIn(viewModelScope, WhileSubscribed(5s))` 持有，
        // 用例结束后若任由其存活，后续用例重置 Main 时可能在途协程回跳到已缺失的 Main
        // （「测试调度器跨用例污染」这一类偶发红），故在恢复 Main 之前先终止本用例的作用域。
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        Dispatchers.resetMain()
    }

    /**
     * 构造被测 ViewModel 并登记到 [createdViewModels]（teardown 统一取消其作用域，见 [tearDown]）。
     */
    private fun TestScope.newViewModel(repository: FakeVaultRepository): VaultListViewModel =
        VaultListViewModel(
            repository,
            FakeSettingsRepository(),
            null,
            buildTestCoordinator(),
            displayDispatcher = UnconfinedTestDispatcher(testScheduler)
        ).also { createdViewModels += it }

    /**
     * 窄通道刻度 → 30 秒周期条目的剩余秒数（ISSUE-P3-158：列表徽标用同一函数按条目自身周期换算）。
     */
    private fun VaultListViewModel.remainingSecondsOf30sEntry(): Int =
        OtpEngine.getRemainingSeconds(
            timestampMillis = totpNowSeconds.value * 1000L,
            periodSeconds = 30
        )

    /**
     * 创建被测 ViewModel 并在后台订阅 uiState 以驱动 stateIn 的 WhileSubscribed 上游计算
     */
    private fun TestScope.createSubscribedViewModel(): VaultListViewModel {
        val viewModel = newViewModel(FakeVaultRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
    }

    /**
     * 构造最小可用的 SyncCoordinator（假 Context + 空会话）；本测试不触发真实同步，
     * 协调器仅满足构造依赖
     */
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
            com.keepasskey.app.data.logger.DebugLogBuffer()
        )
    }

    @Test
    fun `进入分组后展示该分组的条目`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(listOf("group_work", "group_dev"), state.breadcrumbs.map { it.id })
        assertEquals(setOf("2", "4", "6"), state.entries.map { it.id }.toSet())
    }

    @Test
    fun `面包屑导航与返回上一级`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.enterGroup("group_work")
        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()
        var state = viewModel.uiState.value
        assertEquals(listOf("group_work", "group_dev"), state.breadcrumbs.map { it.id })

        viewModel.navigateUp()
        testScheduler.runCurrent()
        state = viewModel.uiState.value
        assertEquals("group_work", state.currentGroupId)

        viewModel.navigateToBreadcrumb(null)
        testScheduler.runCurrent()
        state = viewModel.uiState.value
        assertNull(state.currentGroupId)
    }

    @Test
    fun `搜索时全局匹配条目标题`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.onSearchQueryChange("github")
        // P2 整改：搜索输入已接入 300ms 防抖（官方 Flow.debounce），
        // 需先把虚拟时钟推进越过防抖窗口再断言列表结果
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.entries.any { it.id == "2" })
    }

    @Test
    fun `按名称升序排序`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.enterGroup("group_dev")
        viewModel.setSortOption(VaultSortOption.NAME_ASC)
        testScheduler.runCurrent()

        val titles = viewModel.uiState.value.entries.map { it.title }
        assertEquals(titles.sortedBy { it.lowercase() }, titles)
    }

    @Test
    fun `批量选择与批量移入回收站`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.enterGroup("group_dev")
        viewModel.startBatchMode("2")
        viewModel.toggleEntrySelection("4")
        viewModel.batchDeleteSelected()
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isBatchMode)
        val entries = repository.getEntries().first()
        assertEquals("group_recycle_bin", entries.find { it.id == "2" }!!.groupId)
        assertEquals("group_recycle_bin", entries.find { it.id == "4" }!!.groupId)
    }

    @Test
    fun `取消全部选中时自动退出批量模式`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.startBatchMode("2")
        viewModel.toggleEntrySelection("2")
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isBatchMode)
    }

    @Test
    fun `回收站内还原条目`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.enterGroup("group_recycle_bin")
        viewModel.restoreEntry("entry_recycled_1")
        testScheduler.runCurrent()

        val restored = repository.getEntries().first().find { it.id == "entry_recycled_1" }!!
        assertNull(restored.groupId)
    }

    @Test
    fun `彻底删除回收站条目`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.enterGroup("group_recycle_bin")
        viewModel.purgeEntry("entry_recycled_1")
        testScheduler.runCurrent()

        assertNull(repository.getEntries().first().find { it.id == "entry_recycled_1" })
    }

    @Test
    fun `批量移动选中条目到目标分组`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.startBatchMode("1")
        viewModel.toggleEntrySelection("3")
        viewModel.batchMoveSelected("group_work")
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isBatchMode)
        val entries = repository.getEntries().first()
        assertEquals("group_work", entries.find { it.id == "1" }!!.groupId)
        assertEquals("group_work", entries.find { it.id == "3" }!!.groupId)
    }

    @Test
    fun `删除分组将下属条目移入回收站`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.deleteGroup("group_dev")
        testScheduler.runCurrent()

        assertTrue(repository.getGroups().first().none { it.id == "group_dev" })
        val devEntries = repository.getEntries().first().filter { it.id == "2" || it.id == "4" || it.id == "6" }
        assertTrue(devEntries.isNotEmpty())
        assertTrue(devEntries.all { it.groupId == "group_recycle_bin" })
    }

    @Test
    fun `清空回收站`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        repository.deleteEntry("1")

        viewModel.emptyRecycleBin()
        testScheduler.runCurrent()

        assertTrue(repository.getEntries().first().none { it.groupId == "group_recycle_bin" })
    }

    @Test
    fun `带 TOTP 的条目在投影层即时算出验证码且倒计时改由窄通道下发`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        val totpEntry = state.entries.find { it.id == "2" }!!
        // ISSUE-P2-89：条目上的验证码仍是投影层即时计算值（用于识别「配置了 OTP」与首帧兜底），
        // 但**剩余秒数不再由整页状态下发**，改由 totpNowSeconds 窄通道（秒级刻度）给列表行徽标
        assertNotNull(totpEntry.totpCode)
        assertTrue(viewModel.remainingSecondsOf30sEntry() in 1..30)
        // 无 TOTP 的条目不受影响
        assertNull(state.entries.find { it.id == "6" }!!.totpCode)
    }

    @Test
    fun `TOTP 剩余秒数始终处于有效周期内`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.enterGroup("group_dev")
        advanceTimeBy(2500)
        testScheduler.runCurrent()

        val seconds = viewModel.remainingSecondsOf30sEntry()
        assertTrue(seconds in 1..30)
    }

    /**
     * ISSUE-P2-89 回归锁（结构性契约）：整页会话状态**不得**再承载任何 TOTP 实时输入。
     *
     * 与本文件运行时的「秒级倒计时不再驱动整页状态重建」互补——那条断言证明「当前不发生」，
     * 本条证明「结构上不可能发生」：只要有人把秒数 / 验证码重新塞回会话状态，
     * 秒级 tick 就又能推出新的整页状态；本断言让这种回退在单测期直接变红。
     */
    @Test
    fun `整页会话状态不再承载任何 TOTP 实时输入`() {
        val fields = VaultListSessionState::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue("会话状态不得再承载 TOTP 实时输入：$fields", fields.none { it.contains("totp") })
    }

    /**
     * ISSUE-P2-89 回归锁：秒级倒计时**不得**再驱动整页状态重建。
     *
     * 旧实现把 `totpTracker.remainingSeconds` 并入最外层 `combine`，于是每过 1 秒
     * `uiState` 都会重新发射一次（每次都要重跑过滤 / 排序 / 全量 copy，且在 Main 线程）。
     * 现两条 TOTP 实时值都走窄通道，故：跨过多个 tick 之后 `uiState` **一次都不应重发**，
     * 而倒计时通道仍须照常可用——两侧同时断言，杜绝「干脆不刷新了」的假绿。
     *
     * 判别力边界（如实声明）：本用例的倒计时取值来自真实墙钟，故「值确实变了」这一点
     * 在虚拟时间下无法复现（1 秒内的墙钟余数相同，`distinctUntilChanged` 会吸收重复值）。
     * 真正锁死「回退即变红」的是同文件的
     * `整页会话状态不再承载任何 TOTP 实时输入`（结构契约）与本断言
     * （秒级 tick 后整页状态零重发）。
     */
    @Test
    fun `秒级倒计时不再驱动整页状态重建`() = runTest {
        val viewModel = createSubscribedViewModel()
        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()

        // 与生产等价：页面同时订阅 uiState 与徽标倒计时窄通道
        val emissions = mutableListOf<VaultListUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { emissions += it }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.totpNowSeconds.collect {}
        }
        testScheduler.runCurrent()

        val before = emissions.size
        advanceTimeBy(3_100)
        testScheduler.runCurrent()

        assertEquals("秒级 tick 不得再产生新的整页状态", before, emissions.size)
        // 窄通道必须仍然可用（否则上面的断言会以「节拍根本没跑」的方式假绿）
        assertTrue(viewModel.remainingSecondsOf30sEntry() in 1..30)
    }

    @Test
    fun `复制密码与复制用户名的消息资源`() = runTest {
        val viewModel = createSubscribedViewModel()
        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()
        val entry = viewModel.uiState.value.entries.find { it.id == "2" }!!

        viewModel.copyPassword(entry)
        testScheduler.runCurrent()
        assertEquals(R.string.vault_copy_password_done, viewModel.uiState.value.userMessage?.resId)
        viewModel.clearUserMessage()
        testScheduler.runCurrent()

        viewModel.copyUsername(entry)
        testScheduler.runCurrent()
        assertEquals(R.string.vault_copy_username_done, viewModel.uiState.value.userMessage?.resId)
    }

    @Test
    fun `复制无用户名条目给出缺失提示`() = runTest {
        val viewModel = createSubscribedViewModel()
        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()
        val noteEntry = viewModel.uiState.value.entries.find { it.id == "6" }!!.copy(username = "")

        viewModel.copyUsername(noteEntry)
        testScheduler.runCurrent()

        assertEquals(R.string.vault_copy_username_missing, viewModel.uiState.value.userMessage?.resId)
    }

    @Test
    fun `新建文件夹归属当前分组`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        viewModel.enterGroup("group_work")
        testScheduler.runCurrent()

        viewModel.createGroup("新建分组")
        testScheduler.runCurrent()

        val created = repository.getGroups().first().find { it.name == "新建分组" }
        assertNotNull(created)
        assertEquals("group_work", created!!.parentId)
    }
}
