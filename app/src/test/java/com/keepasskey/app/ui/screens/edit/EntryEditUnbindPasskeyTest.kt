package com.keepasskey.app.ui.screens.edit

import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.PasskeyData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ISSUE-P3-342`：编辑页「解除绑定」必须是一条**真实的写通路**。
 *
 * ## 锁定的缺陷
 *
 * 旧实现 `onTogglePasskey` 只做 `copy(isPasskey = !isPasskey, isDirty = true)`，而 `isPasskey`
 * **从不落盘**（既不在 `VaultEntryWriteCoordinator.saveMergedEntry` 的字段清单里，也不在
 * `VaultEntryMapper.mapUiEntryToKdbx` 里），读路径反过来按「凭据字段是否存在」**重算**它
 * （`VaultEntryMapper.kt:91`）。⇒ 用户点「解除」再点保存，磁盘上什么都没变，
 * **该凭据仍会被 Credential Manager 列进候选并签名**（`KeePasskeyCredentialProviderService`
 * 的 `findMatchingEntries` 正按 `entry.isPasskey` 判）。那是一个会骗人的安全控件。
 *
 * ## 判据的鉴别力
 *
 * 每条「已解除」断言都配一条**非凭据字段必须仍在**的对照 —— 否则实现退化成"把整条条目删掉"
 * 也能让本类全绿，而那是另一种事故。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryEditUnbindPasskeyTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    private suspend fun repositoryWithBoundPasskey(readOnly: Boolean = false): FakeVaultRepository =
        FakeVaultRepository().apply {
            sessionReadOnly = readOnly
            saveEntry(
                UiVaultEntry(
                    id = ENTRY_ID,
                    title = "已绑定条目",
                    username = "alice",
                    url = "https://webauthn.io",
                    isPasskey = true,
                    customFields = listOf(
                        UiCustomField(
                            id = "${ENTRY_ID}_${PasskeyData.FIELD_RP_ID}",
                            key = PasskeyData.FIELD_RP_ID,
                            value = "webauthn.io",
                            isProtected = false
                        ),
                        UiCustomField(
                            id = "${ENTRY_ID}_${PasskeyData.FIELD_CREDENTIAL_ID}",
                            key = PasskeyData.FIELD_CREDENTIAL_ID,
                            value = CREDENTIAL_ID,
                            isProtected = true
                        ),
                        UiCustomField(
                            id = "${ENTRY_ID}_${PasskeyData.FIELD_PRIVATE_KEY}",
                            key = PasskeyData.FIELD_PRIVATE_KEY,
                            value = "fake-private-key-material",
                            isProtected = true
                        ),
                        UiCustomField(
                            id = "${ENTRY_ID}_note_key",
                            key = "note_key",
                            value = "note value",
                            isProtected = false
                        )
                    )
                ),
                null,
                null,
                emptyMap()
            )
        }

    private fun viewModelFor(repository: FakeVaultRepository, entryId: String? = ENTRY_ID) =
        EntryEditViewModel(
            SavedStateHandle(if (entryId == null) mapOf("groupId" to "group_work") else mapOf("entryId" to entryId)),
            repository
        ).also { MainDispatcherGuard.track(it) }

    private fun noticeRes(viewModel: EntryEditViewModel): Int? = viewModel.uiState.value.userMessage?.resId

    @Test
    fun `一 请求解除只挂确认态 取消后凭据一条不少`() = runTest {
        val repository = repositoryWithBoundPasskey()
        val viewModel = viewModelFor(repository)
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        viewModel.requestUnbindPasskey()
        assertTrue("请求解除须挂出确认对话框（不可逆写操作不得一点即生效）", viewModel.showUnbindPasskeyConfirm.value)

        viewModel.dismissUnbindPasskey()
        assertFalse("取消必须关掉对话框", viewModel.showUnbindPasskeyConfirm.value)

        val fields = requireNotNull(repository.getEntry(ENTRY_ID).first()).customFields
        assertTrue(
            "取消路径不得写库：凭据字段须原样在场",
            fields.any { it.key == PasskeyData.FIELD_PRIVATE_KEY }
        )
        assertNotEquals(R.string.edit_passkey_unbind_done, noticeRes(viewModel))
    }

    @Test
    fun `二 确认后凭据字段被摘除而非凭据字段一条不丢`() = runTest {
        val repository = repositoryWithBoundPasskey()
        val viewModel = viewModelFor(repository)
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isPasskey)

        viewModel.requestUnbindPasskey()
        viewModel.confirmUnbindPasskey()
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        val stored = requireNotNull(repository.getEntry(ENTRY_ID).first()) { "条目本身必须仍在库内" }
        assertTrue(
            "全部凭据 schema 键都须被摘除（不得只删私钥留下 rpId）",
            stored.customFields.none { PasskeyData.isPasskeyFieldKey(it.key) }
        )
        assertEquals(
            "非凭据字段必须留下（正向对照：实现退化成删整条条目时这里就红）",
            listOf("note_key"),
            stored.customFields.map { it.key }
        )
        assertEquals("标题不得被连带清空", "已绑定条目", stored.title)
        assertFalse("重载后表单须如实呈现未绑定态", viewModel.uiState.value.isPasskey)
        assertEquals(R.string.edit_passkey_unbind_done, noticeRes(viewModel))
        assertFalse("动作完成后对话框必须已关", viewModel.showUnbindPasskeyConfirm.value)
    }

    @Test
    fun `三 只读会话与未落库条目都拒绝且不弹空对话框`() = runTest {
        // ① 只读会话
        val readOnlyRepository = repositoryWithBoundPasskey(readOnly = true)
        val readOnlyViewModel = viewModelFor(readOnlyRepository)
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()
        readOnlyViewModel.requestUnbindPasskey()
        assertFalse("只读会话不得弹确认框（弹了也只能失败）", readOnlyViewModel.showUnbindPasskeyConfirm.value)
        assertEquals(R.string.readonly_save_rejected, noticeRes(readOnlyViewModel))
        assertTrue(
            "只读拒绝路径不得有任何写入",
            requireNotNull(readOnlyRepository.getEntry(ENTRY_ID).first())
                .customFields.any { it.key == PasskeyData.FIELD_PRIVATE_KEY }
        )

        // ② 新建表单：没有「当前条目」可解除
        val blankRepository = FakeVaultRepository()
        val blankViewModel = viewModelFor(blankRepository, entryId = null)
        blankViewModel.requestUnbindPasskey()
        assertFalse(blankViewModel.showUnbindPasskeyConfirm.value)
    }

    @Test
    fun `四 未绑定态不请求解除 幂等且不误报成功`() = runTest {
        val repository = FakeVaultRepository().apply {
            saveEntry(
                UiVaultEntry(id = ENTRY_ID, title = "普通条目", username = "bob", url = "https://x.example"),
                null, null, emptyMap()
            )
        }
        val viewModel = viewModelFor(repository)
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isPasskey)
        viewModel.requestUnbindPasskey()
        assertFalse("未绑定态没有可解除的东西，不该挂确认框", viewModel.showUnbindPasskeyConfirm.value)
    }

    @Test
    fun `五 保存成功必须清脏位（导入闸门依赖它）`() = runTest {
        val repository = repositoryWithBoundPasskey()
        val viewModel = viewModelFor(repository)
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        viewModel.onTitleChange("改过的标题")
        assertTrue("编辑后应为脏态", viewModel.uiState.value.isDirty)

        viewModel.saveEntry()
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        // ISSUE-P3-342 不变量：`EntryEditPasskeyImport` 以 hasUnsavedEdits()（＝ isDirty）作前置拒绝。
        // 今天不出事仅因 SaveSuccess 随即出页；一旦改成"保存后留在本页"，不清脏位就会让
        // 用户刚保存完却被提示「请先保存」——永久无法导入。
        assertFalse("保存成功后必须不再是脏态", viewModel.uiState.value.isDirty)
    }
}

private const val ENTRY_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private const val CREDENTIAL_ID = "Q1JFREVOVElBTElEXzEyMzQ"
