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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

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
 * `uiState.isDirty`、登记表读数与「返回是否会弹确认」三者一致；另附一条源码守卫，
 * 钉住 UI 的确认谓词确实以 `isDirty` 为唯一开关（改读别的字段即红）。
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
     * 源码守卫：离页确认谓词只能以 `isDirty` 为开关（与登记表同一字段），
     * 且**不得**改成 `userMessage` / `title` 之类的旁证。
     */
    @Test
    fun `离页确认谓词以同一脏位为唯一开关`() {
        val screen = readSource(EDIT_SCREEN)
        assertTrue(
            "离页丢弃确认必须由 isDirty 驱动（与 UnsavedEditRegistry 同一字段）",
            screen.contains("if (isDirty) showDiscardDialog = true else onBackClick()")
        )
        // 两处返回入口（系统返回键 + 顶栏返回）必须共用同一谓词，不得各写一份
        assertTrue(
            "系统返回键必须走 requestBack 谓词",
            screen.contains("BackHandler(onBack = requestBack)")
        )
        assertTrue(
            "顶栏返回必须走同一 requestBack 谓词（不得各自判脏）",
            screen.contains("requestBack = requestBack")
        )
        assertEquals(
            "requestBack 谓词只允许有一处定义（多处定义即可能漂移成两套判据）",
            1,
            Regex("val requestBack: \\(\\) -> Unit =").findAll(screen).count()
        )
        val viewModel = readSource(EDIT_VIEW_MODEL)
        assertTrue(
            "登记表提供者必须读同一 isDirty",
            viewModel.contains("unsavedEditRegistry?.register(this) { _uiState.value.isDirty }")
        )
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

    private fun readSource(path: String): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return File(root, path).readText()
    }

    private companion object {
        const val NEW_ICON_ID = "new-icon"

        const val EDIT_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditScreen.kt"
        const val EDIT_VIEW_MODEL =
            "app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditViewModel.kt"
    }
}
