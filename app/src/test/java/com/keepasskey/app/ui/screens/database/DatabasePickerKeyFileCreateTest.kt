package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.CreateKeyFileFactor
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult
import com.keepasskey.app.ui.screens.unlock.RememberedKeyFile
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        Dispatchers.resetMain()
    }

    /** 记录型仓库：只关心「建库时下传的密钥文件因子」，其余方法委托给内存 Fake */
    private class RecordingVaultRepository : VaultRepository by FakeVaultRepository() {

        var lastCreatedName: String? = null
        var lastFactor: CreateKeyFileFactor? = null

        /** 调用瞬间的密钥文件字节快照（VM 会在调用返回后清零原数组，故必须当场拷贝） */
        var lastExistingKeyFileBytes: ByteArray? = null

        override suspend fun createDatabaseWithKeyFile(
            name: String,
            masterPassword: CharArray,
            keyFileFactor: CreateKeyFileFactor,
            preset: String
        ): KdbxResult<Unit> {
            lastCreatedName = name
            lastFactor = keyFileFactor
            if (keyFileFactor is CreateKeyFileFactor.Existing) {
                lastExistingKeyFileBytes = keyFileFactor.bytes.copyOf()
            }
            return KdbxResult.Success(Unit)
        }
    }

    /** 密钥文件读取通道假实现：只覆盖与本任务相关的读取分型 */
    private class FakeKeyFileAccess(private val outcome: KeyFileReadResult) : KeyFileAccess {

        var lastReadUri: String? = null

        override suspend fun isRememberEnabled(): Boolean = false

        override suspend fun read(uri: String): KeyFileReadResult {
            lastReadUri = uri
            return outcome
        }

        override suspend fun persistReadPermission(uri: String): Boolean = false

        override suspend fun hasPersistedReadPermission(uri: String): Boolean = false

        override suspend fun loadRemembered(): RememberedKeyFile? = null

        override suspend fun remember(uri: String, displayName: String) = Unit

        override suspend fun forget() = Unit
    }

    private fun TestScope.createViewModel(
        repository: VaultRepository,
        keyFileAccess: KeyFileAccess? = null
    ): DatabasePickerViewModel {
        val viewModel = DatabasePickerViewModel(repository, keyFileAccess)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
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
        const val PRESET = "ChaCha20 + Argon2id"
        const val KEY_FILE_URI = "content://test.docs/keyfile/seed.keyx"
    }
}
