package com.keepasskey.app.ui.screens.database

import android.content.Context
import android.content.ContextWrapper
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.CreateVaultPreset
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.testutil.MainDispatcherGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * DatabasePickerViewModel 云端面单测（ISSUE-P2-424 AC③ / ISSUE-P3-425）：
 *
 * 1. 云端直建：`storageLocation = CLOUD` 时库文件落应用私有目录、随后以**云 syncType** 幂等
 *    重登记（与「云端打开」同形态，远端上传交由既有同步周期）；
 * 2. 未配置云账号时云端直建 fail-closed（绝不静默降级为普通本地库）；建库成功但**云登记失败**时
 *    给专属错误提示，不沿用「创建成功」文案；
 * 3. 「打开已有库」对话框的已配置账号预填（消费后 ViewModel 弃持 / 关窗兜底擦除）与来源预选记忆。
 *
 * 凭据仓库用真实 `SyncCredentialsStore` + 内存键值面 + 恒等替换加解密钩子
 * （对齐 `CloudVaultImporterTest` 口径），全程不触碰真实 Keystore。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabasePickerCloudCreateTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    private val prefsFake = InMemorySharedPreferences()

    private val filesDir: File =
        Files.createTempDirectory("keepasskey-cloud-create-files").toFile().apply { deleteOnExit() }

    private val fakeContext: Context = object : ContextWrapper(prefsFake.context()) {
        // @Outer 引用测试类字段，避免 unqualified filesDir 解析到 Context 自身属性（无限递归）
        override fun getFilesDir(): File = this@DatabasePickerCloudCreateTest.filesDir

        override fun getSharedPreferences(name: String?, mode: Int) = prefsFake.prefs
    }

    /** 内存键值面直查别名 */
    private val storage: MutableMap<String, Any?> get() = prefsFake.storage

    /** 建库记录台：只关心「建库下传的 targetUri」与「云登记三元组」，其余方法委托给内存 Fake */
    private class RecordingVaultRepository : com.keepasskey.app.data.repository.VaultRepository by FakeVaultRepository() {
        var lastCreateTargetUri: String? = null
        var createCount = 0

        /** 令云登记步骤失败（验证「登记失败不谎报全成」） */
        var importFails: Boolean = false

        /** 最近一次 `importExternalDatabase` 实际收到的 (name, path, syncType) */
        var lastImport: Triple<String, String, String>? = null
            private set

        override suspend fun createDatabaseWithKeyFile(
            name: String,
            masterPassword: CharArray,
            keyFileFactor: com.keepasskey.app.data.repository.CreateKeyFileFactor,
            preset: CreateVaultPreset,
            targetUri: String?
        ): com.keepasskey.core.result.KdbxResult<Unit> {
            lastCreateTargetUri = targetUri
            createCount++
            return com.keepasskey.core.result.KdbxResult.Success(Unit)
        }

        override suspend fun importExternalDatabase(
            name: String,
            path: String,
            syncType: String
        ): com.keepasskey.core.result.KdbxResult<Unit> {
            lastImport = Triple(name, path, syncType)
            return if (importFails) {
                com.keepasskey.core.result.KdbxResult.Failure(
                    IllegalStateException("模拟云登记失败"),
                    "模拟云登记失败"
                )
            } else {
                com.keepasskey.core.result.KdbxResult.Success(Unit)
            }
        }
    }

    /** 预置已配置的 WebDAV 云账号（saveWebDavConfig 会擦传入数组，每次现造） */
    private fun presetWebDavAccount(store: SyncCredentialsStore) {
        store.saveWebDavConfig(
            "https://dav.example.com",
            "vaultuser",
            "pw123456".toCharArray(),
            "/keepasskey/passwords.kdbx"
        )
    }

    private fun newStore(): SyncCredentialsStore = SyncCredentialsStore(fakeContext, keystoreManager = null).apply {
        customEncryptor = { plaintext -> Pair(ByteArray(12), plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()) }
        customDecryptor = { _, cipher -> cipher.map { (it.toInt() xor 0x5A).toByte() }.toByteArray() }
    }

    private fun TestScope.createViewModel(
        store: SyncCredentialsStore?,
        repo: RecordingVaultRepository = RecordingVaultRepository()
    ): Pair<DatabasePickerViewModel, RecordingVaultRepository> {
        val viewModel = DatabasePickerViewModel(
            repo,
            appContext = fakeContext,
            syncCredentialsStore = store,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return Pair(MainDispatcherGuard.track(viewModel), repo)
    }

    /** ISSUE-P3-425 AC①：云端直建 = 本地 filesDir 建库 + 云 syncType 幂等重登记 */
    @Test
    fun `云端直建以云syncType登记且库文件落应用私有目录`() = runTest {
        val (viewModel, repo) = createViewModel(newStore().also(::presetWebDavAccount))
        testScheduler.runCurrent()

        val snapshot = viewModel.uiState.value.cloudSnapshot
        assertTrue("已配置账号时云端直建必须可用", snapshot.ready)
        assertEquals(OpenVaultSourceType.WEBDAV, snapshot.kind)
        assertEquals("/keepasskey/passwords.kdbx", snapshot.targetHint)

        viewModel.createDatabase(
            "cloud_vault.kdbx", "Fake#Cloud".toCharArray(),
            keyFile = false, preset = CreateVaultPreset.DEFAULT,
            storageLocation = VaultStorageLocation.CLOUD
        )
        testScheduler.runCurrent()

        // 库文件真实落在应用私有目录（SAF targetUri 必须为 null）
        assertNull("云端直建必须走 filesDir 建库（targetUri=null）", repo.lastCreateTargetUri)
        assertEquals(1, repo.createCount)
        // 随后按云 syncType 幂等重登记同一文件（目录扫描对该路径让位，不出双卡）
        val import = repo.lastImport
        assertNotNull("云端直建必须触发云 syncType 登记", import)
        assertEquals("cloud_vault.kdbx", import?.first)
        assertEquals(File(filesDir, "cloud_vault.kdbx").absolutePath, import?.second)
        assertEquals(OpenVaultSourceType.WEBDAV.label, import?.third)
        assertEquals(R.string.db_picker_msg_created, viewModel.uiState.value.userMessage?.resId)
    }

    /**
     * ISSUE-P3-425：建库成功但**云登记失败** ⇒ 必须给专属提示且以错误态呈现，
     * 不得沿用「新密码库已成功创建」谎报全成（用户会以为库已在云端）。
     */
    @Test
    fun `云登记失败时不谎报全成`() = runTest {
        val repo = RecordingVaultRepository().apply { importFails = true }
        val (viewModel, _) = createViewModel(newStore().also(::presetWebDavAccount), repo)
        testScheduler.runCurrent()

        viewModel.createDatabase(
            "cloud_vault.kdbx", "Fake#Cloud".toCharArray(),
            keyFile = false, preset = CreateVaultPreset.DEFAULT,
            storageLocation = VaultStorageLocation.CLOUD
        )
        testScheduler.runCurrent()

        val message = viewModel.uiState.value.userMessage
        assertEquals(R.string.db_picker_msg_cloud_register_failed, message?.resId)
        assertTrue("登记失败必须以错误态呈现（否则与「创建成功」同形）", message?.isError == true)
        assertEquals("失败只在登记步骤，建库本身仍应发生", 1, repo.createCount)
    }

    /** ISSUE-P3-425 fail-closed：未配置云账号时显式失败且不建库（绝不静默降级为普通本地库） */
    @Test
    fun `未配置云账号时云端直建显式失败不建库`() = runTest {
        val (viewModel, repo) = createViewModel(store = null)

        viewModel.createDatabase(
            "no_cloud_vault.kdbx", "Fake#NoCloud".toCharArray(),
            keyFile = false, preset = CreateVaultPreset.DEFAULT,
            storageLocation = VaultStorageLocation.CLOUD
        )
        testScheduler.runCurrent()

        assertEquals(R.string.db_picker_cloud_create_unconfigured, viewModel.uiState.value.userMessage?.resId)
        assertEquals("fail-closed：未配置账号不得建库", 0, repo.createCount)
        assertNull(repo.lastImport)
    }

    /** ISSUE-P2-424 AC③：打开对话框预填已配置账号（URL/用户名/路径/口令齐备，免手输） */
    @Test
    fun `打开对话框预填已配置WebDAV账号且消费后弃持`() = runTest {
        val (viewModel, _) = createViewModel(newStore().also(::presetWebDavAccount))
        testScheduler.runCurrent()

        viewModel.openOpenSourceDialog()
        testScheduler.runCurrent()

        val prefill = viewModel.openVaultPrefill.value
        assertNotNull(prefill)
        val webdav = prefill!!.webdav
        assertNotNull("WebDAV 账号已配置时必须带预填", webdav)
        assertEquals("https://dav.example.com", webdav!!.url)
        assertEquals("vaultuser", webdav.username)
        assertEquals("/keepasskey/passwords.kdbx", webdav.remotePath)
        assertTrue("解封口令必须随预填到达（免手输）", webdav.passwordChars.contentEquals("pw123456".toCharArray()))

        // 对话框消费后 ViewModel 侧弃持（数组所有权移交表单态，此处只清引用不擦）
        viewModel.consumeOpenVaultPrefill()
        assertNull(viewModel.openVaultPrefill.value)
    }

    /** ISSUE-P2-424 AC③ 借用语义兜底：预填未被消费即关窗时，凭据数组必须就地擦除 */
    @Test
    fun `关窗兜底擦除未消费的预填凭据`() = runTest {
        val (viewModel, _) = createViewModel(newStore().also(::presetWebDavAccount))
        testScheduler.runCurrent()

        viewModel.openOpenSourceDialog()
        testScheduler.runCurrent()
        val leaked = viewModel.openVaultPrefill.value!!.webdav!!.passwordChars

        viewModel.closeOpenSourceDialog()
        assertNull(viewModel.openVaultPrefill.value)
        assertTrue("未消费的预填凭据必须就地擦除", leaked.all { it == '0' })
    }

    /** ISSUE-P2-424 AC③：来源预选记忆——用户切过的来源在下次打开时预选 */
    @Test
    fun `来源预选记忆下次打开生效`() = runTest {
        val (viewModel, _) = createViewModel(store = null)

        viewModel.noteOpenVaultSource(OpenVaultSourceType.S3_COMPATIBLE)
        viewModel.openOpenSourceDialog()
        testScheduler.runCurrent()

        assertEquals(OpenVaultSourceType.S3_COMPATIBLE, viewModel.openVaultPrefill.value?.lastSource)
    }
}
