package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.R
import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.core.model.PasskeyData
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ISSUE-P3-337` 第 4 片（下）Q1：编辑页「扫码 / 相册导入通行密钥」的接线。
 *
 * 锁五件事：
 * ① **分流不回退**：`otpauth:` 与任何非 JSON 文本仍走本页原有的「回填种子」通路（AC⑤
 *    「TOTP 分支一字不改」在本页按「非通行密钥形态即旧行为」落地）；只有 JSON 形态进导入会话；
 * ② **确认在先**：解析成功只挂草案，不写库（AC⑩）；
 * ③ **取消即擦**：草案里的私钥字符数组在取消路径被填零（AC③）；
 * ④ **挂当前条目**：确认后按 entryId 替换，条目自身的其它自定义字段一条不丢（口径 5）；
 * ⑤ **未保存即拒绝**：新建表单与有未保存改动两种情形都不写库、并如实提示（不静默吞掉用户刚打的字）。
 *
 * 夹具用**运行时生成的真 PKCS#8**（`secp256r1` ⇒ DER 里带 prime256v1 OID ⇒ 解析器判 ES256），
 * 而不是手抄一段 Base64：抄来的串一旦与解析器的判据脱钩，本用例就会以「解析失败」静默空转。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryEditPasskeyImportTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    /** 规范 §3.3.12 的裸 `Passkey` 字典；缺 `username` / `userDisplayName` 以顺带覆盖容错补齐分支。 */
    private fun cxfPayload(rpId: String, credentialId: String): String {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val der = generator.generateKeyPair().private.encoded
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(der)
        return """{"type":"passkey","credentialId":"$credentialId","rpId":"$rpId",""" +
            """"userHandle":"aGFuZGxl","key":"$key"}"""
    }

    private suspend fun repositoryWithPasskeyEntry(): FakeVaultRepository =
        FakeVaultRepository().apply {
            saveEntry(
                UiVaultEntry(
                    id = ENTRY_ID,
                    title = "旧站条目",
                    username = "old-user",
                    url = "https://old.example",
                    isPasskey = true,
                    customFields = listOf(
                        UiCustomField(
                            id = "${ENTRY_ID}_${PasskeyData.FIELD_RP_ID}",
                            key = PasskeyData.FIELD_RP_ID,
                            value = "old.example",
                            isProtected = false
                        ),
                        UiCustomField(
                            id = "${ENTRY_ID}_${PasskeyData.FIELD_CREDENTIAL_ID}",
                            key = PasskeyData.FIELD_CREDENTIAL_ID,
                            value = OLD_CREDENTIAL_ID,
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

    @Test
    fun `一 otpauth 与非 JSON 文本仍按原通路回填种子 不进导入会话`() = runTest {
        val repository = repositoryWithPasskeyEntry()
        val viewModel = viewModelFor(repository)
        testScheduler.runCurrent()

        viewModel.onQrPayloadDecoded("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP".toCharArray())
        assertEquals(
            "otpauth 形态须原样回填 TOTP 种子",
            "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP",
            viewModel.loadedTotpSecret.value?.concatToString()
        )
        assertNull(viewModel.pendingPasskeyImport.value)

        // 裸 Base32（分类为 Unknown）：编辑页历史上就接受它（手填种子的宽容口径），不得回归为拒绝
        viewModel.onQrPayloadDecoded("JBSWY3DPEHPK3PXP".toCharArray())
        assertEquals("JBSWY3DPEHPK3PXP", viewModel.loadedTotpSecret.value?.concatToString())
        assertNull(viewModel.pendingPasskeyImport.value)
        assertTrue("TOTP 通路不得留下任何待确认草案", repository.lastSavedPasskeyByEntry.isEmpty())
    }

    @Test
    fun `二 通行密钥载荷只挂草案不写库 取消后私钥字符已填零`() = runTest {
        val repository = repositoryWithPasskeyEntry()
        val viewModel = viewModelFor(repository)
        testScheduler.runCurrent()

        viewModel.onQrPayloadDecoded(cxfPayload("webauthn.io", NEW_CREDENTIAL_ID).toCharArray())
        val draft = viewModel.pendingPasskeyImport.value
        assertNotNull("解析成功须挂出待确认草案", draft)
        assertTrue("确认前不得写库", repository.lastSavedPasskeyByEntry.isEmpty())

        viewModel.dismissPasskeyImport()
        assertNull(viewModel.pendingPasskeyImport.value)
        assertTrue(
            "取消路径必须把私钥字符数组填零（AC③），不得只丢引用",
            draft!!.credential.privateKeyPemChars.all { it == ERASED_CHAR }
        )
        assertTrue("取消后仍不得写库", repository.lastSavedPasskeyByEntry.isEmpty())
    }

    @Test
    fun `三 确认后按 entryId 替换 条目其它字段与库内新状态一并回显`() = runTest {
        val repository = repositoryWithPasskeyEntry()
        val viewModel = viewModelFor(repository)
        testScheduler.runCurrent()

        viewModel.onQrPayloadDecoded(cxfPayload("webauthn.io", NEW_CREDENTIAL_ID).toCharArray())
        viewModel.confirmPasskeyImport()
        // 确认体里嵌着「替换 → 重载」两次 launch，须推进到底
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        val stored = requireNotNull(repository.getEntry(ENTRY_ID).first()) { "条目须仍在库内" }
        val fields = stored.customFields
        assertEquals(
            "替换须落在**正在编辑的这条**（id 定位），不是按 rpId 另找一条",
            NEW_CREDENTIAL_ID,
            fields.first { it.key == PasskeyData.FIELD_CREDENTIAL_ID }.value
        )
        assertEquals("webauthn.io", fields.first { it.key == PasskeyData.FIELD_RP_ID }.value)
        assertEquals(
            "非 passkey 字段一条都不许丢（口径 5 的「整体换新只覆盖 schema 键」）",
            "note value",
            fields.first { it.key == "note_key" }.value
        )
        assertTrue(
            "旧凭据不得残留",
            fields.none { it.value.contains(OLD_CREDENTIAL_ID) }
        )
        assertNull("确认后草案必须已交出并置空", viewModel.pendingPasskeyImport.value)
        // 重载证据：表单里的 rpId 字段已是新值，且表单回到「无未保存改动」
        assertEquals(
            "webauthn.io",
            viewModel.uiState.value.customFields.first { it.key == PasskeyData.FIELD_RP_ID }.value
        )
        assertEquals(R.string.passkey_import_replaced, noticeRes(viewModel))
    }

    @Test
    fun `四 新建表单与有未保存改动时拒绝导入 不写库且如实提示`() = runTest {
        // ① 新建表单：没有「当前条目」可挂
        val blankRepository = FakeVaultRepository()
        val blankViewModel = viewModelFor(blankRepository, entryId = null)
        blankViewModel.onQrPayloadDecoded(cxfPayload("webauthn.io", NEW_CREDENTIAL_ID).toCharArray())
        assertNotNull(blankViewModel.pendingPasskeyImport.value)
        blankViewModel.confirmPasskeyImport()
        testScheduler.runCurrent()
        assertTrue("新建表单不得凭空落库", blankRepository.lastSavedPasskeyByEntry.isEmpty())
        assertEquals(R.string.edit_passkey_import_requires_saved, noticeRes(blankViewModel))

        // ② 已保存但表单有未保存改动：替换 + 重载会静默吃掉用户刚打的字 ⇒ 直接拒绝
        val repository = repositoryWithPasskeyEntry()
        val viewModel = viewModelFor(repository)
        testScheduler.runCurrent()
        viewModel.onTitleChange("用户刚改的标题")
        viewModel.onQrPayloadDecoded(cxfPayload("webauthn.io", NEW_CREDENTIAL_ID).toCharArray())
        viewModel.confirmPasskeyImport()
        // 确认体里嵌着「替换 → 重载」两次 launch，须推进到底
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()
        assertTrue("有未保存改动时不得写库", repository.lastSavedPasskeyByEntry.isEmpty())
        assertEquals(R.string.edit_passkey_import_requires_saved, noticeRes(viewModel))
        assertNull("草案同样必须被擦除并置空", viewModel.pendingPasskeyImport.value)
        val stored = requireNotNull(repository.getEntry(ENTRY_ID).first())
        assertTrue(
            "库内旧凭据不许被动过",
            stored.customFields.any { it.value.contains(OLD_CREDENTIAL_ID) }
        )
    }

    @Test
    fun `五 只读会话在解析这一步就硬拒绝`() = runTest {
        val repository = repositoryWithPasskeyEntry().apply { sessionReadOnly = true }
        val viewModel = viewModelFor(repository)
        testScheduler.runCurrent()

        viewModel.onQrPayloadDecoded(cxfPayload("webauthn.io", NEW_CREDENTIAL_ID).toCharArray())
        assertNull("只读会话不得挂出草案", viewModel.pendingPasskeyImport.value)
        assertEquals(R.string.readonly_save_rejected, noticeRes(viewModel))
    }

    /** 取最近一次上浮的静态文案资源 id（编辑页的提示都经 `UiState.userMessage`）。 */
    private fun noticeRes(viewModel: EntryEditViewModel): Int? =
        viewModel.uiState.value.userMessage?.resId

    private companion object {
        const val ENTRY_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OLD_CREDENTIAL_ID = "T0xEX0NSRURFTlRJQUxJRA"
        const val NEW_CREDENTIAL_ID = "TkVXX0NSRURFTlRJQUxJRA"
        const val ERASED_CHAR = '0'
    }
}
