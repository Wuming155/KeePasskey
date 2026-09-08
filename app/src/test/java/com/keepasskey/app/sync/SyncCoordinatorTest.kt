package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * SyncCoordinator 全分支同步状态机测试 (Wave 3-E P0-5 与 E5 验收标准)
 */
@OptIn(ExperimentalCoroutinesApi::class)
/** TASK-21：用户可见消息已资源化；单测无资源环境，注入返回占位文本的假 StringsProvider */
private val TEST_STRINGS = com.keepasskey.app.ui.model.StringsProvider { _, _ -> "" }

class SyncCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakeContext: Context
    private lateinit var databaseSession: DatabaseSession
    private lateinit var credentialsStore: SyncCredentialsStore
    private lateinit var coordinator: SyncCoordinator
    private val masterPassword = "SyncMasterPassword#2026".toCharArray()

    private class MemorySyncProvider : SyncProvider {
        val remoteStorage = mutableMapOf<String, Pair<ByteArray, String>>()
        var isReachable = true

        override suspend fun testConnection(): Result<Unit> {
            return if (isReachable) Result.success(Unit) else Result.failure(IOException("Server unreachable"))
        }

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            if (!isReachable) return Result.failure(SyncException.NetworkError("Network error"))
            val item = remoteStorage[remotePath] ?: return Result.failure(
                SyncException.FileNotFound("File not found: $remotePath")
            )
            return Result.success(
                RemoteFileMetadata(
                    path = remotePath,
                    contentLength = item.first.size.toLong(),
                    lastModifiedMillis = System.currentTimeMillis(),
                    etag = item.second
                )
            )
        }

        override suspend fun download(remotePath: String): Result<ByteArray> {
            if (!isReachable) return Result.failure(SyncException.NetworkError("Network error"))
            val item = remoteStorage[remotePath] ?: return Result.failure(
                SyncException.FileNotFound("File not found")
            )
            return Result.success(item.first)
        }

        override suspend fun upload(remotePath: String, data: ByteArray, expectedEtag: String?): Result<String> {
            if (!isReachable) return Result.failure(SyncException.NetworkError("Network error"))
            val existing = remoteStorage[remotePath]
            if (expectedEtag != null && existing != null && existing.second != expectedEtag) {
                return Result.failure(
                    SyncException.ConflictError(
                        remoteEtag = existing.second,
                        localExpectedEtag = expectedEtag,
                        message = "Precondition Failed"
                    )
                )
            }
            val newEtag = "etag_${System.currentTimeMillis()}_${data.size}"
            remoteStorage[remotePath] = Pair(data, newEtag)
            return Result.success(newEtag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remoteStorage.remove(remotePath)
            return Result.success(Unit)
        }
    }

    @Before
    fun setUp() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)

        val cacheDir = File(tempFolder.root, "cache").apply { mkdirs() }
        val filesDir = File(tempFolder.root, "files").apply { mkdirs() }

        fakeContext = object : android.content.ContextWrapper(null) {
            override fun getCacheDir(): File = cacheDir
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }

        databaseSession = DatabaseSession()
        val dbFile = File(filesDir, "active_sync_vault.kdbx")
        val createResult = databaseSession.create(
            file = dbFile,
            name = "ActiveSyncVault",
            passwordChars = masterPassword,
            useArgon2 = false // 快速单元测试
        )
        assertTrue(createResult is com.keepasskey.core.result.KdbxResult.Success)

        credentialsStore = SyncCredentialsStore(fakeContext, null)
        coordinator = SyncCoordinator(fakeContext, databaseSession, credentialsStore, com.keepasskey.app.data.logger.DebugLogBuffer(), TEST_STRINGS)
    }

    @After
    fun tearDown() {
        masterPassword.fill('0')
        Dispatchers.resetMain()
    }

    @Test
    fun `测试 SyncOutcome_UploadedLocal 与 SyncOutcome_UpToDate 往返`() = runTest(testDispatcher) {
        val memoryProvider = MemorySyncProvider()
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = "/remote/vault_test1.kdbx"

        // 1. 远端尚无文件时首次同步 -> 触发自愈上传
        val outcome1 = coordinator.syncNow()
        assertTrue("首次应自动上传本地: $outcome1", outcome1 is SyncOutcome.UploadedLocal)
        assertEquals(1, memoryProvider.remoteStorage.size)

        // 2. 再次执行同步，双方哈希一致 -> 判定为 UpToDate
        val outcome2 = coordinator.syncNow()
        assertTrue("数据未变更时应判定为 UpToDate: $outcome2", outcome2 is SyncOutcome.UpToDate)
    }

    @Test
    fun `测试网络不可达时降级为 SyncOutcome_Offline`() = runTest(testDispatcher) {
        val memoryProvider = MemorySyncProvider()
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = "/remote/vault_test2.kdbx"

        // 首次同步使本地缓存就绪
        coordinator.syncNow()

        // 模拟断网
        memoryProvider.isReachable = false

        val outcome = coordinator.syncNow()
        assertTrue("断网且有本地缓存时应降级为 Offline: $outcome", outcome is SyncOutcome.Offline)
    }

    @Test
    fun `测试条目冲突检测与 resolveConflicts 解决提交闭环`() = runTest(testDispatcher) {
        val memoryProvider = MemorySyncProvider()
        val remotePath = "/remote/vault_test3.kdbx"
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = remotePath

        // 1. 建立基线
        val entryId = KdbxUuid.random()
        val sharedEntry = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Shared Entry", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("BasePassword#1", true)
            )
        )
        databaseSession.saveEntry(sharedEntry)
        databaseSession.save()

        val baseOutcome = coordinator.syncNow()
        assertTrue(baseOutcome is SyncOutcome.UploadedLocal || baseOutcome is SyncOutcome.UpToDate)

        // 2. 本地修改密码
        val localModifiedEntry = sharedEntry.withField(KdbxConstants.Fields.PASSWORD, ProtectedString("LocalNewPwd#2", true))
        databaseSession.saveEntry(localModifiedEntry)
        databaseSession.save()

        // 3. 构造并发的云端修改（密码改为 RemoteNewPwd#3）
        val remoteDb = databaseSession.databaseFlow.value!!.copy()
        val remoteEntry = sharedEntry.withField(KdbxConstants.Fields.PASSWORD, ProtectedString("RemoteNewPwd#3", true))
        val updatedRemoteRoot = remoteDb.rootGroup.copy(
            entries = remoteDb.rootGroup.entries.map { if (it.id == entryId) remoteEntry else it }
        )
        val finalRemoteDb = remoteDb.copy(rootGroup = updatedRemoteRoot)
        val baos = ByteArrayOutputStream()
        KdbxFile.save(baos, finalRemoteDb, masterPassword)
        val remoteBytes = baos.toByteArray()
        memoryProvider.remoteStorage[remotePath] = Pair(remoteBytes, "etag_concurrent_change")

        // 4. 执行同步，双方修改了相同字段，应检测出 ConflictNeedsUser
        val conflictOutcome = coordinator.syncNow()
        assertTrue("同字段并发修改必须产生冲突: $conflictOutcome", conflictOutcome is SyncOutcome.ConflictNeedsUser)
        val conflicts = (conflictOutcome as SyncOutcome.ConflictNeedsUser).conflicts
        assertEquals(1, conflicts.size)
        assertEquals(entryId.toHexString(), conflicts.first().entryId)

        // 5. 用户在冲突界面选择 KEEP_LOCAL 并提交解决
        val resolutions = mapOf(entryId.toHexString() to ConflictResolutionChoice.KEEP_LOCAL)
        val resolveOutcome = coordinator.resolveConflicts(resolutions)
        assertTrue("冲突解决上传成功: $resolveOutcome", resolveOutcome is SyncOutcome.MergedAndUploaded)

        // 6. 验证冲突列表已清空
        assertTrue(coordinator.conflictFlow.value.isEmpty())
    }

    @Test
    fun `测试缓存被系统回收且本地有未同步修改时不被远端覆盖`() = runTest(testDispatcher) {
        // F1 + F2 修复验收：cacheDir 会被系统在存储不足时自动回收（Android 官方语义）。
        // 缓存丢失后本地存在未同步修改（已落盘但尚未同步，isDirty 已复位的最常见形态）时，
        // 严禁以远端整体覆盖会话；basecache 一并丢失时必须以空 base 退化并集合并，
        // 本地修改必须存活并交由用户决策，而非静默丢失
        val memoryProvider = MemorySyncProvider()
        val remotePath = "/remote/vault_test4.kdbx"
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = remotePath

        // 1. 建立基线并上传云端
        val entryId = KdbxUuid.random()
        val sharedEntry = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Shared Entry", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("BasePassword#1", true)
            )
        )
        databaseSession.saveEntry(sharedEntry)
        databaseSession.save()
        coordinator.syncNow()

        // 2. 本地修改密码并落盘（已保存但尚未同步）
        val localModified = sharedEntry.withField(
            KdbxConstants.Fields.PASSWORD, ProtectedString("LocalOnly#9", true)
        )
        databaseSession.saveEntry(localModified)
        databaseSession.save()

        // 3. 模拟 Android 系统回收 cacheDir（缓存与 basecache 一并丢失）
        File(tempFolder.root, "cache/sync").deleteRecursively()

        // 4. 远端被其他设备修改为不同密码
        val remoteDb = databaseSession.databaseFlow.value!!.copy()
        val remoteEntry = sharedEntry.withField(
            KdbxConstants.Fields.PASSWORD, ProtectedString("RemoteOnly#7", true)
        )
        val updatedRemoteRoot = remoteDb.rootGroup.copy(
            entries = remoteDb.rootGroup.entries.map { if (it.id == entryId) remoteEntry else it }
        )
        val baos = ByteArrayOutputStream()
        KdbxFile.save(baos, remoteDb.copy(rootGroup = updatedRemoteRoot), masterPassword)
        memoryProvider.remoteStorage[remotePath] = Pair(baos.toByteArray(), "etag_remote_change")

        // 5. 同步：修复前本地修改被远端整体覆盖（UpToDate 且数据丢失）；
        //    修复后转三方合并——basecache 一并丢失，F2 修复退化并集合并 → 字段冲突交用户决策
        val outcome = coordinator.syncNow()
        assertTrue(
            "缓存回收 + 本地未同步修改必须转冲突合并而非覆盖: $outcome",
            outcome is SyncOutcome.ConflictNeedsUser
        )
        val conflicts = (outcome as SyncOutcome.ConflictNeedsUser).conflicts
        assertEquals(1, conflicts.size)
        assertEquals(entryId.toHexString(), conflicts.first().entryId)

        // 6. 用户选择保留本地 → 合并版本上传 → 本地修改完整保留
        val resolveOutcome = coordinator.resolveConflicts(
            mapOf(entryId.toHexString() to ConflictResolutionChoice.KEEP_LOCAL)
        )
        assertTrue("冲突解决应成功上传: $resolveOutcome", resolveOutcome is SyncOutcome.MergedAndUploaded)
        val savedEntry = databaseSession.databaseFlow.value!!.rootGroup.allEntries()
            .first { it.id == entryId }
        assertEquals("LocalOnly#9", savedEntry.fields[KdbxConstants.Fields.PASSWORD]?.readString())
    }
}
