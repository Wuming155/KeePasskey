package com.keepasskey.app.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.CustomIconAdmin
import com.keepasskey.app.data.repository.CustomIconCoordinator
import com.keepasskey.app.data.repository.CustomIconRefs
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.model.EntryIcon
import com.keepasskey.app.ui.model.EntryIconProjection
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P3-02（TASK-49）自定义图标删除单测。
 *
 * 覆盖三层：
 * 1. [CustomIconRefs]（纯函数）：引用回退为默认图标 + 回退计数 + 未引用子树保持原实例；
 * 2. [CustomIconCoordinator.deleteCustomIcon]（会话 + 落盘）：Meta 图标池移除、引用回退、
 *    落盘结果如实上浮、库未打开/非法 id 如实失败；
 * 3. [EntryDetailViewModel]：删除上下行与消息、无绑定图标时不发起删除、
 *    展示装饰（图标投影 + Notes 引用展开）装配。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomIconDeleteTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun kdbxEntry(customIconId: KdbxUuid? = null, title: String = "entry") = KdbxEntry(
        id = KdbxUuid.random(),
        customIconId = customIconId,
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, false))
    )

    private fun stringsProvider() = StringsProvider { _, _ -> "测试文案" }

    // ===== 1. 引用回退（纯函数） =====

    @Test
    fun `删除图标后引用条目回退为默认图标且计数正确`() {
        val removedIcon = KdbxUuid.random()
        val otherIcon = KdbxUuid.random()
        val referencing = kdbxEntry(removedIcon)
        val unrelated = kdbxEntry(otherIcon)
        val plain = kdbxEntry(null)
        val nested = KdbxGroup(name = "Sub", entries = listOf(kdbxEntry(removedIcon)))
        val root = KdbxGroup(
            name = "Root",
            entries = listOf(referencing, unrelated, plain),
            subgroups = listOf(nested)
        )

        val (updated, revertedCount) = CustomIconRefs.clearReferences(root, removedIcon)

        assertEquals(2, revertedCount)
        val updatedEntries = updated.allEntries()
        assertNull(
            "引用被删除图标的条目必须回退为默认图标",
            updatedEntries.first { it.id == referencing.id }.customIconId
        )
        assertNull(updatedEntries.first { it.id == nested.entries.first().id }.customIconId)
        assertSame(
            "未引用该图标的条目保持原实例（copy-on-write 友好）",
            unrelated,
            updatedEntries.first { it.id == unrelated.id }
        )
        assertSame(plain, updatedEntries.first { it.id == plain.id })
    }

    @Test
    fun `回退后投影层呈现为标准图标`() {
        val removedIcon = KdbxUuid.random()
        val root = KdbxGroup(name = "Root", entries = listOf(kdbxEntry(removedIcon, title = "t")))

        val revertedEntry = CustomIconRefs.clearReferences(root, removedIcon).first.allEntries().first()
        assertNull(revertedEntry.customIconId)

        val projected = EntryIconProjection.of<String>(
            customIconId = revertedEntry.customIconId?.toHexString(),
            iconName = "key",
            availableIconIds = emptySet()
        )

        assertEquals(EntryIcon.Default("key"), projected)
    }

    @Test
    fun `无条目引用时保持原树实例且计数为零`() {
        val root = KdbxGroup(name = "Root", entries = listOf(kdbxEntry(KdbxUuid.random())))

        val (updated, revertedCount) = CustomIconRefs.clearReferences(root, KdbxUuid.random())

        assertEquals(0, revertedCount)
        assertSame(root, updated)
    }

    @Test
    fun `分组自身引用的图标一并回退`() {
        val removedIcon = KdbxUuid.random()
        val group = KdbxGroup(name = "G", customIconId = removedIcon)

        val (updated, revertedCount) = CustomIconRefs.clearReferences(group, removedIcon)

        assertNull(updated.customIconId)
        assertEquals("分组引用不计入条目回退数量", 0, revertedCount)
    }

    // ===== 2. 协调器（会话 + 落盘） =====

    private suspend fun sessionWithIcon(
        iconId: KdbxUuid,
        rootGroup: KdbxGroup
    ): DatabaseSession {
        val session = DatabaseSession()
        session.setDatabaseForTesting(
            KdbxDatabase(
                header = KdbxHeader.createDefault(),
                rootGroup = rootGroup,
                customIcons = listOf(CustomIcon(uuid = iconId, data = byteArrayOf(1, 2, 3)))
            )
        )
        return session
    }

    @Test
    fun `协调器删除图标同步清理 Meta 并将引用条目回退默认`() = runTest {
        val iconId = KdbxUuid.random()
        val referencing = kdbxEntry(iconId)
        val session = sessionWithIcon(iconId, KdbxGroup(name = "Root", entries = listOf(referencing)))
        var persistCalls = 0
        val coordinator = CustomIconCoordinator(stringsProvider(), session) {
            persistCalls++
            KdbxResult.Success(Unit)
        }

        val result = coordinator.deleteCustomIcon(iconId.toHexString())

        assertTrue(result is KdbxResult.Success)
        assertEquals(1, (result as KdbxResult.Success).data)
        val updatedDb = session.databaseFlow.first()!!
        assertTrue("Meta 图标池条目必须被移除", updatedDb.customIcons.isEmpty())
        assertNull(
            "引用条目必须回退为默认图标",
            updatedDb.rootGroup.allEntries().first().customIconId
        )
        assertEquals("删除必须落盘一次", 1, persistCalls)
    }

    @Test
    fun `落盘失败原样上浮不谎报成功`() = runTest {
        val iconId = KdbxUuid.random()
        val session = sessionWithIcon(iconId, KdbxGroup(name = "Root", entries = listOf(kdbxEntry(iconId))))
        val coordinator = CustomIconCoordinator(stringsProvider(), session) {
            KdbxResult.Failure(IllegalStateException("磁盘错误"), "磁盘错误")
        }

        val result = coordinator.deleteCustomIcon(iconId.toHexString())

        assertTrue(result is KdbxResult.Failure)
        assertEquals("磁盘错误", (result as KdbxResult.Failure).message)
    }

    @Test
    fun `库未打开时删除如实失败`() = runTest {
        var persistCalls = 0
        val coordinator = CustomIconCoordinator(stringsProvider(), DatabaseSession()) {
            persistCalls++
            KdbxResult.Success(Unit)
        }

        val result = coordinator.deleteCustomIcon(KdbxUuid.random().toHexString())

        assertTrue(result is KdbxResult.Failure)
        assertEquals(0, persistCalls)
    }

    @Test
    fun `非法图标 id 如实失败`() = runTest {
        val iconId = KdbxUuid.random()
        val session = sessionWithIcon(iconId, KdbxGroup(name = "Root", entries = listOf(kdbxEntry(iconId))))
        var persistCalls = 0
        val coordinator = CustomIconCoordinator(stringsProvider(), session) {
            persistCalls++
            KdbxResult.Success(Unit)
        }

        val result = coordinator.deleteCustomIcon("not-a-uuid")

        assertTrue(result is KdbxResult.Failure)
        assertEquals(0, persistCalls)
        assertEquals("非法 id 不得改动图标池", 1, session.databaseFlow.first()!!.customIcons.size)
    }

    // ===== 3. 详情页 ViewModel =====

    private class FakeCustomIconAdmin(private val result: KdbxResult<Int>) : CustomIconAdmin {
        val deletedIconIds = mutableListOf<String>()

        override suspend fun deleteCustomIcon(iconIdHex: String): KdbxResult<Int> {
            deletedIconIds += iconIdHex
            return result
        }
    }

    private fun TestScope.createViewModel(
        entryId: String?,
        repository: FakeVaultRepository,
        customIconAdmin: CustomIconAdmin? = null
    ): EntryDetailViewModel {
        val handle = if (entryId != null) SavedStateHandle(mapOf("entryId" to entryId)) else SavedStateHandle()
        val viewModel = EntryDetailViewModel(
            appContext = null,
            savedStateHandle = handle,
            vaultRepository = repository,
            settingsRepository = FakeSettingsRepository(),
            clipboardSecurityManager = null,
            autofillBlocklistStore = AutofillBlocklistStore(null),
            customIconAdmin = customIconAdmin,
            displayDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
    }

    private suspend fun FakeVaultRepository.addEntryWithIcon(
        entryId: String,
        iconId: String?,
        title: String = "条目",
        username: String = "user",
        notes: String = ""
    ) {
        saveEntry(
            UiVaultEntry(
                id = entryId,
                title = title,
                username = username,
                url = "",
                notes = notes,
                customIconId = iconId
            ),
            null,
            null,
            emptyMap()
        )
    }

    @Test
    fun `详情页删除图标成功后上行删除并提示`() = runTest {
        val repository = FakeVaultRepository()
        repository.addEntryWithIcon("e1", "icon-hex")
        val admin = FakeCustomIconAdmin(KdbxResult.Success(1))
        val viewModel = createViewModel("e1", repository, admin)

        assertEquals("icon-hex", viewModel.uiState.value.entry?.customIconId)

        viewModel.deleteCustomIcon()
        testScheduler.runCurrent()

        assertEquals(listOf("icon-hex"), admin.deletedIconIds)
        assertEquals(R.string.vault_icon_delete_done, viewModel.uiState.value.userMessage?.resId)
    }

    @Test
    fun `详情页删除失败如实提示不谎报成功`() = runTest {
        val repository = FakeVaultRepository()
        repository.addEntryWithIcon("e1", "icon-hex")
        val admin = FakeCustomIconAdmin(KdbxResult.Failure(IllegalStateException("磁盘错误"), "磁盘错误"))
        val viewModel = createViewModel("e1", repository, admin)

        viewModel.deleteCustomIcon()
        testScheduler.runCurrent()

        assertEquals(R.string.vault_op_failed, viewModel.uiState.value.userMessage?.resId)
        assertEquals(listOf("磁盘错误"), viewModel.uiState.value.userMessage?.args)
    }

    @Test
    fun `条目未绑定自定义图标时不发起删除`() = runTest {
        val repository = FakeVaultRepository()
        repository.addEntryWithIcon("e1", iconId = null)
        val admin = FakeCustomIconAdmin(KdbxResult.Success(0))
        val viewModel = createViewModel("e1", repository, admin)

        viewModel.deleteCustomIcon()
        testScheduler.runCurrent()

        assertTrue(admin.deletedIconIds.isEmpty())
        assertNull(viewModel.uiState.value.userMessage)
    }

    @Test
    fun `详情页装配自定义图标投影与 Notes 引用展开文案`() = runTest {
        val repository = FakeVaultRepository()
        val iconId = (repository.addCustomIcon(byteArrayOf(1, 2, 3)) as KdbxResult.Success).data
        repository.addEntryWithIcon("e2", null, title = "GitHub", username = "octocat")
        repository.addEntryWithIcon(
            entryId = "e1",
            iconId = iconId,
            title = "消费条目",
            notes = "账号 {REF:U@T:GitHub} / 密码 {REF:P@T:GitHub}"
        )

        val viewModel = createViewModel("e1", repository, FakeCustomIconAdmin(KdbxResult.Success(0)))
        val state = viewModel.uiState.value
        val entry = state.entry
        assertNotNull(entry)

        val icon = state.decorations.iconOf(entry!!)
        assertTrue("绑定了库内自定义图标应投影为自定义图标", icon is EntryIcon.Custom)
        assertEquals(iconId, (icon as EntryIcon.Custom<*>).iconId)

        val notes = state.decorations.textOf(entry).notes
        assertEquals("账号 octocat / 密码 ${com.keepasskey.database.fieldref.FieldReferenceEngine.PROTECTED_PLACEHOLDER}", notes)
        assertFalse("展示文案不得物化受保护字段", notes.contains("{REF:P@T:GitHub}"))
    }
}
