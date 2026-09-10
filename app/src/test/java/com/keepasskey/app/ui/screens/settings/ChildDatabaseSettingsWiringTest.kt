package com.keepasskey.app.ui.screens.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.R
import com.keepasskey.app.data.breach.BreachCheckCoordinator
import com.keepasskey.app.data.childdb.ChildDatabaseCredentialStore
import com.keepasskey.app.data.childdb.ChildDatabaseFixtures
import com.keepasskey.app.data.childdb.ChildDatabaseMountStore
import com.keepasskey.app.data.childdb.ChildDatabaseSessionManager
import com.keepasskey.app.data.childdb.FakeChildDatabaseStreamSource
import com.keepasskey.app.data.childdb.RecordingContextFactory
import com.keepasskey.app.data.childdb.validLocalSource
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.sync.PeriodicSyncScheduler
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.unlock.FakeKeyFileAccess
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.database.session.DatabaseSession
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

/** 单测文案通道：按资源 ID 映射的假实现（无 Android 资源环境） */
private val TEST_STRINGS = StringsProvider { _, _ -> "" }

/**
 * ISSUE-P3-20：子库挂载的**设置页接线**集成回归（真实核心层 + 真实假 KDBX 语料）。
 *
 * 与 `data/childdb` 的 37 例核心层单测互补：这里验证的是「UI 层是否真的接上了核心层」，
 * 而非核心层自身行为。覆盖验收要点：
 *
 * 1. **不谎报**：控制器缺失时 `childDatabasesCount` 回落 0 且面板如实标记不可用；
 *    零挂载时计数为 0（核心层真实值为 0，不是硬编码）。
 * 2. **真接线**：真实挂载后计数与展示态同步（条目数取自真实解密快照）；
 *    卸载后计数回落且凭据槽位清零。
 * 3. **SAF 授权**：`content://` 来源在挂载前必须已申请持久化读授权
 *    （否则进程重启后只能如实报 SOURCE_UNAVAILABLE）。
 * 4. **诚实边界**：根库锁定后仍**计入已挂载**，但状态如实回落「未解锁」并保留真实解锁入口——
 *    「已挂载」与「已解锁」不得混为一谈。
 * 5. **借用语义**：提交后调用方立即擦除密码数组，挂载仍须成功（证明控制器确已复制自有副本）。
 * 6. **不静默降级**：密钥文件读取失败必须中止挂载并如实反馈，绝不退化成「仅主密码」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChildDatabaseSettingsWiringTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 挂载注册表使用内存偏好替身（与生产同一存储形态，且断言「不触碰其他偏好文件」） */
    private val registryFactory = RecordingContextFactory()

    private val streamSource = FakeChildDatabaseStreamSource()

    /** 挂载会话侧根库会话（与 UI 组合的同步协调器用另一个实例，避免无关联动干扰断言） */
    private val databaseSession = DatabaseSession()

    private val credentials = ChildDatabaseCredentialStore()

    private val manager = ChildDatabaseSessionManager(
        mountStore = ChildDatabaseMountStore(registryFactory.context),
        credentials = credentials,
        streamSource = streamSource,
        databaseSession = databaseSession,
        debugLog = DebugLogBuffer()
    )

    private val password = "child-master-pw".toCharArray()

    // ===================== 计数与可用性 =====================

    @Test
    fun `控制器缺失时子库计数回落为零且面板如实不可用`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(manager = null)
        collectState(viewModel)
        testScheduler.advanceUntilIdle()

        assertEquals("缺失控制器时不得谎报挂载数", 0, viewModel.uiState.value.childDatabasesCount)
        assertFalse("缺失控制器时必须如实标记不可用", viewModel.childDatabaseState.value.available)
        assertTrue(viewModel.childDatabaseState.value.mounts.isEmpty())
        assertNull("缺失控制器时不得产生任何假反馈", viewModel.childDatabaseState.value.feedback)
    }

    @Test
    fun `零挂载时计数为零且面板可用`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(manager)
        collectState(viewModel)
        testScheduler.advanceUntilIdle()

        assertEquals("零挂载的真实计数为 0（非硬编码，源于注册表）", 0, viewModel.uiState.value.childDatabasesCount)
        assertTrue(viewModel.childDatabaseState.value.mounts.isEmpty())
        assertTrue(viewModel.childDatabaseState.value.available)
    }

    // ===================== 真实挂载 / 卸载 =====================

    @Test
    fun `真实挂载后计数与展示态同步且条目数取自真实快照`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(manager)
        collectState(viewModel)
        streamSource.payload = ChildDatabaseFixtures.kdbxBytes(password)

        val mounted = manager.mount("工作子库", validLocalSource(), password, null)
        assertTrue("前置：核心层真实挂载应成功", mounted.isSuccess)

        awaitCondition(describe = "计数同步为 1") { viewModel.uiState.value.childDatabasesCount == 1 }
        awaitCondition(describe = "展示态出现该挂载") {
            viewModel.childDatabaseState.value.mounts.size == 1
        }

        val row = viewModel.childDatabaseState.value.mounts.single()
        assertEquals("工作子库", row.alias)
        val text = row.status as? ChildDatabaseStatus.Text
        assertNotNull("已解锁必须给出「已解锁，N 条条目」文案", text)
        assertEquals(R.string.dbset_child_db_opened_summary, text?.message?.resId)
        assertEquals(
            "条目数必须取自真实解密快照",
            listOf<Any>(ChildDatabaseFixtures.ENTRY_COUNT),
            text?.message?.args
        )
        assertFalse("已解锁不应再提供解锁入口", row.canRetryWithCredentials)
    }

    @Test
    fun `卸载后计数回落且子库凭据槽位清零`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(manager)
        collectState(viewModel)
        streamSource.payload = ChildDatabaseFixtures.kdbxBytes(password)

        val mountId = manager.mount("工作子库", validLocalSource(), password, null)
            .getOrNull()
            ?.id
            ?: error("前置：核心层真实挂载失败")
        awaitCondition(describe = "展示态出现该挂载") {
            viewModel.childDatabaseState.value.mounts.size == 1
        }

        viewModel.unmountChildDatabase(mountId)

        awaitCondition(describe = "卸载后计数回落为 0") { viewModel.uiState.value.childDatabasesCount == 0 }
        assertTrue(viewModel.childDatabaseState.value.mounts.isEmpty())
        assertTrue(manager.mounts.value.isEmpty())
        assertEquals("卸载必须清零该子库的凭据槽位", 0, credentials.trackedSlotCount())
        assertNull("卸载成功不应留下错误反馈", viewModel.childDatabaseState.value.feedback)
    }

    // ===================== SAF 持久化读授权 =====================

    @Test
    fun `content 来源挂载前已申请持久化读授权`() = runTest(testDispatcher) {
        val keyFileAccess = FakeKeyFileAccess()
        val viewModel = buildViewModel(manager, keyFileAccess)
        collectState(viewModel)
        streamSource.payload = ChildDatabaseFixtures.kdbxBytes(password)
        val sourceUri = "content://com.example.documents/child-db"

        val submitted = "child-master-pw".toCharArray()
        viewModel.mountChildDatabase("子库一", sourceUri, submitted, null)
        // 借用语义：调用方提交后立即擦除；挂载仍须成功（控制器已复制自有副本）
        submitted.fill('0')

        awaitCondition(describe = "content 来源经 UI 真实挂载完成") { manager.mountedCount.value == 1 }
        assertEquals(
            "SAF 选择后必须立即申请持久化读授权（否则进程重启后只能如实报不可读）",
            listOf(sourceUri),
            keyFileAccess.permissionRequests
        )
        awaitCondition(describe = "计数同步为 1") { viewModel.uiState.value.childDatabasesCount == 1 }

        val feedback = viewModel.childDatabaseState.value.feedback
        assertNotNull("挂载完成后必须有反馈（不静默）", feedback)
        assertEquals(R.string.dbset_child_db_mounted, feedback?.message?.resId)
        assertFalse("成功的挂载反馈不得按错误配色呈现", feedback?.isError ?: true)
    }

    @Test
    fun `本地来源不申请持久化读授权`() = runTest(testDispatcher) {
        val keyFileAccess = FakeKeyFileAccess()
        val viewModel = buildViewModel(manager, keyFileAccess)
        collectState(viewModel)
        streamSource.payload = ChildDatabaseFixtures.kdbxBytes(password)

        viewModel.mountChildDatabase("子库一", validLocalSource(), "child-master-pw".toCharArray(), null)

        awaitCondition(describe = "本地来源挂载完成") { manager.mountedCount.value == 1 }
        assertTrue(
            "本地绝对路径无 SAF 授权可申请（不应产生无谓的授权动作）",
            keyFileAccess.permissionRequests.isEmpty()
        )
    }

    // ===================== 已挂载 ≠ 已解锁 =====================

    @Test
    fun `根库锁定后仍计入已挂载但状态如实回落未解锁`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(manager)
        collectState(viewModel)
        streamSource.payload = ChildDatabaseFixtures.kdbxBytes(password)
        manager.mount("工作子库", validLocalSource(), password, null)
            .getOrNull() ?: error("前置：核心层真实挂载失败")
        awaitCondition(describe = "展示态出现该挂载") {
            viewModel.childDatabaseState.value.mounts.size == 1
        }

        databaseSession.lock()

        awaitCondition(describe = "锁定后状态回落「未解锁」") {
            val text = viewModel.childDatabaseState.value.mounts.singleOrNull()?.status
            (text as? ChildDatabaseStatus.Text)?.message?.resId == R.string.dbset_child_db_state_locked
        }
        assertEquals(
            "锁定只终止会话，不改变「已挂载」事实（计数仍为 1）",
            1,
            viewModel.uiState.value.childDatabasesCount
        )
        assertTrue(
            "未解锁状态必须给出真实解锁入口（不得只显示已挂载而无解锁手段）",
            viewModel.childDatabaseState.value.mounts.single().canRetryWithCredentials
        )
    }

    @Test
    fun `凭据被清零后可由用户重新提供凭据解锁`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(manager)
        collectState(viewModel)
        streamSource.payload = ChildDatabaseFixtures.kdbxBytes(password)
        val mountId = manager.mount("工作子库", validLocalSource(), password, null)
            .getOrNull()
            ?.id
            ?: error("前置：核心层真实挂载失败")
        awaitCondition(describe = "展示态出现该挂载") {
            viewModel.childDatabaseState.value.mounts.size == 1
        }

        // 根库锁定 → 子库会话终止、独立凭据通道清零（有意的安全语义）
        databaseSession.lock()
        awaitCondition(describe = "状态回落未解锁") {
            viewModel.childDatabaseState.value.mounts.singleOrNull()?.canRetryWithCredentials == true
        }

        val retyped = "child-master-pw".toCharArray()
        viewModel.unlockChildDatabase(mountId, retyped, null)
        retyped.fill('0')

        awaitCondition(describe = "重新解锁后回到已解锁") {
            val text = viewModel.childDatabaseState.value.mounts.singleOrNull()?.status
            (text as? ChildDatabaseStatus.Text)?.message?.resId == R.string.dbset_child_db_opened_summary
        }
        assertNull("重新解锁成功不应留下错误反馈", viewModel.childDatabaseState.value.feedback)
    }

    @Test
    fun `凭据被拒时如实反馈且不谎报解锁成功`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(manager)
        collectState(viewModel)
        streamSource.payload = ChildDatabaseFixtures.kdbxBytes(password)
        val mountId = manager.mount("工作子库", validLocalSource(), password, null)
            .getOrNull()
            ?.id
            ?: error("前置：核心层真实挂载失败")
        awaitCondition(describe = "展示态出现该挂载") {
            viewModel.childDatabaseState.value.mounts.size == 1
        }
        databaseSession.lock()
        awaitCondition(describe = "状态回落未解锁") {
            viewModel.childDatabaseState.value.mounts.singleOrNull()?.canRetryWithCredentials == true
        }

        viewModel.unlockChildDatabase(mountId, "wrong-pw".toCharArray(), null)

        awaitCondition(describe = "凭据被拒反馈上浮") {
            viewModel.childDatabaseState.value.feedback != null
        }
        val feedback = requireNotNull(viewModel.childDatabaseState.value.feedback)
        assertEquals(R.string.dbset_child_db_err_credential_rejected, feedback.message.resId)
        assertTrue(feedback.isError)
        val status = viewModel.childDatabaseState.value.mounts.single().status
        assertEquals(
            R.string.dbset_child_db_state_rejected,
            (status as? ChildDatabaseStatus.Text)?.message?.resId
        )
        assertEquals("凭据被拒不得摘除挂载登记", 1, viewModel.uiState.value.childDatabasesCount)
    }

    // ===================== 密钥文件（不静默降级） =====================

    @Test
    fun `密钥文件不可读时中止挂载并如实反馈`() = runTest(testDispatcher) {
        val keyFileAccess = FakeKeyFileAccess(failRead = true)
        val viewModel = buildViewModel(manager, keyFileAccess)
        collectState(viewModel)
        streamSource.payload = ChildDatabaseFixtures.kdbxBytes(password)

        viewModel.mountChildDatabase(
            "子库一",
            validLocalSource(),
            "child-master-pw".toCharArray(),
            FakeKeyFileAccess.KEY_FILE_URI
        )

        awaitCondition(describe = "密钥文件失败反馈上浮") {
            viewModel.childDatabaseState.value.feedback != null
        }
        val feedback = requireNotNull(viewModel.childDatabaseState.value.feedback)
        assertEquals(R.string.unlock_keyfile_read_failed, feedback.message.resId)
        assertTrue(feedback.isError)
        assertEquals("密钥文件读取失败必须中止挂载（不得退化为仅主密码）", 0, manager.mountedCount.value)
        assertEquals(0, viewModel.uiState.value.childDatabasesCount)
    }

    @Test
    fun `重复挂载同一来源如实报「该文件已挂载」`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(manager)
        collectState(viewModel)
        streamSource.payload = ChildDatabaseFixtures.kdbxBytes(password)
        val sourceUri = validLocalSource()
        manager.mount("子库一", sourceUri, password, null).getOrNull()
            ?: error("前置：核心层真实挂载失败")
        awaitCondition(describe = "展示态出现该挂载") {
            viewModel.childDatabaseState.value.mounts.size == 1
        }

        viewModel.mountChildDatabase("子库二", sourceUri, "child-master-pw".toCharArray(), null)

        awaitCondition(describe = "重复挂载反馈上浮") {
            viewModel.childDatabaseState.value.feedback != null
        }
        assertEquals(
            R.string.dbset_child_db_err_duplicate,
            requireNotNull(viewModel.childDatabaseState.value.feedback).message.resId
        )
        assertEquals("重复挂载不得产生第二条记录", 1, manager.mountedCount.value)
    }

    // ===================== 测试脚手架 =====================

    /** 收集两条状态流（`stateIn(WhileSubscribed)` 需有订阅者才会计算真实值） */
    private fun TestScope.collectState(viewModel: SettingsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.childDatabaseState.collect {}
        }
    }

    /**
     * 真实时间轮询等待：Main 侧 combine（StandardTestDispatcher）与
     * `Dispatchers.Default` 侧真实解密需要共同收敛，纯虚拟时间无法覆盖这一组合
     * （与 `AuthenticatorViewModelTest.awaitUiState` 同一手法）。
     */
    private fun TestScope.awaitCondition(
        describe: String,
        timeoutMs: Long = 30_000L,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            testScheduler.advanceUntilIdle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) {
                error("等待「$describe」超时（${timeoutMs}ms）")
            }
            Thread.sleep(10)
        }
    }

    private suspend fun buildViewModel(
        manager: ChildDatabaseSessionManager?,
        keyFileAccess: KeyFileAccess? = null
    ): SettingsViewModel {
        val cacheDir = Files.createTempDirectory("childdb_settings").toFile()
        val fakeContext: Context = object : ContextWrapper(null) {
            override fun getCacheDir(): File = cacheDir

            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                Proxy.newProxyInstance(
                    SharedPreferences::class.java.classLoader,
                    arrayOf(SharedPreferences::class.java)
                ) { _, method, args ->
                    if (method.name == "getString") args.getOrNull(1) else null
                } as SharedPreferences
        }
        val settingsRepository = FakeSettingsRepository().apply {
            // 关闭冷启动同步：本用例只验证子库接线，不引入无关的联网动作
            setSyncOnColdStart(false)
        }
        val credentialsStore = SyncCredentialsStore(fakeContext, null)
        val coordinator = SyncCoordinator(
            fakeContext,
            DatabaseSession(),
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
            PeriodicSyncScheduler(fakeContext, ExtendedSettingsStore(null)),
            AutofillBlocklistStore(null),
            BreachCheckCoordinator(NoOpBreachRangeClient),
            stringsProvider = TEST_STRINGS,
            childDatabaseSessionManager = manager,
            keyFileAccess = keyFileAccess
        )
    }
}
