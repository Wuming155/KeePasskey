package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.model.EntryIcon
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.app.ui.screens.settings.ListDensity
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P3-17 库列表侧 4 个显示偏好的**真实接线**单测：
 * `listDensity` / `showGroupInSearchResult` / `autoActivateSearchOnOpen` 经 UiState 下发，
 * 以及 ISSUE-P3-22 的分组自定义图标投影（含缺图占位语义）。
 *
 * 偏好快照经 [ExtendedSettingsSource] 注入：可在 JVM 直接驱动任意偏好组合，
 * 不依赖 SharedPreferences（后者在桌面单测中不可用）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultDisplayPreferencesTest {

    private companion object {
        /** 与 VaultListViewModel.SEARCH_DEBOUNCE_MS 对齐 */
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    private fun TestScope.createViewModel(
        repository: FakeVaultRepository = FakeVaultRepository(),
        settingsSource: ExtendedSettingsSource
    ): VaultListViewModel {
        val viewModel = VaultListViewModel(
            vaultRepository = repository,
            settingsRepository = FakeSettingsRepository(),
            clipboardSecurityManager = null,
            syncCoordinator = buildTestCoordinator(),
            stringsProvider = null,
            displayDispatcher = UnconfinedTestDispatcher(testScheduler),
            extendedSettingsSource = settingsSource
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.advanceUntilIdle()
        return viewModel
    }

    // ===== listDensity =====

    @Test
    fun `列表密度偏好驱动 UiState 密度下发`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(listDensity = ListDensity.COMPACT) }
        )

        assertEquals(ListDensity.COMPACT, viewModel.uiState.value.listDensity)
        assertEquals(
            ListDensityPresenter.specOf(ListDensity.COMPACT),
            ListDensityPresenter.specOf(viewModel.uiState.value.listDensity)
        )
    }

    @Test
    fun `列表密度偏好改为宽松后页面重进即生效`() = runTest {
        var snapshot = ExtendedSettings(listDensity = ListDensity.NORMAL)
        val viewModel = createViewModel(settingsSource = { snapshot })
        assertEquals(ListDensity.NORMAL, viewModel.uiState.value.listDensity)

        snapshot = ExtendedSettings(listDensity = ListDensity.COMFORTABLE)
        viewModel.onScreenEntered()
        testScheduler.runCurrent()

        assertEquals(ListDensity.COMFORTABLE, viewModel.uiState.value.listDensity)
    }

    // ===== autoActivateSearchOnOpen =====

    @Test
    fun `开启自动聚焦搜索时下发一次性意图且消费后清除`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(autoActivateSearchOnOpen = true) }
        )

        assertTrue("开启偏好后应下发自动聚焦意图", viewModel.uiState.value.autoActivateSearch)

        viewModel.consumeAutoActivateSearch()
        testScheduler.runCurrent()

        assertFalse("意图消费后必须清除，避免重组反复弹输入法", viewModel.uiState.value.autoActivateSearch)
    }

    @Test
    fun `关闭自动聚焦搜索时不下发聚焦意图`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(autoActivateSearchOnOpen = false) }
        )

        assertFalse(viewModel.uiState.value.autoActivateSearch)
    }

    @Test
    fun `已消费的自动聚焦意图不会因页面重进而重复装载`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(autoActivateSearchOnOpen = true) }
        )
        viewModel.consumeAutoActivateSearch()
        testScheduler.runCurrent()

        viewModel.onScreenEntered()
        testScheduler.runCurrent()

        assertFalse(
            "「打开数据库后自动聚焦」是一次性语义，返回列表页不得再次抢焦点",
            viewModel.uiState.value.autoActivateSearch
        )
    }

    // ===== showGroupInSearchResult =====

    private suspend fun FakeVaultRepository.seedGroupedEntry(id: String, groupId: String) {
        saveEntry(
            UiVaultEntry(
                id = id,
                title = "研发条目 $id",
                username = "user",
                url = "https://example.com",
                groupId = groupId
            ),
            null,
            null,
            emptyMap()
        )
    }

    @Test
    fun `开启搜索结果分组路径时下发条目所属分组完整路径`() = runTest {
        val repository = FakeVaultRepository()
        repository.seedGroupedEntry("dev_entry", "group_dev")
        val viewModel = createViewModel(
            repository = repository,
            settingsSource = { ExtendedSettings(showGroupInSearchResult = true) }
        )

        viewModel.onSearchQueryChange("研发")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue("搜索结果中应含目标条目", state.entries.any { it.id == "dev_entry" })
        assertEquals(
            "工作与生产力 / 研发与基础设施",
            state.entryGroupPaths["dev_entry"]
        )
    }

    @Test
    fun `关闭搜索结果分组路径时不下发任何路径`() = runTest {
        val repository = FakeVaultRepository()
        repository.seedGroupedEntry("dev_entry", "group_dev")
        val viewModel = createViewModel(
            repository = repository,
            settingsSource = { ExtendedSettings(showGroupInSearchResult = false) }
        )

        viewModel.onSearchQueryChange("研发")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.showGroupInSearchResult)
        assertTrue(
            "关闭开关后行组件不应收到任何分组路径",
            state.entryGroupPaths.isEmpty()
        )
    }

    @Test
    fun `非搜索态不下发分组路径`() = runTest {
        val repository = FakeVaultRepository()
        repository.seedGroupedEntry("dev_entry", "group_dev")
        val viewModel = createViewModel(
            repository = repository,
            settingsSource = { ExtendedSettings(showGroupInSearchResult = true) }
        )

        viewModel.onScreenEntered()
        testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value.entryGroupPaths.isEmpty())
    }

    // ===== ISSUE-P3-22：分组自定义图标投影 =====

    @Test
    fun `绑定库内图标的分组下发自定义图标投影`() = runTest {
        val repository = FakeVaultRepository()
        val iconId = (repository.addCustomIcon(byteArrayOf(1, 2, 3)) as KdbxResult.Success).data
        val work = repository.getGroups().first().first { it.id == "group_work" }
        repository.saveGroup(work.copy(customIconId = iconId))
        val viewModel = createViewModel(repository = repository, settingsSource = { ExtendedSettings() })

        val icon = viewModel.uiState.value.groupIcons["group_work"]
        assertTrue("命中图标池的分组应投影为自定义图标（载荷解码失败不影响判定）", icon is EntryIcon.Custom<*>)
        assertEquals(iconId, (icon as EntryIcon.Custom<*>).iconId)
    }

    @Test
    fun `引用残留的图标 id 投影为缺图占位而非标准图标`() = runTest {
        val repository = FakeVaultRepository()
        val work = repository.getGroups().first().first { it.id == "group_work" }
        // 图标已在其他端删除而 CustomIconUUID 残留：必须如实呈现缺图占位，不得谎报为标准图标
        repository.saveGroup(work.copy(customIconId = "icon_gone"))
        val viewModel = createViewModel(repository = repository, settingsSource = { ExtendedSettings() })

        assertEquals(EntryIcon.Missing, viewModel.uiState.value.groupIcons["group_work"])
    }

    @Test
    fun `未绑定自定义图标的分组回退标准图标`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = createViewModel(repository = repository, settingsSource = { ExtendedSettings() })

        val icon = viewModel.uiState.value.groupIcons["group_work"]
        assertEquals(EntryIcon.Default("work"), icon)
        assertNull(repository.getGroups().first().first { it.id == "group_work" }.customIconId)
    }
}
