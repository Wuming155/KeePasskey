package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.core.otp.OtpEngine
import com.keepasskey.app.testutil.MainDispatcherGuard
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @After
    fun tearDown() {
        // 对齐 §18 批次确立的口径：**先取消各 ViewModel 作用域、再 resetMain()**。
        // 本期的 TOTP 节拍由 `stateIn(viewModelScope, WhileSubscribed(5s))` 持有，
        // 用例结束后若任由其存活，后续用例重置 Main 时可能在途协程回跳到已缺失的 Main
        // （「测试调度器跨用例污染」这一类偶发红），故在恢复 Main 之前先终止本用例的作用域。
        MainDispatcherGuard.tearDown()
    }

    /**
     * 构造被测 ViewModel 并登记到 `MainDispatcherGuard`（teardown 统一取消其作用域，见 [tearDown]）。
     * [clipboard]：ISSUE-P2-353 AC① 后成功消息依赖真实可写的剪贴板通道——缺省 null 用于
     * 「通道缺失如实报失败」的负向用例，正向用例注入 [RecordingClipboardChannel]。
     */
    private fun TestScope.newViewModel(
        repository: FakeVaultRepository,
        clipboard: com.keepasskey.app.security.ClipboardSecurityChannel? = null
    ): VaultListViewModel =
        VaultListViewModel(
            repository,
            FakeSettingsRepository(),
            clipboard,
            buildTestCoordinator(),
            displayDispatcher = UnconfinedTestDispatcher(testScheduler)
        ).also { MainDispatcherGuard.track(it) }

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
    private fun TestScope.createSubscribedViewModel(
        repository: FakeVaultRepository = FakeVaultRepository(),
        clipboard: com.keepasskey.app.security.ClipboardSecurityChannel? = null
    ): VaultListViewModel {
        val viewModel = newViewModel(repository, clipboard)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
    }

    /** ISSUE-P2-353：为夹具条目 `2` 备好可读密码（Fake 的密码按需存储默认为空仓）。 */
    private suspend fun seedPasswordOfEntry2(repository: FakeVaultRepository) {
        val entry = repository.getEntries().first().find { it.id == "2" }!!
        repository.saveEntry(entry, passwordChars = "unit-test-password".toCharArray())
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

    /** ISSUE-P2-353：记录型剪贴板通道——只记录调用与内容，不触碰 Android 剪贴板（纯 JVM）。 */
    private class RecordingClipboardChannel : com.keepasskey.app.security.ClipboardSecurityChannel {
        var writeCount = 0
            private set
        var lastText: String? = null
            private set

        override fun copySensitiveText(
            label: CharSequence,
            text: CharSequence,
            customTimeoutSeconds: Int?
        ) {
            writeCount++
            lastText = text.toString()
        }

        override fun copySensitiveChars(
            label: CharSequence,
            chars: CharArray,
            customTimeoutSeconds: Int?
        ) {
            writeCount++
            lastText = String(chars)
        }

        override fun copyPlainText(label: CharSequence, text: CharSequence) {
            writeCount++
            lastText = text.toString()
        }
    }

    /** ISSUE-P2-353：写入即抛异常的通道——驱动「异常按失败回报」分支。 */
    private class ThrowingClipboardChannel : com.keepasskey.app.security.ClipboardSecurityChannel {
        override fun copySensitiveText(
            label: CharSequence,
            text: CharSequence,
            customTimeoutSeconds: Int?
        ): Unit = error("clipboard unavailable")

        override fun copySensitiveChars(
            label: CharSequence,
            chars: CharArray,
            customTimeoutSeconds: Int?
        ): Unit = error("clipboard unavailable")

        override fun copyPlainText(label: CharSequence, text: CharSequence): Unit =
            error("clipboard unavailable")
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

    /**
     * ISSUE-P2-356 投影时序回归锁：显示态即时回显，防抖只作用于过滤流。
     *
     * 快速连打（击键间隔远小于 300ms）期间显示通道必须已跟到最新串——旧实现显示值取
     * 自防抖后的 filterParams.query，受控 BasicTextField 被同步回旧值即「回吞」；
     * 同时反向断言过滤流不得抢跑（否则等于把整页投影改回每字符全量重算）。
     */
    @Test
    fun `快速连打显示态即时回显 防抖只作用于过滤流`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.onSearchQueryChange("g")
        viewModel.onSearchQueryChange("gi")
        viewModel.onSearchQueryChange("git")
        testScheduler.runCurrent()

        // 显示态：未推进虚拟时钟即须回显到最新串
        assertEquals("git", viewModel.searchQueryDisplay.value)
        // 过滤流：仍处防抖窗口内，整页状态的过滤关键词不得抢跑
        assertEquals("", viewModel.uiState.value.searchQuery)

        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        testScheduler.runCurrent()
        assertEquals("git", viewModel.uiState.value.searchQuery)
    }

    /**
     * ISSUE-P2-356 AC：清空立即生效——空串经 `debounce { }` 零超时旁路直达过滤流，
     * 不等 300ms 窗口（退出搜索即还原全列表）。反向锁：去掉旁路本断言即红。
     */
    @Test
    fun `清空搜索零延迟直达过滤流`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.onSearchQueryChange("github")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        testScheduler.runCurrent()
        assertEquals("github", viewModel.uiState.value.searchQuery)

        viewModel.onSearchQueryChange("")
        testScheduler.runCurrent()

        assertEquals("", viewModel.searchQueryDisplay.value)
        assertEquals("清空必须零延迟到达过滤流", "", viewModel.uiState.value.searchQuery)
    }

    /**
     * ISSUE-P2-356：createEntryPrefill 语义保持——预填发布**即时输入值**
     * （与显示态同源 searchQueryFlow），不因防抖窗口丢失用户最后敲入的关键词。
     */
    @Test
    fun `搜索预填发布即时输入值不等防抖`() = runTest {
        val host = com.keepasskey.app.ui.screens.edit.CreateEntryPrefillHost()
        val viewModel = VaultListViewModel(
            FakeVaultRepository(),
            FakeSettingsRepository(),
            null,
            buildTestCoordinator(),
            displayDispatcher = UnconfinedTestDispatcher(testScheduler),
            createEntryPrefill = host
        ).also { MainDispatcherGuard.track(it) }

        viewModel.onSearchQueryChange("github")
        // 未推进虚拟时钟（防抖未到期）即发布
        viewModel.beginCreateEntryFromSearch()
        testScheduler.runCurrent()

        assertEquals("github", host.takeTitle())
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

    /** ISSUE-P2-357 AC②：批量软删除的消息必须带撤销标记，撤销把整批条目移出回收站并回报还原数。 */
    @Test
    fun `批量软删除消息可撤销且撤销后整批条目回到回收站外`() = runTest {
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

        val deletedMessage = viewModel.uiState.value.userMessage
        assertEquals(R.string.vault_batch_deleted, deletedMessage?.resId)
        assertTrue(deletedMessage!!.undoable)
        assertEquals("group_recycle_bin", repository.getEntries().first().find { it.id == "2" }!!.groupId)

        viewModel.undoPendingSoftDelete()
        testScheduler.runCurrent()

        val entries = repository.getEntries().first()
        assertNull(entries.find { it.id == "2" }!!.groupId)
        assertNull(entries.find { it.id == "4" }!!.groupId)
        assertEquals(R.string.vault_batch_restored, viewModel.uiState.value.userMessage?.resId)
    }

    /** ISSUE-P2-357 AC②：彻底删除不可逆、不得置入待撤销批次——再点撤销为空操作，消息保持不变。 */
    @Test
    fun `彻底删除不产生可撤销消息且撤销对它为空操作`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.enterGroup("group_recycle_bin")
        viewModel.purgeEntry("entry_recycled_1")
        testScheduler.runCurrent()

        val purgeMessage = viewModel.uiState.value.userMessage
        assertEquals(R.string.vault_entry_purged, purgeMessage?.resId)
        assertFalse(purgeMessage!!.undoable)

        viewModel.undoPendingSoftDelete()
        testScheduler.runCurrent()

        assertEquals(
            "无待撤销批次时撤销不得产出任何新消息（否则会覆盖彻底删除的如实反馈）",
            R.string.vault_entry_purged,
            viewModel.uiState.value.userMessage?.resId
        )
        assertNull(repository.getEntries().first().find { it.id == "entry_recycled_1" })
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
        // ISSUE-P2-353 AC①：成功消息只在剪贴板**实际写入成功**后发出——
        // 正向夹具须同时备好可读密码与可用通道（旧行为在两者皆缺时也发成功，正是本缺陷）
        val repository = FakeVaultRepository()
        seedPasswordOfEntry2(repository)
        val channel = RecordingClipboardChannel()
        val viewModel = createSubscribedViewModel(repository, channel)
        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()
        val entry = viewModel.uiState.value.entries.find { it.id == "2" }!!

        viewModel.copyPassword(entry)
        testScheduler.runCurrent()
        assertEquals(R.string.vault_copy_password_done, viewModel.uiState.value.userMessage?.resId)
        assertEquals("密码须经剪贴板通道真实落值", "unit-test-password", channel.lastText)
        viewModel.clearUserMessage()
        testScheduler.runCurrent()

        viewModel.copyUsername(entry)
        testScheduler.runCurrent()
        assertEquals(R.string.vault_copy_username_done, viewModel.uiState.value.userMessage?.resId)
        assertEquals("用户名须经剪贴板通道真实落值", entry.username, channel.lastText)
    }

    /** ISSUE-P2-353 AC①：通道缺失（DI 注入 null）时不得发「已复制」，如实报失败。 */
    @Test
    fun `剪贴板通道缺失时复制密码与用户名如实报失败`() = runTest {
        val repository = FakeVaultRepository()
        seedPasswordOfEntry2(repository)
        val viewModel = createSubscribedViewModel(repository) // clipboard = null
        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()
        val entry = viewModel.uiState.value.entries.find { it.id == "2" }!!

        viewModel.copyPassword(entry)
        testScheduler.runCurrent()
        assertEquals(R.string.clipboard_copy_failed, viewModel.uiState.value.userMessage?.resId)
        viewModel.clearUserMessage()
        testScheduler.runCurrent()

        viewModel.copyUsername(entry)
        testScheduler.runCurrent()
        assertEquals(R.string.clipboard_copy_failed, viewModel.uiState.value.userMessage?.resId)
    }

    /** ISSUE-P2-353 AC①：仓库取不到密码（chars == null）时同样不得报成功。 */
    @Test
    fun `读不到密码时复制密码如实报失败`() = runTest {
        // 夹具条目 `2` 未 seed 密码 → getEntryPasswordChars 返回 null
        val channel = RecordingClipboardChannel()
        val viewModel = createSubscribedViewModel(FakeVaultRepository(), channel)
        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()
        val entry = viewModel.uiState.value.entries.find { it.id == "2" }!!

        viewModel.copyPassword(entry)
        testScheduler.runCurrent()

        assertEquals(R.string.clipboard_copy_failed, viewModel.uiState.value.userMessage?.resId)
        assertEquals("取值失败时不得发生任何剪贴板写入", 0, channel.writeCount)
    }

    /** ISSUE-P2-353 AC①：写入通道抛异常时按失败回报，不得把异常当成功。 */
    @Test
    fun `剪贴板写入异常时复制用户名如实报失败`() = runTest {
        val viewModel = createSubscribedViewModel(FakeVaultRepository(), ThrowingClipboardChannel())
        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()
        val entry = viewModel.uiState.value.entries.find { it.id == "2" }!!

        viewModel.copyUsername(entry)
        testScheduler.runCurrent()

        assertEquals(R.string.clipboard_copy_failed, viewModel.uiState.value.userMessage?.resId)
    }

    /** ISSUE-P2-353 AC①：无 TOTP 的条目仍走原有「缺失」文案（与通道失败区分开）。 */
    @Test
    fun `复制无验证码条目给出缺失提示而非通道失败`() = runTest {
        val channel = RecordingClipboardChannel()
        val viewModel = createSubscribedViewModel(FakeVaultRepository(), channel)
        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()
        val noteEntry = viewModel.uiState.value.entries.find { it.id == "6" }!!

        viewModel.copyTotpCode(noteEntry)
        testScheduler.runCurrent()

        assertEquals(R.string.vault_copy_totp_missing, viewModel.uiState.value.userMessage?.resId)
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

    // ---------------------------------------------------------------------
    // 顶栏扫码：otpauth 二维码 → 直接创建验证码条目
    // ---------------------------------------------------------------------

    @Test
    fun `扫码 otpauth 在当前分组创建验证码条目并存原文`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        viewModel.enterGroup("group_work")
        testScheduler.runCurrent()

        val uri = "otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        val decoded = uri.toCharArray()
        val before = repository.getEntries().first().size

        viewModel.onQrCodeDecoded(decoded)
        testScheduler.runCurrent()

        val entries = repository.getEntries().first()
        assertEquals("有效 otpauth 扫码应新增且仅新增一个条目", before + 1, entries.size)
        val created = entries.first { it.username == "alice@example.com" }
        assertEquals("title 应取 issuer", "Example", created.title)
        assertEquals("新建条目归属当前分组", "group_work", created.groupId)
        // 落库存原始 otpauth URI（period / digits / algorithm 全参数保真），不存裁剪后的裸种子
        assertEquals(uri, repository.lastSavedTotpByEntry[created.id])
        // 解码原文按擦除契约清零（含种子语义的 CharArray 不残留）
        assertTrue("扫码原文用毕必须清零", decoded.all { it == '0' })
        assertEquals(
            R.string.vault_scan_entry_created,
            viewModel.uiState.value.userMessage?.resId
        )
        assertEquals(listOf("Example"), viewModel.uiState.value.userMessage?.args)
    }

    @Test
    fun `扫码非 otpauth 文本拒绝落库并清零原文`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        val before = repository.getEntries().first().size

        // 纯字母单词全在 Base32 字母表内——若缺前缀强校验，宽容解析会把它当种子落库
        val decoded = "HELLOWORLD".toCharArray()
        viewModel.onQrCodeDecoded(decoded)
        testScheduler.runCurrent()

        assertEquals("非 otpauth 内容不得创建条目", before, repository.getEntries().first().size)
        assertTrue("被拒原文同样必须清零", decoded.all { it == '0' })
        assertEquals(
            R.string.vault_scan_invalid_qr,
            viewModel.uiState.value.userMessage?.resId
        )
    }

    @Test
    fun `扫码 otpauth 前缀但种子非法时拒绝落库并清零原文`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        val before = repository.getEntries().first().size

        // 前缀合法但 secret 含 Base32 字母表外字符——严格口径解析失败，不得落库
        val decoded = "otpauth://totp/Example:alice@example.com?secret=!!!invalid".toCharArray()
        viewModel.onQrCodeDecoded(decoded)
        testScheduler.runCurrent()

        assertEquals("解析失败不得创建条目", before, repository.getEntries().first().size)
        assertTrue("解析失败后原文必须清零", decoded.all { it == '0' })
        assertEquals(
            R.string.vault_scan_invalid_qr,
            viewModel.uiState.value.userMessage?.resId
        )
    }

    // ---------------------------------------------------------------------
    // ISSUE-P3-337：顶栏扫码的通行密钥分支（Q2「确认在先」+ AC①③④ 的接线层）
    // ---------------------------------------------------------------------

    /**
     * 规范附录 A 那把 passkey 的裸 `Passkey` 对象（`key` 为其原样值：Base64URL → PKCS#8 DER 138 B，
     * ES256）。多行只是 JSON 空白，不影响形态判定。
     */
    private fun cxfPasskeyJson(type: String = "passkey"): String = """
        {"type":"$type","credentialId":"Y3JlZGVudGlhbElkRXhhbXBsZQ","rpId":"webauthn.io",
         "username":"johndoe","userDisplayName":"John Doe",
         "userHandle":"cnEzaNHWcYK3coWZjvoaV1Hj9gnI12mKe2dL2HZVFlY",
         "key":"MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgARu_0sCt20EpgVxb4Puq3Ga5VVLpuTY75ngvZlyq3X6hRANCAASmdk1xLsK0oOlhxIPp0d1ZuS0sT9nf6BZtSelhqvLBW0fOL33l_bXgsr_STUHjCLn8l6gcRJwe7OQvbQubZ1dY"}
    """.trimIndent()

    @Test
    fun `扫码 CXF 载荷只进确认草案 不静默落库`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        testScheduler.runCurrent()
        val before = repository.getEntries().first().size

        val decoded = cxfPasskeyJson().toCharArray()
        viewModel.onQrCodeDecoded(decoded)
        testScheduler.runCurrent()

        assertEquals("Q2：未经确认不得建条目", before, repository.getEntries().first().size)
        assertTrue("上行原文在移交后即清零（草案承载的是自己的副本）", decoded.all { it == '0' })
        val draft = viewModel.pendingPasskeyImport.value
        assertNotNull("合法 CXF 载荷必须进入确认环节", draft)
        assertEquals("webauthn.io", draft!!.credential.relyingPartyId)
        assertNull("确认前不得有导航意图", viewModel.openEntryEditId.value)
        assertNull("确认前不得落库", repository.lastSavedPasskeyByEntry.values.firstOrNull())
        draft.wipe()
    }

    @Test
    fun `确认导入后落库于当前分组并交出条目id 草案即刻擦除`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val group = com.keepasskey.core.model.KdbxUuid.random()
        viewModel.enterGroup(group.toHexString())
        testScheduler.runCurrent()

        viewModel.onQrCodeDecoded(cxfPasskeyJson().toCharArray())
        testScheduler.runCurrent()
        val draft = requireNotNull(viewModel.pendingPasskeyImport.value)
        val pemBefore = draft.credential.privateKeyPemChars.copyOf()

        viewModel.confirmPasskeyImport()
        testScheduler.runCurrent()

        assertNull("确认即消费草案（不得二次落库）", viewModel.pendingPasskeyImport.value)
        assertTrue(
            "草案承载的私钥必须在落库后被清零（AC③）",
            draft.credential.privateKeyPemChars.all { it == '0' }
        )
        assertFalse("清零是改写而不是重新赋值：长度须保持", draft.credential.privateKeyPemChars.isEmpty())
        assertTrue("原私钥字符确实非空（否则上面断言为空转）", pemBefore.any { it != '0' })
        val saved = repository.lastSavedPasskeyByEntry
        assertEquals("只落一条", 1, saved.size)
        val fields = saved.getValue(saved.keys.single())
        assertTrue(
            "第二枚 PRF 种子不在此次载荷里 ⇒ 该扩展键不得写出",
            fields.none { it.key == com.keepasskey.core.model.PasskeyData.FIELD_PRF_NO_UV }
        )
        assertTrue(
            "私钥字段必须受保护",
            fields.first { it.key == com.keepasskey.core.model.PasskeyData.FIELD_PRIVATE_KEY }.value.isProtected
        )
        val savedId = saved.keys.single()
        val entry = repository.getKdbxEntries().first { it.id.toHexString() == savedId }
        assertEquals("顶栏导入须落在用户当下所在分组", group, entry.parentGroupId)
        assertEquals(R.string.passkey_import_created, viewModel.uiState.value.userMessage?.resId)
        assertEquals("成功后交出「打开该条目编辑页」的一次性意图", groupEntryEditId(viewModel), entry.id.toHexString())
    }

    /** 读取并消费一次性导航意图（同时验证「消费后归零」）。 */
    private fun groupEntryEditId(viewModel: VaultListViewModel): String? {
        val id = viewModel.openEntryEditId.value
        viewModel.consumeOpenEntryEditId()
        return id
    }

    @Test
    fun `取消导入不落库且同样擦除私钥`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        testScheduler.runCurrent()
        val before = repository.getEntries().first().size

        viewModel.onQrCodeDecoded(cxfPasskeyJson().toCharArray())
        testScheduler.runCurrent()
        val draft = requireNotNull(viewModel.pendingPasskeyImport.value)

        viewModel.dismissPasskeyImport()
        testScheduler.runCurrent()

        assertEquals("取消不得建条目", before, repository.getEntries().first().size)
        assertNull(viewModel.pendingPasskeyImport.value)
        assertTrue("取消路径同样必须清零私钥（AC③ 点名的取消分支）", draft.credential.privateKeyPemChars.all { it == '0' })
        assertNull("取消不得交出导航意图", viewModel.openEntryEditId.value)
    }

    @Test
    fun `分流按形态单义判定 CXF 缺私钥时走通行密钥错误码而非回退猜测`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        testScheduler.runCurrent()
        val before = repository.getEntries().first().size

        val json = cxfPasskeyJson().replace(Regex("\"key\":\"[^\"]*\""), "\"other\":\"x\"")
        val decoded = json.toCharArray()
        viewModel.onQrCodeDecoded(decoded)
        testScheduler.runCurrent()

        assertEquals("缺 `key` 属仪式字段缺失，一律拒收", before, repository.getEntries().first().size)
        assertNull("拒收不得留下草案", viewModel.pendingPasskeyImport.value)
        assertEquals(R.string.passkey_import_failed_invalid, viewModel.uiState.value.userMessage?.resId)
        assertTrue("被拒载荷同样清零", decoded.all { it == '0' })
    }

    @Test
    fun `分流不新增菜单项与跳转 只有确认对话框`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        testScheduler.runCurrent()
        viewModel.onQrCodeDecoded(cxfPasskeyJson(type = "totp").toCharArray())
        testScheduler.runCurrent()
        assertNull(
            "裸单对象形态 type 非 passkey ⇒ 静态拒收（文档内混装才是「跳过并计数」）",
            viewModel.pendingPasskeyImport.value
        )
        assertEquals(R.string.passkey_import_failed_invalid, viewModel.uiState.value.userMessage?.resId)
    }
}
