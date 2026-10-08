package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.CreateVaultPreset
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.model.VaultRemovalKind
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DatabasePickerViewModel 单元测试：
 * 覆盖密码库列表加载、新建库向导、导入外部库与多库切换选择。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabasePickerViewModelTest {

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

    private fun TestScope.createViewModel(
        cloudVaultImporter: com.keepasskey.app.sync.CloudVaultImporter? = null
    ): Pair<DatabasePickerViewModel, FakeVaultRepository> {
        val repo = FakeVaultRepository()
        val viewModel = DatabasePickerViewModel(repo, cloudVaultImporter = cloudVaultImporter)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return Pair(MainDispatcherGuard.track(viewModel), repo)
    }

    @Test
    fun `选择数据库触发选中事件并更新激活态`() = runTest {
        val (viewModel, _) = createViewModel()
        var selectedId: String? = null

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is DatabasePickerEvent.DatabaseSelected) {
                    selectedId = event.id
                }
            }
        }

        viewModel.selectDatabase("db_work")
        testScheduler.runCurrent()

        assertEquals("db_work", selectedId)
        val active = viewModel.uiState.value.databases.firstOrNull { it.isActive }
        assertEquals("db_work", active?.id)
    }

    @Test
    fun `新建密码库成功后关闭弹窗并弹出提示`() = runTest {
        val (viewModel, _) = createViewModel()

        viewModel.openCreateDialog()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.showCreateDialog)

        val pwd = "TestPassword#2026".toCharArray()
        viewModel.createDatabase(
            "new_secure_vault.kdbx",
            pwd,
            keyFile = false,
            preset = CreateVaultPreset.CHACHA20_ARGON2ID
        )
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.showCreateDialog)
        assertNotNull(viewModel.uiState.value.userMessage)
        assertEquals(R.string.db_picker_msg_created, viewModel.uiState.value.userMessage?.resId)
    }

    @Test
    fun `导入外部密码库成功后关闭弹窗并弹出提示`() = runTest {
        val (viewModel, _) = createViewModel()

        viewModel.openOpenSourceDialog()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.showOpenSourceDialog)

        viewModel.importDatabaseFromSource(
            OpenVaultSubmission.Local(
                name = "external_vault.kdbx",
                path = "content://com.android.providers.downloads.documents/document/123"
            )
        )
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.showOpenSourceDialog)
        assertNotNull(viewModel.uiState.value.userMessage)
        assertEquals(R.string.db_picker_msg_opened, viewModel.uiState.value.userMessage?.resId)
    }

    // ===== ISSUE-P2-399：云端打开链路 =====

    /** 假导入器：返回预置结果并模拟借用语义（消费请求凭据后擦除） */
    private class FakeCloudVaultImporter(
        private val result: com.keepasskey.app.sync.CloudVaultImportResult
    ) : com.keepasskey.app.sync.CloudVaultImporter {
        var lastRequest: com.keepasskey.app.sync.CloudVaultImportRequest? = null

        override suspend fun import(
            request: com.keepasskey.app.sync.CloudVaultImportRequest
        ): com.keepasskey.app.sync.CloudVaultImportResult {
            lastRequest = request
            when (request) {
                is com.keepasskey.app.sync.CloudVaultImportRequest.WebDav -> request.password.fill('0')
                is com.keepasskey.app.sync.CloudVaultImportRequest.S3 -> {
                    request.accessKey.fill('0')
                    request.secretKey.fill('0')
                }
            }
            return result
        }
    }

    @Test
    fun `云端导入成功后按本地路径登记并保持云端来源标签`() = runTest {
        val importer = FakeCloudVaultImporter(
            com.keepasskey.app.sync.CloudVaultImportResult.Success("/data/files/cloud_vault.kdbx")
        )
        val (viewModel, repo) = createViewModel(importer)

        viewModel.importDatabaseFromSource(
            OpenVaultSubmission.Cloud(
                com.keepasskey.app.sync.CloudVaultImportRequest.WebDav(
                    name = "cloud_vault",
                    url = "https://dav.example.com/dav/",
                    username = "user@example.com",
                    password = "pwd".toCharArray(),
                    remotePath = "mailbox/keepasskey.kdbx"
                )
            )
        )
        testScheduler.runCurrent()

        // 导入器收到原始请求
        assertTrue(importer.lastRequest is com.keepasskey.app.sync.CloudVaultImportRequest.WebDav)
        // 登记出口收到本地路径，且 syncType 保持 WebDAV 云端标签（卡片云徽章 / 解锁页「云端库」口径）
        assertEquals(
            Triple("cloud_vault", "/data/files/cloud_vault.kdbx", "WebDAV 云存储"),
            repo.lastImport
        )
        assertFalse(viewModel.uiState.value.showOpenSourceDialog)
    }

    @Test
    fun `云端导入失败如实上浮且不登记`() = runTest {
        val importer = FakeCloudVaultImporter(
            com.keepasskey.app.sync.CloudVaultImportResult.Failure(
                com.keepasskey.app.ui.model.UiMessage(R.string.picker_cloud_download_failed)
            )
        )
        val (viewModel, repo) = createViewModel(importer)

        viewModel.importDatabaseFromSource(
            OpenVaultSubmission.Cloud(
                com.keepasskey.app.sync.CloudVaultImportRequest.S3(
                    name = "s3_vault",
                    endpoint = "https://acct.r2.cloudflarestorage.com",
                    bucket = "my-vault",
                    region = "auto",
                    accessKey = "AK".toCharArray(),
                    secretKey = "SK".toCharArray(),
                    objectKey = "keepasskey.kdbx",
                    usePathStyle = false
                )
            )
        )
        testScheduler.runCurrent()

        assertEquals(R.string.picker_cloud_download_failed, viewModel.uiState.value.userMessage?.resId)
        assertNull(repo.lastImport)
    }

    @Test
    fun `移除数据库成功后弹出提示`() = runTest {
        val (viewModel, repo) = createViewModel()

        viewModel.removeDatabase("db_work", VaultRemovalKind.PRIVATE_FILE)
        testScheduler.runCurrent()

        assertNotNull(viewModel.uiState.value.userMessage)
        assertEquals(R.string.db_picker_msg_removed, viewModel.uiState.value.userMessage?.resId)
        assertNull(viewModel.uiState.value.databases.find { it.id == "db_work" })
        // ISSUE-P1-241：下行给数据层的必须是调用侧同一枚判据（数据层据此决定是否删文件），
        // 不得被 ViewModel 二次判定或吞掉
        assertEquals(VaultRemovalKind.PRIVATE_FILE, repo.lastRemovalKind)
    }

    /**
     * `ISSUE-P2-529` AC①/AC③：两条移除出口必须把「是否另存副本」这一开关**如实**下行到数据层——
     * 界面选的是哪条出口，数据层收到的就是哪条（`saveCopy`），不得被吞掉或默认成 true。
     */
    @Test
    fun `两条移除出口如实下行另存副本开关`() = runTest {
        val (viewModel, repo) = createViewModel()

        viewModel.removeDatabase("db_work", VaultRemovalKind.PRIVATE_FILE)
        testScheduler.runCurrent()
        assertFalse("「永久删除」出口不得顺手另存副本", repo.lastRemovalSaveCopy)

        viewModel.saveCopyAndRemoveDatabase("db_work", VaultRemovalKind.PRIVATE_FILE)
        testScheduler.runCurrent()
        assertTrue("「另存副本后移除」出口必须把 saveCopy=true 下行到数据层", repo.lastRemovalSaveCopy)
        // 反馈文案须与出口一致（否则用户无从判断副本到底存了没）
        assertEquals(
            R.string.db_picker_msg_copied_removed,
            viewModel.uiState.value.userMessage?.resId
        )
    }

    @Test
    fun `移除数据库同时清除其按库密钥文件记忆键`() = runTest {
        // ISSUE-P3-466 ④：删库此前只清副本，按库记忆键（keyfile_remember_*_<dbId摘要>）成为
        // 无主陈旧记录并累积（真机取证：DataStore 按库记忆 5 条 vs 副本 2 个）
        val access = com.keepasskey.app.ui.screens.unlock.FakeKeyFileAccess()
        access.remember(
            "db_work",
            com.keepasskey.app.ui.screens.unlock.FakeKeyFileAccess.KEY_FILE_URI,
            com.keepasskey.app.ui.screens.unlock.FakeKeyFileAccess.DISPLAY_NAME
        )
        val viewModel = DatabasePickerViewModel(FakeVaultRepository(), keyFileAccess = access)
        MainDispatcherGuard.track(viewModel)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        testScheduler.runCurrent()

        viewModel.removeDatabase("db_work", VaultRemovalKind.PRIVATE_FILE)
        testScheduler.runCurrent()

        assertEquals("删库必须同批清按库密钥文件记忆键", 1, access.forgetCount)
        assertEquals(
            "被删库名下不得再残留记忆",
            "",
            access.persistedKeyFileUri("db_work")
        )
    }
}
