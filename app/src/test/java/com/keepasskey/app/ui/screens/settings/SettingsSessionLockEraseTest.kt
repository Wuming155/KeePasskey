package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.data.breach.BreachCheckCoordinator
import com.keepasskey.app.data.breach.BreachRangeClient
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.sync.PeriodicSyncScheduler
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * `ISSUE-P3-188` 剩余清单第 4 项第三段（§190）：`SettingsViewModel` 的「锁定即擦除」回调
 * （`SessionLockGuard(databaseSession) { 清三条同步凭据预填通道 }`）此前无宿主直调用例。
 *
 * 被锁定的通道是 ISSUE-P2-01 / Wave 15 立下的「CharArray 一次性预填」面：WebDAV 口令、
 * S3 AccessKey ID、S3 SecretKey——三条都只经 `restoreSyncCredentials()`（VM `init` 内）填充，
 * 所以用例必须**先构造出已封印落盘的凭据**，否则断言会退化成「本来就是 null」的空断言。
 *
 * 三层证据：
 * 1. 锁定后三条通道置空；
 * 2. 锁定前拿到的数组本体被填零（只丢引用不清内容不算擦除）；
 * 3. 三条通道各自的明文在锁定前确实等于预置值（前提非空，且能区分「通道接错」与「没擦干净」）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSessionLockEraseTest {

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

    /** 内存版可逆封印闭包：与 `SyncCredentialsStoreTest` 同法（XOR 恒等可逆），不触 AndroidKeyStore */
    private fun SyncCredentialsStore.useReversibleTestSealing() {
        customEncryptor = { plaintext ->
            Pair(TEST_IV, plaintext.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray())
        }
        customDecryptor = { _, cipher ->
            cipher.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray()
        }
    }

    @Test
    fun `会话锁定后云同步凭据的三条预填通道一并擦除`() = runTest {
        val session = DatabaseSession()
        val credentialsStore = SyncCredentialsStore(InMemorySharedPreferences().context(), null)
        credentialsStore.useReversibleTestSealing()
        assertTrue(
            "前提：WebDAV 凭据应能封印落盘（否则本用例退化为空断言）",
            credentialsStore.saveWebDavConfig(
                url = "https://dav.example.com/remote.php/webdav",
                username = "dav_admin",
                password = WEBDAV_FAKE_PASSWORD.toCharArray(),
                remotePath = "/keepass/vault.kdbx"
            )
        )
        assertTrue(
            "前提：S3 凭据应能封印落盘",
            credentialsStore.saveS3Config(
                endpoint = "https://s3.example.com",
                bucket = "vault",
                region = "auto",
                accessKey = S3_ACCESS_FAKE.toCharArray(),
                secretKey = S3_SECRET_FAKE.toCharArray(),
                objectKey = "/keepasskey/vault.kdbx"
            )
        )

        val viewModel = buildViewModel(session, credentialsStore)

        // 预填通道由 VM 的 init 恢复；跑完 init 里排队的动作再读
        runCurrent()
        val webdavBefore = viewModel.webdavPasswordPrefill.value
        val accessBefore = viewModel.s3AccessKeyPrefill.value
        val secretBefore = viewModel.s3SecretKeyPrefill.value
        assertEquals("前提：WebDAV 口令应已进预填通道", WEBDAV_FAKE_PASSWORD, webdavBefore?.let(::String))
        assertEquals("前提：S3 AccessKey 应已进预填通道", S3_ACCESS_FAKE, accessBefore?.let(::String))
        assertEquals("前提：S3 SecretKey 应已进预填通道", S3_SECRET_FAKE, secretBefore?.let(::String))
        assertNotNull(webdavBefore)
        assertNotNull(accessBefore)
        assertNotNull(secretBefore)

        session.lock()
        runCurrent()
        advanceUntilIdle()

        assertNull("锁定后 WebDAV 口令预填通道必须置空", viewModel.webdavPasswordPrefill.value)
        assertNull("锁定后 S3 AccessKey 预填通道必须置空", viewModel.s3AccessKeyPrefill.value)
        assertNull("锁定后 S3 SecretKey 预填通道必须置空", viewModel.s3SecretKeyPrefill.value)
        assertTrue("锁定必须把口令数组本体填零", webdavBefore!!.all { it == ERASED_CHAR })
        assertTrue("锁定必须把 AccessKey 数组本体填零", accessBefore!!.all { it == ERASED_CHAR })
        assertTrue("锁定必须把 SecretKey 数组本体填零", secretBefore!!.all { it == ERASED_CHAR })
    }

    private suspend fun buildViewModel(
        session: DatabaseSession,
        credentialsStore: SyncCredentialsStore
    ): SettingsViewModel {
        val context = InMemorySharedPreferences().context()
        val settingsRepository = FakeSettingsRepository().apply {
            // 关闭冷启动同步：本用例只验证锁定擦除，不引入无关的联网动作
            setSyncOnColdStart(false)
        }
        val coordinator = SyncCoordinator(
            context,
            session,
            credentialsStore,
            DebugLogBuffer(),
            TEST_STRINGS
        )
        return SettingsViewModel(
            settingsRepository,
            FakeVaultRepository(),
            credentialsStore,
            coordinator,
            DebugLogBuffer(),
            ExtendedSettingsStore(null),
            PeriodicSyncScheduler(context, ExtendedSettingsStore(null)),
            com.keepasskey.app.data.repository.AutofillBlocklistStore(null),
            com.keepasskey.app.autofill.AutofillSaveBlocklistStore(null),
            com.keepasskey.app.autofill.AutofillFieldBlocklistStore(null),
            BreachCheckCoordinator(NoOpBreachRangeClient),
            databaseSession = session,
            stringsProvider = TEST_STRINGS
        ).also { MainDispatcherGuard.track(it) }
    }

    private object NoOpBreachRangeClient : BreachRangeClient {
        override suspend fun queryRange(prefix: String): Set<String> = emptySet()
    }

    private companion object {
        val TEST_STRINGS = StringsProvider { _, _ -> "" }

        val TEST_IV = ByteArray(12) { (it + 1).toByte() }
        const val XOR_MASK = 0x5A

        /** 虚构假凭据（非真实凭据，符合测试数据规约） */
        const val WEBDAV_FAKE_PASSWORD = "WebDav-Fake-Pw-1"
        const val S3_ACCESS_FAKE = "AKIAFAKEEXAMPLE"
        const val S3_SECRET_FAKE = "s3-fake-secret-key"

        /** 擦除字符：`fill('0')` 用的是字符 `'0'`，不是空字符 */
        const val ERASED_CHAR = '0'
    }
}
