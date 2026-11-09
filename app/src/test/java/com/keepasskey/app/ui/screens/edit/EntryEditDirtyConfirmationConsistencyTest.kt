package com.keepasskey.app.ui.screens.edit

import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.security.UnsavedEditRegistry
import com.keepasskey.app.testutil.MainDispatcherGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `ISSUE-P3-643`：**离页丢弃确认与全局脏表单登记读数必须一致**。
 *
 * 整改前半环（重复选择图标不得清掉其它未保存编辑的脏位）由
 * `EntryEditViewModelTest#重复选择图标保留标题密码脏态且未改动负对照干净` 锁定；
 * 本件补后半环——两个消费方读的必须是**同一份**脏位，否则会退化成
 * 「离页不弹确认，但锁定路径仍报有草稿」（或反过来）：
 * - 编辑页返回键 / 顶栏返回的丢弃确认谓词（`EntryEditContent.requestBack`，读 `isDirty`）；
 * - 自动锁定路径的 `UnsavedEditRegistry.hasUnsavedEdits()`（读同一 `_uiState.value.isDirty`）。
 *
 * 用例覆盖整改方案 §4 矩阵的三种序列 + 一个负对照，并在每条序列后同时断言
 * `uiState.isDirty` 与登记表读数一致。
 *
 * ENG-20261028-12 整改：原附带的源码守卫（逐字匹配 `EntryEditScreen.kt` 的
 * `if (isDirty) showDiscardDialog = true else onBackClick()` 等实现串）已删除——它锁的是
 * 整改当时的写法，提取谓词 / 改行即误红；该判据的行为面由设备用例
 * `EntryEditDirtyConfirmationDeviceTest`（驱动生产编辑页真实点返回、断言确认框出现 / 不出现）
 * 承担，本件只保留可脱离平台的「三读一致」行为读数。
 *
 * 不修改既有 `EntryEditViewModelTest`：那份用例的断言是本项前半环的既有证据，
 * 本件只做增量（AGENTS「测试资产纪律」）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryEditDirtyConfirmationConsistencyTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    @Test
    fun `改标题后重复选择同一图标_确认与登记读数一致`() = runTest {
        val registry = UnsavedEditRegistry()
        val model = EntryEditViewModel(
            SavedStateHandle(mapOf("groupId" to "group_work")),
            FakeVaultRepository(),
            unsavedEditRegistry = registry
        )
        MainDispatcherGuard.track(model)
        testScheduler.runCurrent()

        assertConsistent(model, registry, expectedDirty = false, stage = "初始空白表单")

        model.onTitleChange("Synthetic edit")
        val currentIcon = model.uiState.value.customIconId
        // 连续两次选中「当前」图标：整改前第二次会把 isDirty 覆盖为 false
        model.onCustomIconSelected(currentIcon)
        model.onCustomIconSelected(currentIcon)

        assertConsistent(model, registry, expectedDirty = true, stage = "改标题 + 重复选择当前图标")
    }

    @Test
    fun `改密码后连续两次选择同一新图标_确认与登记读数一致`() = runTest {
        val registry = UnsavedEditRegistry()
        val model = EntryEditViewModel(
            SavedStateHandle(mapOf("groupId" to "group_work")),
            FakeVaultRepository(),
            unsavedEditRegistry = registry
        )
        MainDispatcherGuard.track(model)
        testScheduler.runCurrent()

        model.onPasswordChangeSecure("Fake-Password-1".toCharArray())
        model.onCustomIconSelected(NEW_ICON_ID)
        assertConsistent(model, registry, expectedDirty = true, stage = "改密码 + 首次选新图标")

        model.onCustomIconSelected(NEW_ICON_ID)
        assertConsistent(model, registry, expectedDirty = true, stage = "再次选择同一新图标")
    }

    @Test
    fun `纯未改动负对照_选择当前图标不得凭空变脏`() = runTest {
        val registry = UnsavedEditRegistry()
        val model = EntryEditViewModel(
            SavedStateHandle(mapOf("groupId" to "group_work")),
            FakeVaultRepository(),
            unsavedEditRegistry = registry
        )
        MainDispatcherGuard.track(model)
        testScheduler.runCurrent()

        val currentIcon = model.uiState.value.customIconId
        model.onCustomIconSelected(currentIcon)
        model.onCustomIconSelected(currentIcon)

        assertConsistent(model, registry, expectedDirty = false, stage = "未改动负对照")
    }

    @Test
    fun `保存成功后确认与登记读数一并回落`() = runTest {
        val registry = UnsavedEditRegistry()
        val model = EntryEditViewModel(
            SavedStateHandle(mapOf("groupId" to "group_work")),
            FakeVaultRepository(),
            unsavedEditRegistry = registry
        )
        MainDispatcherGuard.track(model)
        testScheduler.runCurrent()

        model.onTitleChange("Saved synthetic entry")
        model.onCustomIconSelected(model.uiState.value.customIconId)
        assertConsistent(model, registry, expectedDirty = true, stage = "保存前")

        model.saveEntry()
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()
        assertConsistent(model, registry, expectedDirty = false, stage = "保存成功后")
    }

    /**
     * 三读一致：编辑页会弹确认（`isDirty`）、锁定路径会报有草稿（登记表）、
     * 且两者必须相等——任一为真另一为假即是本项要防的漂移。
     */
    private fun assertConsistent(
        model: EntryEditViewModel,
        registry: UnsavedEditRegistry,
        expectedDirty: Boolean,
        stage: String
    ) {
        val isDirty = model.uiState.value.isDirty
        val registrySaysDirty = registry.hasUnsavedEdits()
        assertEquals("$stage：编辑页脏位", expectedDirty, isDirty)
        assertEquals(
            "$stage：登记表读数必须与编辑页脏位一致（否则离页确认与锁定丢弃告知会互相矛盾）",
            isDirty,
            registrySaysDirty
        )
    }

    private companion object {
        const val NEW_ICON_ID = "new-icon"
    }
}
