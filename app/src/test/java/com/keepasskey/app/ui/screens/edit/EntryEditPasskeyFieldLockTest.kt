package com.keepasskey.app.ui.screens.edit

import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.testutil.stripCommentsOnly
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.PasskeyData
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * `ISSUE-P3-337` AC⑪①：导入后的条目在编辑页**不可手改**凭据材料。
 *
 * 规范 CXF §3.3.12.1 原文：`Passkey` 字典除 `username` / `userDisplayName` 外的成员
 * 「MUST NOT be user editable」。本仓此前把全部自定义字段（含 `KPEX_PASSKEY_*`）都渲染成
 * 可编辑行 ⇒ 该条**不成立**，本用例把锁钉死。三层证据：
 * ① 判据本身（哪些键锁、哪些键按规范豁免、非 passkey 键不误伤）；
 * ② **ViewModel 侧真挡下**：改键名 / 改值 / 切保护标记 / 删除 / 受保护明文上行四条通路
 *    对被锁字段一律无效，而对普通字段**仍然有效**（正向对照，防止「断言空转」）；
 * ③ 私钥明文**根本不进编辑态**：加载条目时不再按需解密被锁的受保护字段
 *   （改不动的东西没有解密进来的理由）。
 *
 * 另附一条静态源码守卫：编辑页的自定义字段分节必须按判据分叉渲染只读卡片——
 * JVM 起不了 Compose，「UI 不给入口」这件事只能这样钉（同 `TotpScanCameraResolutionGuardTest` 口径）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryEditPasskeyFieldLockTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    private fun field(idSuffix: String, key: String, value: String, protectedFlag: Boolean) =
        UiCustomField(id = "${ENTRY_ID}_$idSuffix", key = key, value = value, isProtected = protectedFlag)

    /** 一份「已导入通行密钥」的条目：四把凭据材料 + 两枚按规范豁免的展示字段 + 一个普通字段。 */
    private suspend fun TestScope.viewModelWithPasskeyEntry(): EntryEditViewModel {
        val repository = FakeVaultRepository().apply {
            saveEntry(
                UiVaultEntry(
                    id = ENTRY_ID,
                    title = "已导入的条目",
                    username = "johndoe",
                    url = "https://webauthn.io",
                    isPasskey = true,
                    customFields = listOf(
                        field(PasskeyData.FIELD_RP_ID, PasskeyData.FIELD_RP_ID, "webauthn.io", false),
                        field(PasskeyData.FIELD_CREDENTIAL_ID, PasskeyData.FIELD_CREDENTIAL_ID, CRED_ID, true),
                        field(PasskeyData.FIELD_USER_HANDLE, PasskeyData.FIELD_USER_HANDLE, USER_HANDLE, true),
                        field(PasskeyData.FIELD_PRIVATE_KEY, PasskeyData.FIELD_PRIVATE_KEY, PEM_MATERIAL, true),
                        field(PasskeyData.FIELD_SIGN_COUNT, PasskeyData.FIELD_SIGN_COUNT, "1048576", false),
                        field(PasskeyData.FIELD_USER_NAME, PasskeyData.FIELD_USER_NAME, "johndoe", false),
                        field(PasskeyData.FIELD_USER_DISPLAY_NAME, PasskeyData.FIELD_USER_DISPLAY_NAME, "John Doe", false),
                        field("plain_note", "plain_note", "note", false),
                        field("plain_secret", "plain_secret", SECRET_NOTE_MATERIAL, true)
                    )
                ),
                null,
                null,
                emptyMap()
            )
        }
        val viewModel = EntryEditViewModel(
            SavedStateHandle(mapOf("entryId" to ENTRY_ID)),
            repository
        )
        MainDispatcherGuard.track(viewModel)
        testScheduler.runCurrent()
        assertEquals(
            "前提：条目须已载入表单（否则下面的断言全在空转）",
            ENTRY_ID,
            viewModel.uiState.value.entryId
        )
        return viewModel
    }

    @Test
    fun `一 判据锁凭据材料 按规范豁免两个展示字段 不误伤普通字段`() {
        for (key in listOf(
            PasskeyData.FIELD_RP_ID,
            PasskeyData.FIELD_CREDENTIAL_ID,
            PasskeyData.FIELD_USER_HANDLE,
            PasskeyData.FIELD_PRIVATE_KEY,
            PasskeyData.FIELD_ALGORITHM,
            PasskeyData.FIELD_PUBLIC_KEY,
            PasskeyData.FIELD_SIGN_COUNT,
            PasskeyData.KPEX_FIELD_PRF,
            PasskeyData.FIELD_PRF_NO_UV,
            PasskeyData.KPEX_FIELD_FLAG_BE,
            PasskeyData.KPEX_FIELD_FLAG_BS,
            PasskeyData.FIELD_CREATED_AT,
            PasskeyData.LEGACY_FIELD_RP_ID,
            PasskeyData.LEGACY_FIELD_CREDENTIAL_ID,
            PasskeyData.LEGACY_FIELD_PRIVATE_KEY,
            PasskeyData.LEGACY_FIELD_USER_HANDLE
        )) {
            assertTrue("$key 必须锁（§3.3.12.1 参与仪式或属凭据材料）", isLockedPasskeyFieldKey(key))
        }
        for (key in listOf(
            PasskeyData.FIELD_USER_NAME,
            PasskeyData.LEGACY_FIELD_USER_NAME,
            PasskeyData.FIELD_USER_DISPLAY_NAME
        )) {
            assertFalse("$key 是展示字段，规范明文豁免", isLockedPasskeyFieldKey(key))
        }
        for (key in listOf("plain_note", "totp_recovery", "")) {
            assertFalse("非 passkey 键不得被误伤：$key", isLockedPasskeyFieldKey(key))
        }
    }

    @Test
    fun `二 改键名 改值 切保护 删除四条通路对被锁字段一律无效`() = runTest {
        val viewModel = viewModelWithPasskeyEntry()
        val rpIdId = "${ENTRY_ID}_${PasskeyData.FIELD_RP_ID}"

        viewModel.updateCustomField(rpIdId, "KPEX_HACKED", "evil.example", false)
        val afterEdit = viewModel.uiState.value.customFields
        assertEquals(
            "rpId 的键名与值都必须原样留下（AC⑪①）",
            listOf(PasskeyData.FIELD_RP_ID to "webauthn.io"),
            afterEdit.filter { it.id == rpIdId }.map { it.key to it.value }
        )
        // 受保护材料走的是同一条通用编辑通路：把私钥字段「改成明文」也必须无效
        val pemId = "${ENTRY_ID}_${PasskeyData.FIELD_PRIVATE_KEY}"
        viewModel.updateCustomField(pemId, PasskeyData.FIELD_PRIVATE_KEY, "MUTATED", false)
        assertEquals(
            "私钥字段不许被改写",
            listOf(PEM_MATERIAL),
            viewModel.uiState.value.customFields.filter { it.id == pemId }.map { it.value }
        )

        viewModel.removeCustomField(rpIdId)
        assertTrue("被锁字段删不掉", viewModel.uiState.value.customFields.any { it.id == rpIdId })

        // 正向对照：普通字段的三条通路仍然管用（否则上面两句是「谁都改不动」的假绿）
        viewModel.updateCustomField("${ENTRY_ID}_plain_note", "renamed", "changed", false)
        val plain = viewModel.uiState.value.customFields.first { it.key == "renamed" }
        assertEquals("changed", plain.value)
        viewModel.removeCustomField(plain.id)
        assertFalse(viewModel.uiState.value.customFields.any { it.id == plain.id })
    }

    @Test
    fun `三 受保护明文上行对被锁字段拒收而对普通字段放行`() = runTest {
        val viewModel = viewModelWithPasskeyEntry()
        assertFalse(viewModel.uiState.value.isDirty)

        viewModel.updateProtectedFieldValue("${ENTRY_ID}_${PasskeyData.FIELD_CREDENTIAL_ID}", "MUTATED".toCharArray())
        assertFalse("被锁字段的受保护上行必须整条拒收（连「已改动」都不该标记）", viewModel.uiState.value.isDirty)

        viewModel.updateProtectedFieldValue("${ENTRY_ID}_plain_secret", "EDITED".toCharArray())
        assertTrue("普通受保护字段仍可编辑（正向对照）", viewModel.uiState.value.isDirty)
    }

    @Test
    fun `四 私钥与凭据标识明文不进编辑态 普通受保护字段照旧解密`() = runTest {
        val viewModel = viewModelWithPasskeyEntry()
        val loaded = viewModel.loadedProtectedFields.value

        for (key in listOf(
            PasskeyData.FIELD_CREDENTIAL_ID,
            PasskeyData.FIELD_USER_HANDLE,
            PasskeyData.FIELD_PRIVATE_KEY
        )) {
            assertFalse(
                "$key 属被锁材料，不该按需解密进编辑态（AC⑪① 的附带面：改不动就别把它物化到内存里）",
                loaded.containsKey("${ENTRY_ID}_$key")
            )
        }
        assertNotNull(
            "普通受保护字段仍须下发预填明文，否则本例只是「什么都没解密」的假绿",
            loaded["${ENTRY_ID}_plain_secret"]
        )
        assertEquals(
            SECRET_NOTE_MATERIAL,
            loaded.getValue("${ENTRY_ID}_plain_secret").concatToString()
        )
    }

    /** 静态守卫：编辑页分节必须按判据分叉，把被锁字段渲染成只读卡片。 */
    @Test
    fun `五 编辑页分节对被锁字段不发射任何可编辑控件`() {
        val source = stripCommentsOnly(File(repositoryRoot, LIST_SECTIONS).readText())
        assertTrue(
            "自定义字段分节必须按 isLockedPasskeyFieldKey 分叉渲染",
            source.contains("if (isLockedPasskeyFieldKey(field.key))")
        )
        assertTrue("分叉的另一支必须是只读卡片", source.contains("LockedPasskeyFieldCard(field)"))
        // 可编辑卡片只允许出现在「未被锁」那一支：出现两次以上就说明有旁路。
        // 正则按「行首缩进」取调用点，函数声明那行（`private fun CustomFieldEditCard(`）不在列。
        assertEquals(
            "可编辑卡片只应在判据的 else 分支被调用一次",
            1,
            Regex("^\\s+CustomFieldEditCard\\(", RegexOption.MULTILINE).findAll(source).count()
        )
    }

    private companion object {
        const val ENTRY_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val CRED_ID = "Y3JlZGVudGlhbElkRXhhbXBsZQ"
        const val USER_HANDLE = "cnEzaNHWcYK3coWZjvoaV1Hj9gnI12mKe2dL2HZVFlY"
        const val PEM_MATERIAL = "-----BEGIN PRIVATE KEY-----\nQUJD\n-----END PRIVATE KEY-----"
        const val SECRET_NOTE_MATERIAL = "ordinary-secret"
        const val LIST_SECTIONS = "app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditListSections.kt"

        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory && File(candidate, "core/src/main/java").isDirectory) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
