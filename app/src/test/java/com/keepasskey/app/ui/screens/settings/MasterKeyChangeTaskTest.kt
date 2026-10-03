package com.keepasskey.app.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.breach.BreachCheckCoordinator
import com.keepasskey.app.data.breach.BreachRangeClient
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ChangeKeyFileIntent
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.sync.PeriodicSyncScheduler
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult
import com.keepasskey.app.ui.screens.unlock.RememberedKeyFile
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ISSUE-P2-354 AC③`：更换主密钥任务移出 UI scope 后的忙态守卫与回执。
 *
 * 旧缺陷形态：提交后立即 onDismiss、任务跑在 `rememberCoroutineScope`（切 Tab 即取消）、
 * 可重复打开对话框重复提交。现任务挂 `SettingsViewModel.viewModelScope`，忙态与回执
 * 都住在 ViewModel——本类判据：
 * 1. 提交后 uiState 出现 `isChangingMasterKey = true`（对话框据此保持进度且禁提交/禁关闭）；
 * 2. 忙态中的**第二次提交被同步拒绝**且入参当场清零（数组所有权已移交）；
 * 3. 完成后忙态回落 + 成功回执落位 + 首个入参清零；
 * 4. 回执经 clearMasterKeyChangeFeedback 一次性清除。
 *
 * 仓库用门闩挂起，制造**真实的在途窗口**——否则任务瞬时完成，忙态断言会退化成
 * 「读到的永远是终态」的空转断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MasterKeyChangeTaskTest {

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
    fun `换密忙态：在途期间投影 busy、第二次提交被拒、完成后回执并清零`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeVaultRepository()
        val repository = object : VaultRepository by fake {
            override suspend fun changeMasterPassword(
                newPassword: CharArray,
                keyFileIntent: ChangeKeyFileIntent
            ): KdbxResult<Unit> {
                // 门闩挂起：把「任务在途」窗口拉长到断言可稳定观察
                gate.await()
                return fake.changeMasterPassword(newPassword)
            }
        }
        val viewModel = buildViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        val first = "Master-Key-Fake-1".toCharArray()
        viewModel.masterKeyChangeController.submit(first)
        // 调度到 viewModelScope：任务启动并在门闩上挂起，busy 投影进 uiState
        testScheduler.runCurrent()
        assertTrue(
            "在途期间 uiState 必须呈现 isChangingMasterKey（对话框据此保持进度且禁重复提交）",
            viewModel.uiState.value.isChangingMasterKey
        )

        // 忙态中的第二次提交：同步拒绝 + 入参当场清零（所有权自调用起移交 VM）
        val second = "Master-Key-Fake-2".toCharArray()
        viewModel.masterKeyChangeController.submit(second)
        assertTrue("忙路径必须同步清零被拒入参", second.all { it == '0' })
        assertFalse(
            "在途入参不得在调用点被预清零（清零属任务的 finally）",
            first.all { it == '0' }
        )

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertFalse("完成后忙态必须回落", viewModel.uiState.value.isChangingMasterKey)
        assertEquals(
            "成功回执必须落位（既有 Snackbar 路径据此展示）",
            R.string.set_master_key_updated,
            viewModel.uiState.value.masterKeyChangeFeedback?.resId
        )
        assertTrue("任务完成后首个入参必须清零", first.all { it == '0' })

        viewModel.clearMasterKeyChangeFeedback()
        testScheduler.runCurrent()
        assertNull("回执展示后必须可清除（一次性消息语义）", viewModel.uiState.value.masterKeyChangeFeedback)
    }

    // ── ISSUE-P2-398：改密成功后的重封印挂点 ────────────────────────────

    @Test
    fun `换密成功后触发重封印且宿主与新密码快照透传`() = runTest {
        val repository = FakeVaultRepository()
        val resealCalls = mutableListOf<Pair<FragmentActivity?, CharArray>>()
        var intactAtReseal = false
        val controller = SettingsMasterKeyChangeController(repository, this, resealAfterChange = { activity, chars ->
            intactAtReseal = chars?.all { it != '0' } == true
            resealCalls.add(activity to chars!!.copyOf())
        })

        val password = "Reseal-New-Pass#1".toCharArray()
        controller.submit(password, ChangeKeyFileIntent.Keep, HOST_ACTIVITY)
        advanceUntilIdle()

        assertEquals("成功路径必须恰好触发一次重封印", 1, resealCalls.size)
        assertEquals("宿主 Activity 必须透传（BiometricPrompt 依赖）", HOST_ACTIVITY, resealCalls.single().first)
        assertArrayEquals("重封印必须拿到新密码内容", "Reseal-New-Pass#1".toCharArray(), resealCalls.single().second)
        assertTrue("重封印窗口内入参尚未被 finally 清零（唯一能以新密码重封印的时机）", intactAtReseal)
        assertTrue("任务收尾必须清零入参", password.all { it == '0' })
    }

    @Test
    fun `换密失败不触发重封印`() = runTest {
        val fake = FakeVaultRepository()
        val repository = object : VaultRepository by fake {
            override suspend fun changeMasterPassword(
                newPassword: CharArray,
                keyFileIntent: ChangeKeyFileIntent
            ): KdbxResult<Unit> =
                KdbxResult.Failure(IllegalStateException("boom"), "改密失败")
        }
        var resealCalls = 0
        val controller = SettingsMasterKeyChangeController(repository, this, resealAfterChange = { _, _ ->
            resealCalls++
        })

        val password = "Should-Not-Reseal".toCharArray()
        controller.submit(password, ChangeKeyFileIntent.Keep, HOST_ACTIVITY)
        advanceUntilIdle()

        assertEquals("失败路径不得触发重封印（封印载荷只承载当前有效密码）", 0, resealCalls)
        assertTrue(password.all { it == '0' })
    }

    // ── ISSUE-P3-428：密钥文件第二因子三态透传与借用字节清零 ────────────

    @Test
    fun `换密三态透传：Use字节finally清零、Remove与缺省Keep原样上行`() = runTest {
        val received = mutableListOf<ChangeKeyFileIntent>()
        val repository = object : VaultRepository by FakeVaultRepository() {
            override suspend fun changeMasterPassword(
                newPassword: CharArray,
                keyFileIntent: ChangeKeyFileIntent
            ): KdbxResult<Unit> {
                received.add(keyFileIntent)
                return KdbxResult.Success(Unit)
            }
        }
        val controller = SettingsMasterKeyChangeController(repository, this)

        val bytes = ByteArray(8) { 0x11 }
        controller.submit("Tri-State#1".toCharArray(), ChangeKeyFileIntent.Use(bytes))
        advanceUntilIdle()
        assertTrue("Use 意图必须原样上行", received.single() is ChangeKeyFileIntent.Use)
        assertTrue("任务收尾必须清零借用密钥文件字节", bytes.all { it == 0.toByte() })

        controller.submit("Tri-State#2".toCharArray(), ChangeKeyFileIntent.Remove)
        advanceUntilIdle()
        assertEquals("Remove 意图必须原样上行", ChangeKeyFileIntent.Remove, received.last())

        controller.submit("Tri-State#3".toCharArray())
        advanceUntilIdle()
        assertEquals("缺省必须为 Keep（沿用既有单参语义）", ChangeKeyFileIntent.Keep, received.last())
    }

    // ── ISSUE-P3-430：留空密码 = 仅改绑密钥文件的通道分派 ────────────────

    @Test
    fun `留空密码走changeKeyFileOnly且重封印入参为null、空提交被拒`() = runTest {
        val changeMasterCalls = mutableListOf<ChangeKeyFileIntent>()
        val changeKeyFileOnlyCalls = mutableListOf<ChangeKeyFileIntent>()
        val resealPasswords = mutableListOf<CharArray?>()
        val repository = object : VaultRepository by FakeVaultRepository() {
            override suspend fun changeMasterPassword(
                newPassword: CharArray,
                keyFileIntent: ChangeKeyFileIntent
            ): KdbxResult<Unit> {
                changeMasterCalls.add(keyFileIntent)
                return KdbxResult.Success(Unit)
            }

            override suspend fun changeKeyFileOnly(keyFileIntent: ChangeKeyFileIntent): KdbxResult<Unit> {
                changeKeyFileOnlyCalls.add(keyFileIntent)
                return KdbxResult.Success(Unit)
            }
        }
        val controller = SettingsMasterKeyChangeController(repository, this, resealAfterChange = { _, pwd ->
            resealPasswords.add(pwd?.copyOf())
        })

        // 留空密码 + Use：必须走 changeKeyFileOnly，重封印收到 null（密码分量未变）
        val bytes = ByteArray(4) { 0x22 }
        controller.submit(CharArray(0), ChangeKeyFileIntent.Use(bytes))
        advanceUntilIdle()
        assertTrue("留空密码必须走仅改绑通道", changeMasterCalls.isEmpty())
        assertTrue(changeKeyFileOnlyCalls.single() is ChangeKeyFileIntent.Use)
        assertTrue("借用字节必须清零", bytes.all { it == 0.toByte() })
        assertEquals("重封印必须以 null 密码触发（取会话快照）", 1, resealPasswords.size)
        assertNull(resealPasswords.single())

        // 留空密码 + Keep：无任何改动，必须被同步拒绝（不进任务、不产生回执）
        controller.submit(CharArray(0), ChangeKeyFileIntent.Keep)
        advanceUntilIdle()
        assertEquals("空提交不得进入任何通道", 1, changeKeyFileOnlyCalls.size)
    }

    // ── ISSUE-P3-434：改绑/解绑成功后同步「记住的密钥文件位置」 ────────────

    @Test
    fun `改绑成功后记忆更新为新来源、解绑成功后记忆清除`() = runTest {
        val access = FakeKeyFileAccess().apply {
            remembered = RememberedKeyFile("old://keyfile", "old.pem")
        }
        val controller = SettingsMasterKeyChangeController(
            FakeVaultRepository(), this, keyFileAccess = access
        )

        val bytes = ByteArray(4) { 0x33 }
        controller.submit(CharArray(0), ChangeKeyFileIntent.Use(bytes, "new://keyfile", "new.pem"))
        advanceUntilIdle()
        assertEquals(
            "改绑成功后记忆必须指向新来源（冷启动恢复与指纹现读据此读新文件）",
            RememberedKeyFile("new://keyfile", "new.pem"),
            access.remembered
        )
        assertEquals(
            "必须为新来源申请持久化读授权（否则下次冷启动授权校验即降级）",
            listOf("new://keyfile"),
            access.persistRequests
        )
        assertTrue("借用字节必须清零", bytes.all { it == 0.toByte() })

        controller.submit(CharArray(0), ChangeKeyFileIntent.Remove)
        advanceUntilIdle()
        assertNull("解绑成功后记忆必须清除（改后库无第二因子）", access.remembered)
    }

    @Test
    fun `Keep不改密钥文件时记忆原样保留`() = runTest {
        val old = RememberedKeyFile("old://keyfile", "old.pem")
        val access = FakeKeyFileAccess().apply { remembered = old }
        val controller = SettingsMasterKeyChangeController(
            FakeVaultRepository(), this, keyFileAccess = access
        )

        controller.submit("Keep-Cred#1".toCharArray(), ChangeKeyFileIntent.Keep)
        advanceUntilIdle()
        assertEquals("Keep 意图不触碰记忆记录（密钥文件未变，记录仍有效）", old, access.remembered)
        assertTrue("Keep 不得扩大持久授权面", access.persistRequests.isEmpty())
    }

    @Test
    fun `改绑失败不动记忆，偏好关闭授权失败与Uri缺失一律清旧记录`() = runTest {
        val failing = object : VaultRepository by FakeVaultRepository() {
            override suspend fun changeKeyFileOnly(
                keyFileIntent: ChangeKeyFileIntent
            ): KdbxResult<Unit> = KdbxResult.Failure(IllegalStateException("boom"), "仅改绑失败")
        }
        val intact = FakeKeyFileAccess().apply {
            remembered = RememberedKeyFile("old://keyfile", "old.pem")
        }
        SettingsMasterKeyChangeController(failing, this, keyFileAccess = intact)
            .submit(CharArray(0), ChangeKeyFileIntent.Use(ByteArray(4), "new://keyfile", "new.pem"))
        advanceUntilIdle()
        assertEquals(
            "改密失败路径不得触碰记忆记录（改绑并未发生）",
            RememberedKeyFile("old://keyfile", "old.pem"),
            intact.remembered
        )
        assertTrue("失败路径不得申请持久化授权", intact.persistRequests.isEmpty())

        // 偏好关闭：无论意图为何，旧记录一律清除（不留密钥文件元数据）
        val disabled = FakeKeyFileAccess(rememberEnabled = false).apply {
            remembered = RememberedKeyFile("old://keyfile", "old.pem")
        }
        SettingsMasterKeyChangeController(FakeVaultRepository(), this, keyFileAccess = disabled)
            .submit(CharArray(0), ChangeKeyFileIntent.Use(ByteArray(4), "new://keyfile", "new.pem"))
        advanceUntilIdle()
        assertNull("偏好关闭必须清除旧记录（与解锁页 rememberKeyFileOnSuccess 同口径）", disabled.remembered)

        // 持久授权不可得：清旧记录（新来源无法在冷启动恢复，旧来源指向已解绑文件）
        val noPermission = FakeKeyFileAccess(persistPermission = false).apply {
            remembered = RememberedKeyFile("old://keyfile", "old.pem")
        }
        SettingsMasterKeyChangeController(FakeVaultRepository(), this, keyFileAccess = noPermission)
            .submit(CharArray(0), ChangeKeyFileIntent.Use(ByteArray(4), "new://keyfile", "new.pem"))
        advanceUntilIdle()
        assertNull("授权不可得必须清旧记录，绝不留陈旧指向", noPermission.remembered)

        // Uri 缺失（无记忆语义的构造点）：同样清旧记录
        val blankUri = FakeKeyFileAccess().apply {
            remembered = RememberedKeyFile("old://keyfile", "old.pem")
        }
        SettingsMasterKeyChangeController(FakeVaultRepository(), this, keyFileAccess = blankUri)
            .submit(CharArray(0), ChangeKeyFileIntent.Use(ByteArray(4)))
        advanceUntilIdle()
        assertNull("来源 Uri 缺失必须清旧记录", blankUri.remembered)
    }

    private class FakeKeyFileAccess(
        private val rememberEnabled: Boolean = true,
        private val persistPermission: Boolean = true
    ) : KeyFileAccess {
        /** 单槽即可：本用例只涉一个活动库（FakeVaultRepository 的 db_personal） */
        var remembered: RememberedKeyFile? = null
        val persistRequests = mutableListOf<String>()

        override suspend fun isRememberEnabled(): Boolean = rememberEnabled
        override suspend fun read(uri: String): KeyFileReadResult = KeyFileReadResult.Unreadable
        override suspend fun persistReadPermission(uri: String): Boolean {
            persistRequests.add(uri)
            return persistPermission
        }
        override suspend fun hasPersistedReadPermission(uri: String): Boolean = persistPermission
        override suspend fun loadRemembered(databaseId: String): RememberedKeyFile? = remembered
        override suspend fun loadLegacyGlobalHint(): RememberedKeyFile? = null
        override suspend fun remember(databaseId: String, uri: String, displayName: String) {
            remembered = RememberedKeyFile(uri, displayName)
        }
        override suspend fun forget(databaseId: String?) {
            remembered = null
        }
    }

    private suspend fun buildViewModel(repository: VaultRepository): SettingsViewModel {
        val context = InMemorySharedPreferences().context()
        val settingsRepository = FakeSettingsRepository().apply {
            // 关闭冷启动同步：本用例只验证换密任务态，不引入无关的联网动作
            setSyncOnColdStart(false)
        }
        val credentialsStore = SyncCredentialsStore(InMemorySharedPreferences().context(), null)
        val coordinator = SyncCoordinator(
            context,
            DatabaseSession(),
            credentialsStore,
            DebugLogBuffer(),
            TEST_STRINGS
        )
        return SettingsViewModel(
            settingsRepository,
            repository,
            credentialsStore,
            coordinator,
            DebugLogBuffer(),
            ExtendedSettingsStore(null),
            PeriodicSyncScheduler(context, ExtendedSettingsStore(null)),
            com.keepasskey.app.data.repository.AutofillBlocklistStore(null),
            com.keepasskey.app.autofill.AutofillSaveBlocklistStore(null),
            com.keepasskey.app.autofill.AutofillFieldBlocklistStore(null),
            BreachCheckCoordinator(NoOpBreachRangeClient),
            databaseSession = DatabaseSession(),
            stringsProvider = TEST_STRINGS
        ).also { MainDispatcherGuard.track(it) }
    }

    private object NoOpBreachRangeClient : BreachRangeClient {
        override suspend fun queryRange(prefix: String): Set<String> = emptySet()
    }

    private companion object {
        val TEST_STRINGS = StringsProvider { _, _ -> "" }

        /** JVM 可构造的宿主替身：仅作引用透传判据，不触达真实 BiometricPrompt */
        val HOST_ACTIVITY: FragmentActivity? = null
    }
}
