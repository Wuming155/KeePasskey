package com.keepasskey.app.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.model.UiVaultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ISSUE-P2-357` 遗留补齐 + `ISSUE-P3-359` AC④：详情页**单条软删除**的撤销链路。
 *
 * 旧形态：删除确认后只置回退信号，页面即弹回列表——没有任何可恢复出口。
 * 新形态：软删（删除后仍在库中且已入回收站）发布 `undoable` 消息 + 携带撤销动作；
 * 动作由外壳全局宿主以**自身作用域**执行（详情页 ViewModel 此时已随回退销毁），
 * 恢复结果回执还原 / 失败消息。物理删除（已在回收站内）不给假撤销入口。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryDetailSoftDeleteUndoTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    /** 排空全局通道存量（进程级单例，其他用例可能先发布过事件）。 */
    private suspend fun drainChannel() {
        while (withTimeoutOrNull(50L) { AppSnackbarChannel.events.first() } != null) {
            // 丢弃存量
        }
    }

    private fun TestScope.createViewModel(
        entryId: String,
        repository: FakeVaultRepository
    ): EntryDetailViewModel {
        val viewModel = EntryDetailViewModel(
            appContext = null,
            savedStateHandle = SavedStateHandle(mapOf("entryId" to entryId)),
            vaultRepository = repository,
            settingsRepository = FakeSettingsRepository(),
            clipboardSecurityManager = null,
            autofillBlocklistStore = AutofillBlocklistStore(null),
            displayDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return MainDispatcherGuard.track(viewModel)
    }

    @Test
    fun `软删除发布可撤销消息且撤销恢复条目并回执`() = runTest {
        drainChannel()
        val repository = FakeVaultRepository()
        val viewModel = createViewModel("1", repository)

        viewModel.deleteEntry()
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        // ① 状态层记录（既有 uiState.userMessage 断言口径不变）
        val message = viewModel.uiState.value.userMessage
        assertEquals("软删必须产出撤销消息", R.string.vault_entry_deleted, message?.resId)
        assertTrue("软删消息必须标记 undoable", message!!.undoable)
        assertTrue("删除后应已置一次性回退信号", viewModel.entryDeleted.value)

        // ② 通道事件携带撤销动作（外壳宿主消费的就是这条）
        val event = AppSnackbarChannel.events.first()
        assertEquals(R.string.vault_entry_deleted, event.message.resId)
        assertNotNull("undoable 事件必须携带撤销动作", event.onUndo)

        // ③ 以外壳作用域执行撤销（不依赖详情页 ViewModel 存活）→ 条目回到非回收站态
        event.onUndo!!.invoke()
        testScheduler.advanceUntilIdle()
        val restored = repository.getEntry("1").first()
        assertNotNull("撤销必须把条目还原回库", restored)
        assertFalse("还原后条目不得仍在回收站", restored!!.isRecycled)

        // ④ 还原回执经通道如实上浮
        val ack = AppSnackbarChannel.events.first()
        assertEquals(R.string.vault_entry_restored, ack.message.resId)
    }

    @Test
    fun `回收站内条目删除走物理删除不给撤销入口`() = runTest {
        drainChannel()
        val repository = FakeVaultRepository()
        repository.saveEntry(
            UiVaultEntry(
                id = "already-binned",
                title = "已在回收站的条目",
                username = "",
                url = "",
                groupId = FakeVaultRepository.RECYCLE_BIN_GROUP_ID
            ),
            null,
            null,
            emptyMap()
        )
        val viewModel = createViewModel("already-binned", repository)

        viewModel.deleteEntry()
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        assertTrue("物理删除同样置回退信号", viewModel.entryDeleted.value)
        assertNull("物理删除无从还原，不得产出撤销消息", viewModel.uiState.value.userMessage)
        assertNull(
            "物理删除不得向通道发布任何事件（不给假撤销入口）",
            withTimeoutOrNull(50L) { AppSnackbarChannel.events.first() }
        )
        assertNull("墓碑语义：条目应已彻底消失", repository.getEntry("already-binned").first())
    }
}
