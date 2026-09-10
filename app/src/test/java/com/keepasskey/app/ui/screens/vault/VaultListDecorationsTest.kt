package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.model.EntryIcon
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.fieldref.FieldReferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P3-02（TASK-49）列表页展示装饰装配单测（ViewModel 级接线）：
 * 条目绑定的自定义图标被投影为 [EntryIcon.Custom]，Notes/URL 中的 `{REF:...}`
 * 经展示模式展开（受保护字段掩码），并随 uiState 下发到行组件消费点。
 *
 * 展示装配调度器注入测试调度器，使 `flowOn` 上游落在虚拟时间轴上（断言确定性）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultListDecorationsTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
            com.keepasskey.app.data.logger.DebugLogBuffer()
        )
    }

    private fun TestScope.createSubscribedViewModel(repository: FakeVaultRepository): VaultListViewModel {
        val viewModel = VaultListViewModel(
            vaultRepository = repository,
            settingsRepository = FakeSettingsRepository(),
            clipboardSecurityManager = null,
            syncCoordinator = buildTestCoordinator(),
            stringsProvider = null,
            displayDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.advanceUntilIdle()
        return viewModel
    }

    private suspend fun FakeVaultRepository.seedEntry(entry: UiVaultEntry) {
        saveEntry(entry, null, null, emptyMap())
    }

    @Test
    fun `列表装配自定义图标投影与引用展开文案`() = runTest {
        val repository = FakeVaultRepository()
        val rootGroupId = repository.getGroups().first().first { it.parentId == null }.id
        val iconId = (repository.addCustomIcon(byteArrayOf(9, 9, 9)) as KdbxResult.Success).data
        repository.seedEntry(
            UiVaultEntry(
                id = "ref_target",
                title = "GitHub",
                username = "octocat",
                url = "https://github.com",
                notes = ""
            )
        )
        repository.seedEntry(
            UiVaultEntry(
                id = "consumer",
                title = "消费条目",
                username = "user",
                url = "{REF:A@T:GitHub}",
                notes = "账号 {REF:U@T:GitHub} / 密码 {REF:P@T:GitHub}",
                groupId = rootGroupId,
                customIconId = iconId
            )
        )

        val viewModel = createSubscribedViewModel(repository)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        val entry = state.entries.firstOrNull { it.id == "consumer" }
        assertEquals("消费条目应出现在当前（根）目录列表中", "consumer", entry?.id)
        val consumer = entry!!

        val icon = state.decorations.iconOf(consumer)
        assertTrue("绑定库内自定义图标应投影为自定义图标", icon is EntryIcon.Custom)
        assertEquals(iconId, (icon as EntryIcon.Custom<*>).iconId)

        val display = state.decorations.textOf(consumer)
        assertEquals("https://github.com", display.url)
        assertEquals(
            "账号 octocat / 密码 ${FieldReferenceEngine.PROTECTED_PLACEHOLDER}",
            display.notes
        )
        assertFalse("展示文案不得物化受保护字段明文", display.notes.contains("{REF:P@T:GitHub}"))
    }

    @Test
    fun `未绑定自定义图标的条目投影为标准图标`() = runTest {
        val repository = FakeVaultRepository()
        val rootGroupId = repository.getGroups().first().first { it.parentId == null }.id
        repository.seedEntry(
            UiVaultEntry(
                id = "plain",
                title = "普通条目",
                username = "user",
                url = "https://example.com",
                notes = "普通备注",
                groupId = rootGroupId
            )
        )
        val viewModel = createSubscribedViewModel(repository)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        val entry = state.entries.first { it.id == "plain" }

        assertEquals(EntryIcon.Default("key"), state.decorations.iconOf(entry))
        assertEquals("普通备注", state.decorations.textOf(entry).notes)
        assertEquals("https://example.com", state.decorations.textOf(entry).url)
    }
}
