package com.keepasskey.app.sync

import android.content.Context
import android.content.ContextWrapper
import com.keepasskey.app.data.repository.ActiveDatabaseIdStore
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

/**
 * CloudVaultImporter 单元测试（ISSUE-P2-399）。
 *
 * 锁定云端打开导入编排的不可回退约束：
 * ① 先下载后落凭据——下载失败时 `SyncCredentialsStore` 不得写入任何键（含 Provider 指向）；
 * ② 成功路径：远端内容落地为本地库文件、凭据封印落盘且 Provider 指向随来源切换；
 * ③ 借用语义：请求侧凭据 `CharArray` 在任何结果路径用毕擦除；
 * ④ 本地同名库文件冲突 fail-closed，绝不覆盖。
 */
class CloudVaultImporterTest {

    private val prefsFake = InMemorySharedPreferences()

    private val filesDir: File =
        Files.createTempDirectory("keepasskey-importer-files").toFile().apply { deleteOnExit() }

    private val fakeContext: Context = object : ContextWrapper(prefsFake.context()) {
        // 注意：unqualified `filesDir` 在 Context 子类作用域内会解析到 Context 自身属性，
        // 必须 @Outer 显式引用测试类字段，否则 getFilesDir 无限递归
        override fun getFilesDir(): File = this@CloudVaultImporterTest.filesDir

        // android.jar 桩的 ContextWrapper 不委托 base（方法体直接抛 not-mocked），必须自行覆写
        override fun getSharedPreferences(name: String?, mode: Int) = prefsFake.prefs
    }

    private lateinit var store: SyncCredentialsStore
    private lateinit var importer: RealCloudVaultImporter

    @Before
    fun setUp() {
        filesDir.listFiles()?.forEach { it.delete() }
        store = SyncCredentialsStore(fakeContext, keystoreManager = null)
        store.customEncryptor = { plaintext ->
            Pair(ByteArray(12), plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
        }
        store.customDecryptor = { _, cipher ->
            cipher.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        importer = RealCloudVaultImporter(fakeContext, store)
    }

    /** 内存键值面直查别名（对齐 SyncCredentialsStoreTest 口径） */
    private val storage: MutableMap<String, Any?> get() = prefsFake.storage

    /**
     * `ISSUE-P2-465`：把「当前活动库」摆到刚导入的落盘路径上——上层 `importExternalDatabase`
     * 正是以该路径登记该库，故这等价于「登记后重新读取」。替身把两个 prefs 名映射到同一张表，
     * 故活动库 ID 与同步键同处一张内存表。
     */
    private fun activateImportedVault(localPath: String) {
        storage[ActiveDatabaseIdStore.KEY_ACTIVE_DATABASE_ID] = localPath
    }

    /** 假 Provider：download 把 [content] 写进 sink（[failDownload] 时模拟网络失败；[onDownload] 供用例注入下载期副作用） */
    private class FakeProvider(
        private val content: ByteArray = "FAKE_KDBX".toByteArray(),
        private val failDownload: Boolean = false,
        private val onDownload: () -> Unit = {}
    ) : SyncProvider {
        var downloadedPath: String? = null
        var clearedCredentials = false

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> =
            Result.success(RemoteFileMetadata(remotePath, "\"etag\"", content.size.toLong(), 0L))

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> {
            if (failDownload) return Result.failure(
                com.keepasskey.sync.model.SyncException.NetworkError("网络不可达")
            )
            onDownload()
            downloadedPath = remotePath
            sink.write(content)
            return Result.success(Unit)
        }

        override suspend fun upload(remotePath: String, data: ByteArray, expectedEtag: String?): Result<String> =
            Result.failure(UnsupportedOperationException("导入链路不上传"))

        override suspend fun delete(remotePath: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("导入链路不删除"))
    }

    private fun webDavRequest(password: CharArray = "webdav-pass".toCharArray()) =
        CloudVaultImportRequest.WebDav(
            name = "cloud_vault",
            url = "dav.example.com/dav/",
            username = "user@example.com",
            password = password,
            remotePath = "mailbox/keepasskey.kdbx"
        )

    @Test
    fun `WebDAV 成功路径下载落地且凭据封印落盘`() = runTest {
        val provider = FakeProvider()
        importer.providerFactory = CloudVaultProviderFactory { provider }

        val request = webDavRequest()
        val result = importer.import(request)

        assertTrue(result is CloudVaultImportResult.Success)
        val localFile = File((result as CloudVaultImportResult.Success).localPath)
        assertTrue(localFile.isFile)
        assertArrayEquals("FAKE_KDBX".toByteArray(), localFile.readBytes())
        assertEquals("/mailbox/keepasskey.kdbx", provider.downloadedPath)
        // ISSUE-P2-465：导入的凭据落在「即将登记的本地库」命名空间下（显式 dbId = 落盘路径）
        // ——上层登记该库后（活动库 ID = 同一路径），设置页与同步周期读到的正是这一份
        activateImportedVault(localFile.absolutePath)
        assertEquals(CloudSyncProvider.WEBDAV, store.loadProvider())
        val saved = store.loadWebDavConfig()
        assertEquals("https://dav.example.com/dav/", saved?.url)
        assertEquals("user@example.com", saved?.username)
        assertEquals("mailbox/keepasskey.kdbx", saved?.remotePath)
        // ISSUE-P2-402 后续：密码往返必须原样（封印的是输入内容，不是擦除后的 '0' 串）
        assertArrayEquals("webdav-pass".toCharArray(), saved?.password)
        // 请求侧凭据用毕擦除（借用语义）
        assertTrue(request.password.all { it == '0' })
        // 无临时残留
        assertTrue(filesDir.listFiles()?.none { it.name.endsWith(".importing") } == true)
    }

    @Test
    fun `下载失败不写凭据存储且不留下库文件`() = runTest {
        // 预置一份既有可用配置：失败路径不得覆盖它
        storage["webdav_url"] = "https://old.example.com/dav/"
        val provider = FakeProvider(failDownload = true)
        importer.providerFactory = CloudVaultProviderFactory { provider }

        val request = webDavRequest()
        val result = importer.import(request)

        assertTrue(result is CloudVaultImportResult.Failure)
        // 预置的旧配置原样保留、新凭据未写入
        assertEquals("https://old.example.com/dav/", storage["webdav_url"])
        assertNull(storage["webdav_password_iv"])
        assertNull(storage["webdav_password_cipher"])
        assertNull(storage["sync_provider"])
        assertTrue(filesDir.listFiles()?.isEmpty() == true)
        assertTrue(request.password.all { it == '0' })
    }

    @Test
    fun `本地同名库文件冲突时拒绝且不触碰网络与存储`() = runTest {
        File(filesDir, "cloud_vault.kdbx").writeText("EXISTING")
        var factoryCalled = false
        importer.providerFactory = CloudVaultProviderFactory {
            factoryCalled = true
            FakeProvider()
        }

        val request = webDavRequest()
        val result = importer.import(request)

        assertTrue(result is CloudVaultImportResult.Failure)
        assertFalse(factoryCalled)
        assertEquals("EXISTING", File(filesDir, "cloud_vault.kdbx").readText())
        assertNull(storage["webdav_url"])
        assertTrue(request.password.all { it == '0' })
    }

    @Test
    fun `S3 成功路径按 objectKey 下载且凭据封印落盘`() = runTest {
        val provider = FakeProvider()
        importer.providerFactory = CloudVaultProviderFactory { provider }

        val request = CloudVaultImportRequest.S3(
            name = "s3_vault",
            endpoint = "https://acct.r2.cloudflarestorage.com",
            bucket = "my-vault",
            region = "auto",
            accessKey = "AKID".toCharArray(),
            secretKey = "SK".toCharArray(),
            objectKey = "backups/keepasskey.kdbx",
            usePathStyle = true
        )
        val result = importer.import(request)

        val success = result as CloudVaultImportResult.Success
        assertTrue(File(success.localPath).isFile)
        assertEquals("backups/keepasskey.kdbx", provider.downloadedPath)
        // ISSUE-P2-465：同上——按落盘路径（= 上层登记的库 ID）读回本库自己的配置
        activateImportedVault(success.localPath)
        assertEquals(CloudSyncProvider.S3_COMPATIBLE, store.loadProvider())
        val saved = store.loadS3Config()
        assertEquals("https://acct.r2.cloudflarestorage.com", saved?.endpoint)
        assertEquals("my-vault", saved?.bucket)
        assertEquals("backups/keepasskey.kdbx", saved?.objectKey)
        assertTrue(saved?.usePathStyle == true)
        // ISSUE-P2-402 后续：AK/SK 往返必须原样
        assertArrayEquals("AKID".toCharArray(), saved?.accessKey)
        assertArrayEquals("SK".toCharArray(), saved?.secretKey)
        assertTrue(request.accessKey.all { it == '0' })
        assertTrue(request.secretKey.all { it == '0' })
    }

    /**
     * ISSUE-P2-403（真机缺陷复现器）：下载窗口期请求侧凭据被外部路径擦成 '0'
     * （借用语义擦除指纹）时，提交封印必须仍使用**入口快照**的原内容——
     * 整改前该场景会把全零串封进存储，导致后续同步 401、设置页预填整串 0。
     */
    @Test
    fun `下载期间请求侧凭据被擦除仍按入口快照封印`() = runTest {
        val request = webDavRequest()
        val provider = FakeProvider(onDownload = { request.password.fill('0') })
        importer.providerFactory = CloudVaultProviderFactory { provider }

        val result = importer.import(request)
        assertTrue(result is CloudVaultImportResult.Success)
        activateImportedVault((result as CloudVaultImportResult.Success).localPath)
        val saved = store.loadWebDavConfig()
        assertEquals("user@example.com", saved?.username)
        assertArrayEquals("webdav-pass".toCharArray(), saved?.password)
    }

    @Test
    fun `凭据封印失败时整体失败且不留半成品库文件`() = runTest {
        // 封印失败模拟：customEncryptor 抛出 → saveWebDavConfig 返回 false
        store.customEncryptor = { error("keystore unavailable") }
        importer.providerFactory = CloudVaultProviderFactory { FakeProvider() }

        val request = webDavRequest()
        val result = importer.import(request)

        assertTrue(result is CloudVaultImportResult.Failure)
        assertTrue(filesDir.listFiles()?.isEmpty() == true)
        assertNull(storage["webdav_url"])
        assertTrue(request.password.all { it == '0' })
    }
}
