package com.keepasskey.app.ui.screens.edit

import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.testutil.MainDispatcherGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ISSUE-P2-354 AC①②`：条目保存防重复（双击不产生重复条目 / 保存成功只导航一次）。
 *
 * 旧缺陷形态：无 `isSaving` 守卫、新条目 UUID 在协程内生成——快速双击读到两次
 * `entryId == null`，落两条重复条目、发两次 SaveSuccess。本类用**虚拟时钟内的连点**
 * 复现该形态：第二次 `saveEntry()` 在第一次的协程体尚未执行时就到达，
 * 判据只能由「守卫同步置位 + 单条落库 + 单次事件」共同给出。
 *
 * 断言刻意不重言（`check_tautological_assertions` 口径）：`isSaving` 的断言夹在
 * 两个 await 点之间读到**不同值**，事件与落库计数均来自仓库/事件流外部观测。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryEditSaveDebounceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        // 先取消本用例登记的 ViewModel 作用域、再恢复 Main（ISSUE-P3-189，见 MainDispatcherGuard）。
        MainDispatcherGuard.tearDown()
    }

    @Test
    fun `连续两次 saveEntry 只落库一条且只发一次 SaveSuccess`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = EntryEditViewModel(SavedStateHandle(), repository)
        MainDispatcherGuard.track(viewModel)
        val events = mutableListOf<EntryEditEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events.add(it) }
        }

        viewModel.onTitleChange("防重复条目")
        viewModel.onUsernameChange("double-tap-user")

        // 第一次点击：守卫必须在协程体执行前**同步**置位（UUID 也在守卫之后生成）
        viewModel.saveEntry()
        assertTrue(
            "提交后 isSaving 必须同步为 true（否则同帧第二次点击可穿透守卫）",
            viewModel.uiState.value.isSaving
        )

        // 第二次点击：第一次的协程体尚未被调度执行，entryId 仍为 null——
        // 正是旧缺陷读到「entryId == null」的那个时间窗
        viewModel.saveEntry()

        testScheduler.runCurrent()

        val stored = repository.getEntries().first().filter { it.title == "防重复条目" }
        assertEquals("双击必须只落库一条", 1, stored.size)
        assertEquals("保存成功事件必须只发一次（导航只发生一次）", listOf(EntryEditEvent.SaveSuccess), events)
        assertNotNull("成功后必须记下条目 id", viewModel.uiState.value.entryId)
        assertEquals(stored.first().id, viewModel.uiState.value.entryId)
        assertFalse("成功后 isSaving 必须回落", viewModel.uiState.value.isSaving)
        assertFalse("成功后脏位必须清除", viewModel.uiState.value.isDirty)
    }

    @Test
    fun `新建保存前 isSaving 为 false 且标题为空时被拒不置忙态`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = EntryEditViewModel(SavedStateHandle(), repository)
        MainDispatcherGuard.track(viewModel)

        // 标题为空：entrySaveRejectionRes 拦截——拒绝路径不得把 isSaving 卡在 true
        viewModel.saveEntry()
        assertFalse("校验拒绝不得进入忙态", viewModel.uiState.value.isSaving)
        testScheduler.runCurrent()
        assertFalse("校验拒绝后忙态仍为 false", viewModel.uiState.value.isSaving)
    }
}
