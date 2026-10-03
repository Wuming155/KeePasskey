package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.CreateKeyFileFactor
import com.keepasskey.app.data.repository.CreateVaultPreset
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult
import com.keepasskey.app.ui.screens.unlock.RememberedKeyFile
import com.keepasskey.core.result.KdbxResult
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P3-21 回归测试：建库侧密钥文件因子的**接线**（app 层）。
 *
 * 断言重点（与 database 模块 `DatabaseSessionKeyFileCompositeTest` 的「真实加解密结果」互补）：
 * - 未勾选 → 仅主密码因子；
 * - 勾选且未选既有文件 → 生成型因子，并触发一次性交付提示（丢失即无法解锁）；
 * - 勾选且选定既有文件 → **用户选中的字节真实下传**参与复合密钥，且借用副本用毕清零；
 * - 读取失败 / 通道缺失 → 显式失败且**不调用建库**（绝不静默降级为生成型或仅密码）；
 * - 未覆盖新通道的测试替身 → 携带密钥文件因子时必须显式失败，不得静默丢弃第二因子。
 *
 * 全程手写 Fake（不使用 MockK），仅依赖 JVM + JUnit4/协程测试库，不触碰 `android.*`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabasePickerKeyFileCreateTest {

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

    /** 记录型仓库：只关心「建库时下传的密钥文件因子」，其余方法委托给内存 Fake */
    private class RecordingVaultRepository : VaultRepository by FakeVaultRepository() {

        var lastCreatedName: String? = null
        var lastFactor: CreateKeyFileFactor? = null

        /** 调用瞬间的密钥文件字节快照（VM 会在调用返回后清零原数组，故必须当场拷贝） */
        var lastExistingKeyFileBytes: ByteArray? = null

        /** ISSUE-P2-229：建库落地位置（null = 应用私有目录；非空 = `content://` 自选文档） */
        var lastTargetUri: String? = null

        override suspend fun createDatabaseWithKeyFile(
            name: String,
            masterPassword: CharArray,
            keyFileFactor: CreateKeyFileFactor,
            preset: CreateVaultPreset,
            targetUri: String?
        ): KdbxResult<Unit> {
            lastCreatedName = name
            lastFactor = keyFileFactor
            lastTargetUri = targetUri
            if (keyFileFactor is CreateKeyFileFactor.Existing) {
                lastExistingKeyFileBytes = keyFileFactor.bytes.copyOf()
            }
            return KdbxResult.Success(Unit)
        }
    }

    /** 密钥文件读取通道假实现：只覆盖与本任务相关的读取分型 */
    private class FakeKeyFileAccess(private val outcome: KeyFileReadResult) : KeyFileAccess {

        var lastReadUri: String? = null

        /** ISSUE-P2-460 AC②：「记住密钥文件位置」偏好（默认关 = 既有用例行为不变） */
        var rememberEnabled: Boolean = false

        /** ISSUE-P2-460 AC②：持久化读授权是否可得 */
        var persistPermissionSucceeds: Boolean = false

        /** ISSUE-P2-460 AC②：按库登记观测点 */
        var lastRememberedDbId: String? = null
        var lastRememberedUri: String? = null

        override suspend fun isRememberEnabled(): Boolean = rememberEnabled

        override suspend fun read(uri: String): KeyFileReadResult {
            lastReadUri = uri
            return outcome
        }

        override suspend fun persistReadPermission(uri: String): Boolean = persistPermissionSucceeds

        override suspend fun hasPersistedReadPermission(uri: String): Boolean = false

        override suspend fun loadRemembered(databaseId: String): RememberedKeyFile? = null

        override suspend fun loadLegacyGlobalHint(): RememberedKeyFile? = null

        override suspend fun remember(databaseId: String, uri: String, displayName: String) {
            lastRememberedDbId = databaseId
            lastRememberedUri = uri
        }

        override suspend fun forget(databaseId: String?) = Unit
    }

    private fun TestScope.createViewModel(
        repository: VaultRepository,
        keyFileAccess: KeyFileAccess? = null,
        copyStore: com.keepasskey.app.security.KeyFileVaultCopyStore? = null
    ): DatabasePickerViewModel {
        val viewModel = DatabasePickerViewModel(
            vaultRepository = repository,
            keyFileAccess = keyFileAccess,
            keyFileVaultCopyStore = copyStore
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return MainDispatcherGuard.track(viewModel)
    }

    // ===== ISSUE-P2-460 AC②：建库成功即收编副本 + 按库登记（不等下次解锁） =====

    /** 带假封印挂钩的临时副本库（KeyFileSessionCoordinatorTest 同范式；JVM 无 Keystore） */
    private fun newCopyStore(): com.keepasskey.app.security.KeyFileVaultCopyStore {
        val dir = java.io.File.createTempFile("kfc", "").parentFile
            .resolve("kfc-${System.nanoTime()}")
        return com.keepasskey.app.security.KeyFileVaultCopyStore(
            context = null,
            keystoreManager = null
        ).also { store ->
            store.baseDirOverride = dir
            store.sealHook = { dek ->
                val iv = ByteArray(12) { it.toByte() }
                iv to dek.mapIndexed { i, b -> (b.toInt() xor (i and 0xFF)).toByte() }.toByteArray()
            }
            store.unsealHook = { iv, ciphertext ->
                ciphertext.mapIndexed { i, b -> (b.toInt() xor (i and 0xFF)).toByte() }.toByteArray()
            }
        }
    }

    /**
     * 轮询等待建库收编完成：`KeyFileVaultCopyStore.save` 内部 `withContext(Dispatchers.IO)`
     * 在真实 IO 线程执行，`testScheduler.runCurrent()` 不等待其完成，须轮询至副本可读
     * （每轮先推进 Main 队列让 VM 协程续跑，再真读一次副本）。
     */
    private suspend fun TestScope.awaitCopy(
        store: com.keepasskey.app.security.KeyFileVaultCopyStore,
        dbId: String
    ): com.keepasskey.app.security.KeyFileVaultCopyStore.StoredCopy? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            testScheduler.runCurrent()
            store.load(dbId)?.let { return it }
            kotlinx.coroutines.delay(5)
        }
        return null
    }

    /** 同上：轮询等待按库登记落位（登记协程同样经真实 IO 的授权校验后写入） */
    private suspend fun TestScope.awaitRememberedDbId(access: FakeKeyFileAccess): String? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            testScheduler.runCurrent()
            access.lastRememberedDbId?.let { return it }
            kotlinx.coroutines.delay(5)
        }
        return null
    }

    @Test
    fun `建库成功即收编生成型密钥文件副本（不等下次解锁）`() = runTest {
        val sessionBytes = ByteArray(32) { (it * 3 + 1).toByte() }
        val recording = RecordingVaultRepository()
        val repository = object : VaultRepository by recording {
            override suspend fun exportKeyFileBytes(): KdbxResult<ByteArray> =
                KdbxResult.Success(sessionBytes.copyOf())
        }
        val store = newCopyStore()
        val viewModel = createViewModel(repository, copyStore = store)

        viewModel.createDatabase(
            "generated.kdbx", "Fake#Adopt".toCharArray(),
            keyFile = true, preset = PRESET
        )
        testScheduler.runCurrent()

        val copy = awaitCopy(store, "generated.kdbx")
        assertNotNull("建库成功即收编副本，不再等下次解锁", copy)
        assertArrayEquals("副本字节必须为会话绑定的密钥文件", sessionBytes, copy!!.bytes)
        assertEquals("生成型副本显示名取建议名（.kdbx → .keyx）", "generated.keyx", copy.displayName)
        copy.bytes.fill(0)
    }

    @Test
    fun `既有密钥文件建库成功即按库登记且副本带来源显示名`() = runTest {
        val keyFileBytes = ByteArray(32) { 9 }
        val access = FakeKeyFileAccess(KeyFileReadResult.Success(keyFileBytes, "seed.keyx")).apply {
            rememberEnabled = true
            persistPermissionSucceeds = true
        }
        val recording = RecordingVaultRepository()
        val repository = object : VaultRepository by recording {
            override suspend fun exportKeyFileBytes(): KdbxResult<ByteArray> =
                KdbxResult.Success(keyFileBytes.copyOf())
        }
        val store = newCopyStore()
        val viewModel = createViewModel(repository, access, store)

        viewModel.createDatabase(
            "existing.kdbx", "Fake#AdoptExisting".toCharArray(),
            keyFile = true, preset = PRESET, keyFileSourceUri = KEY_FILE_URI
        )
        testScheduler.runCurrent()

        assertEquals(
            "登记必须落本库名下（targetUri 为空 ⇒ 库 id = 登记文件名）",
            "existing.kdbx",
            awaitRememberedDbId(access)
        )
        assertEquals("登记内容为来源 Uri", KEY_FILE_URI, access.lastRememberedUri)
        val copy = awaitCopy(store, "existing.kdbx")
        assertNotNull("既有因子建库同样立即收编副本", copy)
        assertEquals("副本显示名取来源文档名", "seed.keyx", copy!!.displayName)
        copy.bytes.fill(0)
    }

    @Test
    fun `既有密钥文件建库时偏好关闭不登记Uri但副本仍收编`() = runTest {
        val keyFileBytes = ByteArray(32) { 5 }
        val access = FakeKeyFileAccess(KeyFileReadResult.Success(keyFileBytes, "seed.keyx"))
        val recording = RecordingVaultRepository()
        val repository = object : VaultRepository by recording {
            override suspend fun exportKeyFileBytes(): KdbxResult<ByteArray> =
                KdbxResult.Success(keyFileBytes.copyOf())
        }
        val store = newCopyStore()
        val viewModel = createViewModel(repository, access, store)

        viewModel.createDatabase(
            "pref-off.kdbx", "Fake#PrefOff".toCharArray(),
            keyFile = true, preset = PRESET, keyFileSourceUri = KEY_FILE_URI
        )
        testScheduler.runCurrent()

        val copy = awaitCopy(store, "pref-off.kdbx")
        assertNotNull("副本归「导入密钥文件」功能管，不随记忆偏好缺位（§411 裁决同源）", copy)
        copy!!.bytes.fill(0)
        // 收编完成后登记面仍应保持未登记（偏好关闭不写 Uri 记忆）
        testScheduler.runCurrent()
        assertNull("偏好关闭不得登记 Uri 记忆", access.lastRememberedDbId)
    }

    @Test
    fun `未勾选密钥文件时以仅主密码因子建库`() = runTest {
        val repository = RecordingVaultRepository()
        val viewModel = createViewModel(repository)

        viewModel.createDatabase(
            "plain.kdbx", "Fake#NoKeyFile".toCharArray(),
            keyFile = false, preset = PRESET
        )
        testScheduler.runCurrent()

        assertEquals(CreateKeyFileFactor.None, repository.lastFactor)
        assertEquals(KeyFileDeliveryState.None, viewModel.keyFileDelivery.value)
    }

    /** ISSUE-P2-229：未显式选位置即维持既有行为——建到应用私有目录（targetUri 必须为 null） */
    @Test
    fun `默认建库不下发自选位置`() = runTest {
        val repository = RecordingVaultRepository()
        val viewModel = createViewModel(repository)

        viewModel.createDatabase(
            "internal.kdbx", "Fake#Internal".toCharArray(),
            keyFile = false, preset = PRESET
        )
        testScheduler.runCurrent()

        assertNull("内部存储分支必须传 null，否则会被误判为 SAF 库", repository.lastTargetUri)
    }

    /** ISSUE-P2-229：向导经 `ACTION_CREATE_DOCUMENT` 挑定文档后，uri 必须原样下行到数据层 */
    @Test
    fun `自选位置时把 SAF 文档 uri 下行到数据层`() = runTest {
        val repository = RecordingVaultRepository()
        val viewModel = createViewModel(repository)
        val picked = "content://com.android.externalstorage.documents/document/primary%3Abackup.kdbx"

        viewModel.createDatabase(
            "backup.kdbx", "Fake#Saf".toCharArray(),
            keyFile = false, preset = PRESET, targetUri = picked
        )
        testScheduler.runCurrent()

        assertEquals(picked, repository.lastTargetUri)
    }

    @Test
    fun `勾选生成密钥文件时以生成型因子建库并触发一次性交付提示`() = runTest {
        val repository = RecordingVaultRepository()
        val viewModel = createViewModel(repository)

        viewModel.createDatabase(
            "generated.kdbx", "Fake#GenerateKeyFile".toCharArray(),
            keyFile = true, preset = PRESET
        )
        testScheduler.runCurrent()

        assertEquals(CreateKeyFileFactor.Generate, repository.lastFactor)
        val delivery = viewModel.keyFileDelivery.value
        assertTrue("生成型密钥文件必须触发一次性交付提示", delivery is KeyFileDeliveryState.PendingSave)
        assertEquals(
            "建议文件名应与密码库同名（.kdbx → .keyx）",
            "generated.keyx",
            (delivery as KeyFileDeliveryState.PendingSave).suggestedFileName
        )
    }

    @Test
    fun `选定既有密钥文件时其字节真实参与复合密钥且副本用毕清零`() = runTest {
        val repository = RecordingVaultRepository()
        val keyFileBytes = ByteArray(32) { (it * 5 + 1).toByte() }
        // 期望值必须**独立快照**：`keyFileBytes` 是交给数据层的借用副本，按擦除契约会被原地清零
        // （下方 :173 断言其全零）。若直接拿它当期望值，则测试自身的别名共享会把断言击败——
        // 与 RESOLVED_LOG §3.3 第 5 条（P3-13 的同类测试缺陷）同因：生产实现正确，测试有缺陷。
        val expectedBytes = keyFileBytes.copyOf()
        val access = FakeKeyFileAccess(KeyFileReadResult.Success(keyFileBytes, "seed.keyx"))
        val viewModel = createViewModel(repository, access)

        viewModel.createDatabase(
            "existing.kdbx", "Fake#ExistingKeyFile".toCharArray(),
            keyFile = true, preset = PRESET, keyFileSourceUri = KEY_FILE_URI
        )
        testScheduler.runCurrent()

        val factor = repository.lastFactor
        assertTrue("选定既有密钥文件必须以 Existing 因子建库", factor is CreateKeyFileFactor.Existing)
        assertArrayEquals(
            "下传的必须是用户选中的真实字节（不得丢弃）",
            expectedBytes,
            repository.lastExistingKeyFileBytes
        )
        assertEquals("必须以选定 Uri 读取密钥文件", KEY_FILE_URI, access.lastReadUri)
        assertTrue(
            "借用语义：数据层用毕后调用方副本必须被清零",
            keyFileBytes.all { it == 0.toByte() }
        )
        assertEquals(
            "既有密钥文件已在用户手上，不应触发一次性交付提示",
            KeyFileDeliveryState.None,
            viewModel.keyFileDelivery.value
        )
    }

    @Test
    fun `既有密钥文件读取失败时必须显式失败且不得降级为生成型`() = runTest {
        val repository = RecordingVaultRepository()
        val viewModel = createViewModel(repository, FakeKeyFileAccess(KeyFileReadResult.Unreadable))

        viewModel.createDatabase(
            "broken.kdbx", "Fake#UnreadableKeyFile".toCharArray(),
            keyFile = true, preset = PRESET, keyFileSourceUri = KEY_FILE_URI
        )
        testScheduler.runCurrent()

        assertNull("读取失败时不得建库（更不得降级为生成型/仅密码）", repository.lastFactor)
        assertEquals(KeyFileDeliveryState.None, viewModel.keyFileDelivery.value)
        assertEquals(
            R.string.unlock_keyfile_read_failed,
            viewModel.uiState.value.userMessage?.resId
        )
    }

    @Test
    fun `密钥文件读取通道缺失时必须显式失败而非静默生成`() = runTest {
        val repository = RecordingVaultRepository()
        val viewModel = createViewModel(repository, keyFileAccess = null)

        viewModel.createDatabase(
            "no-channel.kdbx", "Fake#NoChannel".toCharArray(),
            keyFile = true, preset = PRESET, keyFileSourceUri = KEY_FILE_URI
        )
        testScheduler.runCurrent()

        assertNull("通道缺失时不得以任何因子建库", repository.lastFactor)
        assertEquals(
            R.string.unlock_keyfile_read_failed,
            viewModel.uiState.value.userMessage?.resId
        )
    }

    @Test
    fun `未覆盖密钥文件建库通道的仓库必须显式失败而非静默丢弃第二因子`() = runTest {
        val bare = FakeVaultRepository()

        val passwordOnly = bare.createDatabaseWithKeyFile(
            "plain.kdbx", CharArray(0), CreateKeyFileFactor.None, PRESET
        )
        assertTrue("仅主密码因子可回退到既有建库入口", passwordOnly.isSuccess)

        val generated = bare.createDatabaseWithKeyFile(
            "generated.kdbx", CharArray(0), CreateKeyFileFactor.Generate, PRESET
        )
        assertTrue("携带生成型因子却未实现通道时必须显式失败", generated.isFailure)

        val existing = bare.createDatabaseWithKeyFile(
            "existing.kdbx", CharArray(0), CreateKeyFileFactor.Existing(ByteArray(32)), PRESET
        )
        assertTrue("携带既有密钥文件因子却未实现通道时必须显式失败", existing.isFailure)
    }

    private companion object {
        val PRESET = CreateVaultPreset.CHACHA20_ARGON2ID
        const val KEY_FILE_URI = "content://test.docs/keyfile/seed.keyx"
    }
}
