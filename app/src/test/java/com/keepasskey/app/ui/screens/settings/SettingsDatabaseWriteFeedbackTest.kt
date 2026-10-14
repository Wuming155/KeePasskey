package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.autofill.AutofillFieldBlocklistStore
import com.keepasskey.app.autofill.AutofillSaveBlocklistStore
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * `ISSUE-P2-602` 整改验收：**数据库设置不再吞掉保存失败**，界面状态与磁盘配置一致。
 *
 * 整改前 `SettingsPreferencesController` 的四个 setter 与 `SettingsDatabaseMetaController.setDatabaseMeta`
 * 只 `scope.launch { vaultRepository.commitDatabaseMeta { … } }` 而**忽略返回的 `KdbxResult`**：
 * 磁盘满 / 只读 / SAF 写回失败时界面已显示新值而磁盘仍旧，锁定后更改消失，用户无从得知。
 *
 * 本用例锁定整改后的契约（方案 §4 验收矩阵）：
 * ① 每类库设置（加密算法 / KDF / Argon2 / 回收站 / 库 Meta）注入**普通 Failure**（非抛异常）：
 *    明确回执失败、**已应用回显不变**、磁盘字节原样、状态不停留 DIRTY；
 * ② 外部漂移失败**不**叠加一次性反馈（交既有三选提示，避免与三选对话框重复）；
 * ③ 提交前参数无效如实回执且不落盘、不改会话；
 * ④ 成功路径回执成功且目标值真实落盘；
 * ⑤ 失败事务不覆盖其后的新编辑（不把旧树回滚到更新过的会话）；
 * ⑥ 写盘进行中如实呈现 busy，结果到达后释放。
 *
 * 手法与 `AlgorithmSelectionApplyTest` / `SettingsWriteBoundaryRoutingTest` 同源：
 * 真实 [DatabaseSession] + 只改 `commitDatabaseMeta` 返回值的仓库替身；使用真实
 * [CoroutineScope]（与生产 `viewModelScope` 同语义）与真实时间轮询。
 */
class SettingsDatabaseWriteFeedbackTest {

    /** 控制器真实作用域（用毕取消） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /**
     * 可切换结果的仓库替身：默认按 [failures] 决定 `commitDatabaseMeta` 的返回，不改也不落盘；
     * 关掉 `failNext` 后委托真实 [FakeVaultRepository]（含真实落盘），用于「失败→重试成功」交错。
     */
    private class SwitchableRepository(
        private val base: FakeVaultRepository,
        private val failure: KdbxResult.Failure
    ) : VaultRepository by base {
        val commitCount = AtomicInteger(0)
        var failNext = true
        /** 非空时先挂起等待（驱动「正在保存」观测），随后照常返回。 */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun commitDatabaseMeta(
            transform: (KdbxDatabase) -> KdbxDatabase
        ): KdbxResult<Unit> {
            commitCount.incrementAndGet()
            gate?.await()
            return if (failNext) failure else base.commitDatabaseMeta(transform)
        }
    }

    private fun awaitCondition(describe: String, timeoutMs: Long = 20_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("等待「$describe」超时（${timeoutMs}ms）")
            Thread.sleep(5)
        }
    }

    private fun buildController(
        session: DatabaseSession,
        repo: VaultRepository,
        controllerScope: CoroutineScope = scope
    ): SettingsPreferencesController =
        SettingsPreferencesController(
            settingsRepository = FakeSettingsRepository(),
            vaultRepository = repo,
            autofillBlocklistStore = AutofillBlocklistStore(null),
            autofillSaveBlocklistStore = AutofillSaveBlocklistStore(null),
            autofillFieldBlocklistStore = AutofillFieldBlocklistStore(null),
            debugLogBuffer = DebugLogBuffer(),
            databaseSession = session,
            scope = controllerScope
        )

    /** 建库（缺省 AES-256-CBC + Argon2id）并等待回填通道就绪（回显 = 文件头真值）。 */
    private fun createSession(name: String, password: CharArray): Pair<DatabaseSession, File> {
        val dir = Files.createTempDirectory(name).toFile()
        val file = File(dir, "$name.kdbx")
        val session = DatabaseSession()
        runBlocking {
            assertTrue(session.create(file, name, password) is KdbxResult.Success)
        }
        return session to file
    }

    private fun failure(code: String): KdbxResult.Failure =
        KdbxResult.Failure(IllegalStateException("模拟磁盘写失败"), code = code)

    /** 等回显就绪（建库缺省头），确保后续「回显不变」断言有观察对象。 */
    private fun awaitEchoReady(controller: SettingsPreferencesController) {
        awaitCondition("回显通道就绪") {
            controller.databaseConfigState.value.encryptionAlgorithm == CipherLabels.AES_256_CBC &&
                controller.databaseConfigState.value.kdfAlgorithm == KdfLabels.ARGON2ID
        }
    }

    private fun awaitFailureFeedback(controller: SettingsPreferencesController): FeedbackSnapshot {
        awaitCondition("失败回执到达") { controller.databaseConfigWriteState.value.feedback != null }
        val state = controller.databaseConfigWriteState.value
        return FeedbackSnapshot(state.feedback!!.resId, state.feedback!!.isError, state.isSaving)
    }

    private data class FeedbackSnapshot(val resId: Int, val isError: Boolean, val isSaving: Boolean)

    /** 五个库设置入口共享的失败断言：回执失败 + 回显不变 + 磁盘原样 + 不停留 DIRTY。 */
    private fun assertFailureKeepsStateConsistent(
        controller: SettingsPreferencesController,
        session: DatabaseSession,
        file: File,
        bytesBefore: ByteArray,
        echoBefore: String,
        echoReader: () -> String
    ) {
        val feedback = awaitFailureFeedback(controller)
        assertTrue("失败必须显式回执错误样式", feedback.isError)
        assertEquals("写失败按错误码映射到专用文案", R.string.err_save_failed, feedback.resId)
        assertTrue("结果已到达后 busy 必须释放", !feedback.isSaving)
        assertEquals("失败不得伪装已应用（回显保持磁盘真值）", echoBefore, echoReader())
        assertTrue("状态不得停留 DIRTY", session.state.value != DatabaseSession.SessionState.DIRTY)
        assertArrayEquals("失败不得改动磁盘字节", bytesBefore, file.readBytes())
    }

    // ================= ① 每类库设置各一条普通 Failure 注入 =================

    @Test
    fun `加密算法保存失败时不伪装已应用且回执失败`() {
        val (session, file) = createSession("cipher-fail", "cipher-fail-pw".toCharArray())
        val controller = buildController(session, SwitchableRepository(FakeVaultRepository(), failure(KdbxError.SAVE_FAILED)))
        awaitEchoReady(controller)
        val bytesBefore = file.readBytes()
        val echoBefore = controller.databaseConfigState.value.encryptionAlgorithm

        controller.setEncryptionAlgorithm(CipherLabels.CHACHA20)

        assertFailureKeepsStateConsistent(
            controller, session, file, bytesBefore, echoBefore
        ) { controller.databaseConfigState.value.encryptionAlgorithm }
        assertEquals("头也不得变化", KdbxConstants.Cipher.AES_256_CBC, session.databaseFlow.value!!.header.cipherUuid)
        runBlocking { session.close() }
    }

    @Test
    fun `KDF 算法保存失败时不伪装已应用且回执失败`() {
        val (session, file) = createSession("kdf-fail", "kdf-fail-pw".toCharArray())
        val controller = buildController(session, SwitchableRepository(FakeVaultRepository(), failure(KdbxError.SAVE_READ_ONLY)))
        awaitEchoReady(controller)
        val bytesBefore = file.readBytes()
        val echoBefore = controller.databaseConfigState.value.kdfAlgorithm

        controller.setKdfAlgorithm(KdfLabels.ARGON2D)

        awaitCondition("失败回执到达") { controller.databaseConfigWriteState.value.feedback != null }
        val feedback = controller.databaseConfigWriteState.value.feedback!!
        assertTrue(feedback.isError)
        assertEquals("只读写失败应映射到只读专用文案", R.string.err_save_read_only, feedback.resId)
        assertEquals("失败不得伪装已应用", echoBefore, controller.databaseConfigState.value.kdfAlgorithm)
        assertArrayEquals("失败不得改动磁盘字节", bytesBefore, file.readBytes())
        assertTrue("状态不得停留 DIRTY", session.state.value != DatabaseSession.SessionState.DIRTY)
        runBlocking { session.close() }
    }

    @Test
    fun `Argon2 参数保存失败时不伪装已应用且回执失败`() {
        val (session, file) = createSession("argon2-fail", "argon2-fail-pw".toCharArray())
        val controller = buildController(session, SwitchableRepository(FakeVaultRepository(), failure(KdbxError.SAVE_FAILED)))
        awaitEchoReady(controller)
        val bytesBefore = file.readBytes()
        val kdfBefore = session.databaseFlow.value!!.header.kdfParameters

        controller.setArgon2Parameters(iterations = 7L, memoryMb = 32L, parallelism = 4)

        awaitCondition("失败回执到达") { controller.databaseConfigWriteState.value.feedback != null }
        assertTrue(controller.databaseConfigWriteState.value.feedback!!.isError)
        assertEquals("失败不得改动会话头", kdfBefore, session.databaseFlow.value!!.header.kdfParameters)
        assertArrayEquals("失败不得改动磁盘字节", bytesBefore, file.readBytes())
        runBlocking { session.close() }
    }

    @Test
    fun `回收站开关保存失败时回显保持真值`() {
        val (session, file) = createSession("recycle-fail", "recycle-fail-pw".toCharArray())
        val controller = buildController(session, SwitchableRepository(FakeVaultRepository(), failure(KdbxError.SAVE_NO_WRITER)))
        awaitEchoReady(controller)
        val bytesBefore = file.readBytes()
        assertTrue("建库缺省回收站开启", session.databaseFlow.value!!.recycleBinEnabled)

        controller.setRecycleBinEnabled(false)

        awaitCondition("失败回执到达") { controller.databaseConfigWriteState.value.feedback != null }
        assertEquals(
            "无写通道应映射到专用文案",
            R.string.err_save_no_writer,
            controller.databaseConfigWriteState.value.feedback!!.resId
        )
        assertEquals("回显必须仍为磁盘真值（开启）", true, controller.databaseConfigState.value.recycleBinEnabled)
        assertEquals("会话树不得被改动", true, session.databaseFlow.value!!.recycleBinEnabled)
        assertArrayEquals("失败不得改动磁盘字节", bytesBefore, file.readBytes())
        runBlocking { session.close() }
    }

    @Test
    fun `库 Meta 保存失败时不伪装已应用且回执失败`() {
        val (session, file) = createSession("meta-fail", "meta-fail-pw".toCharArray())
        val controller = buildController(session, SwitchableRepository(FakeVaultRepository(), failure(KdbxError.SAVE_FAILED)))
        awaitEchoReady(controller)
        val bytesBefore = file.readBytes()
        val nameBefore = session.databaseFlow.value!!.databaseName

        controller.databaseMetaController.setDatabaseMeta("改名后", "新描述", null)

        awaitCondition("失败回执到达") { controller.databaseConfigWriteState.value.feedback != null }
        assertTrue(controller.databaseConfigWriteState.value.feedback!!.isError)
        assertEquals("失败不得改动库名", nameBefore, session.databaseFlow.value!!.databaseName)
        assertArrayEquals("失败不得改动磁盘字节", bytesBefore, file.readBytes())
        runBlocking { session.close() }
    }

    // ================= ② 漂移失败不叠加一次性反馈 =================

    @Test
    fun `外部漂移失败不叠加一次性反馈（由其专属三选提示承载）`() {
        val (session, file) = createSession("drift-fail", "drift-fail-pw".toCharArray())
        val drift = KdbxResult.Failure(
            IllegalStateException("Vault file modified externally"),
            userText = "已中止保存：磁盘文件已被外部修改",
            code = KdbxError.DRIFT_SAVE_ABORTED
        )
        val repo = SwitchableRepository(FakeVaultRepository(), drift)
        val controller = buildController(session, repo)
        awaitEchoReady(controller)

        controller.setRecycleBinEnabled(false)

        // 提交确已发生且写盘窗口已闭合（否则 `feedback == null` 是空转断言）
        awaitCondition("提交已发生且 busy 释放") { repo.commitCount.get() == 1 && !controller.databaseConfigWriteState.value.isSaving }
        assertNull(
            "漂移失败不得叠加一次性反馈（三选提示是它唯一的用户出口）",
            controller.databaseConfigWriteState.value.feedback
        )
        runBlocking { session.close() }
    }

    // ================= ③ 失败分型：按错误码映射，语义各不相同 =================

    @Test
    fun `失败分型按错误码映射且漂移返回空`() {
        assertEquals(R.string.err_save_read_only, libraryWriteFailureFeedback(failure(KdbxError.SAVE_READ_ONLY))!!.resId)
        assertEquals(R.string.err_save_no_writer, libraryWriteFailureFeedback(failure(KdbxError.SAVE_NO_WRITER))!!.resId)
        assertEquals(R.string.err_save_no_database, libraryWriteFailureFeedback(failure(KdbxError.SAVE_NO_DATABASE))!!.resId)
        assertEquals(
            R.string.err_save_credentials_lost,
            libraryWriteFailureFeedback(failure(KdbxError.SAVE_CREDENTIALS_LOST))!!.resId
        )
        assertEquals(R.string.err_save_failed, libraryWriteFailureFeedback(failure(KdbxError.SAVE_FAILED))!!.resId)
        assertEquals(R.string.err_unknown, libraryWriteFailureFeedback(failure(KdbxError.UNKNOWN))!!.resId)
        assertTrue("失败反馈一律错误样式", libraryWriteFailureFeedback(failure(KdbxError.SAVE_FAILED))!!.isError)
        assertNull(
            "漂移失败交三选提示，不得叠加一次性反馈",
            libraryWriteFailureFeedback(failure(KdbxError.DRIFT_SAVE_ABORTED))
        )
    }

    // ================= ④ 参数无效：不改会话与磁盘，如实回执 =================

    @Test
    fun `参数无效如实回执且不改会话与磁盘`() {
        val (session, file) = createSession("invalid-params", "invalid-params-pw".toCharArray())
        val controller = buildController(session, SwitchableRepository(FakeVaultRepository(), failure(KdbxError.SAVE_FAILED)))
        awaitEchoReady(controller)
        val bytesBefore = file.readBytes()
        val kdfBefore = session.databaseFlow.value!!.header.kdfParameters

        // 256 MiB × 50 轮 = 12.5 GiB 字节·轮 > 2^33 联合预算（与读 / 写侧同判据）
        controller.setArgon2Parameters(iterations = 50L, memoryMb = 256L, parallelism = 2)

        val state = controller.databaseConfigWriteState.value
        assertEquals("参数无效应如实回执专用文案", R.string.dbset_kdf_params_rejected, state.feedback!!.resId)
        assertTrue("参数无效反馈为错误样式", state.feedback!!.isError)
        assertTrue("未进入写盘窗口，不得停在 busy", !state.isSaving)
        assertEquals("非法组合不得改动会话头", kdfBefore, session.databaseFlow.value!!.header.kdfParameters)
        assertArrayEquals("非法组合不得改动磁盘字节", bytesBefore, file.readBytes())
        assertTrue("状态不得停留 DIRTY", session.state.value != DatabaseSession.SessionState.DIRTY)
        runBlocking { session.close() }
    }

    // ================= ⑤ 成功路径：真实落盘 + 回执成功 =================

    @Test
    fun `保存成功后回执成功且目标值真实生效`() {
        val (session, file) = createSession("success", "success-pw".toCharArray())
        val controller = buildController(session, FakeVaultRepository(databaseSession = session))
        awaitEchoReady(controller)

        controller.setRecycleBinEnabled(false)

        awaitCondition("成功回执到达") {
            controller.databaseConfigWriteState.value.feedback?.resId == R.string.dbset_save_applied
        }
        val state = controller.databaseConfigWriteState.value
        assertTrue("成功反馈不得标错误样式", !state.feedback!!.isError)
        assertTrue("结果已到达后 busy 必须释放", !state.isSaving)
        assertEquals("目标值必须真实落入会话树", false, session.databaseFlow.value!!.recycleBinEnabled)
        awaitCondition("回显随磁盘真值下发") {
            controller.databaseConfigState.value.recycleBinEnabled == false
        }
        val reopened = DatabaseSession()
        runBlocking {
            assertTrue("落盘后应以同一主密码重开成功", reopened.open(file, "success-pw".toCharArray()) is KdbxResult.Success)
        }
        assertEquals("重开文件应读回新值（真实生效，非仅内存）", false, reopened.databaseFlow.value!!.recycleBinEnabled)
        runBlocking { reopened.close() }
        runBlocking { session.close() }
    }

    // ================= ⑥ 失败不覆盖其后的新编辑 =================

    @Test
    fun `先失败后成功的交错编辑不被旧事务回滚`() {
        val (session, file) = createSession("interleave", "interleave-pw".toCharArray())
        val repo = SwitchableRepository(FakeVaultRepository(databaseSession = session), failure(KdbxError.SAVE_FAILED))
        val controller = buildController(session, repo)
        awaitEchoReady(controller)

        // 第一次：写失败（不落树、不改盘）
        controller.setEncryptionAlgorithm(CipherLabels.CHACHA20)
        awaitFailureFeedback(controller)

        // 第二次：放开替身并提交新值——失败事务不得把会话回滚成更旧的状态
        repo.failNext = false
        controller.setEncryptionAlgorithm(CipherLabels.TWOFISH_CBC)

        // 以**磁盘外层头**确认第二次保存已完成（会话内存头先变、落盘后头才落定）
        awaitCondition("第二次提交已落盘") {
            if (!file.isFile || file.length() == 0L) return@awaitCondition false
            val (header, rawHeader) = file.inputStream().use { KdbxHeader.deserialize(it) }
            try {
                header.cipherUuid == KdbxConstants.Cipher.TWOFISH &&
                    session.state.value == DatabaseSession.SessionState.OPENED
            } finally {
                rawHeader.fill(0)
            }
        }
        val reopened = DatabaseSession()
        runBlocking {
            assertTrue(reopened.open(file, "interleave-pw".toCharArray()) is KdbxResult.Success)
        }
        assertEquals(
            "磁盘应为第二次提交的新值（旧失败不得回滚覆盖）",
            KdbxConstants.Cipher.TWOFISH,
            reopened.databaseFlow.value!!.header.cipherUuid
        )
        runBlocking { reopened.close() }
        runBlocking { session.close() }
    }

    // ================= ⑦ 正在保存的过程态 =================

    @Test
    fun `写盘进行中如实呈现正在保存且结果到达后释放`() {
        val (session, _) = createSession("busy", "busy-pw".toCharArray())
        val repo = SwitchableRepository(FakeVaultRepository(databaseSession = session), failure(KdbxError.SAVE_FAILED))
        repo.failNext = false
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val controller = buildController(session, repo)
        awaitEchoReady(controller)

        controller.setRecycleBinEnabled(false)

        awaitCondition("进入正在保存") { controller.databaseConfigWriteState.value.isSaving }
        assertNull("进行中不得提前回执结果", controller.databaseConfigWriteState.value.feedback)

        gate.complete(Unit)
        awaitCondition("结果到达") {
            controller.databaseConfigWriteState.value.feedback?.resId == R.string.dbset_save_applied
        }
        assertTrue("结果到达后必须释放 busy", !controller.databaseConfigWriteState.value.isSaving)
        runBlocking { session.close() }
    }

    @Test
    fun `进入关键提交后页面退出不截断写盘`() {
        val (session, _) = createSession("cancel-mid-save", "cancel-mid-save-pw".toCharArray())
        val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = SwitchableRepository(FakeVaultRepository(databaseSession = session), failure(KdbxError.SAVE_FAILED))
        repo.failNext = false
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val controller = buildController(session, repo, pageScope)
        awaitEchoReady(controller)

        controller.setRecycleBinEnabled(false)
        awaitCondition("进入正在保存") { controller.databaseConfigWriteState.value.isSaving }

        // 模拟用户退出设置页（viewModelScope 随导航销毁）：已进入关键提交的写盘不得被截断
        pageScope.cancel()
        gate.complete(Unit)

        awaitCondition("已进入关键提交的写盘仍完成") {
            session.databaseFlow.value!!.recycleBinEnabled == false
        }
        awaitCondition("写盘结果如实回执") {
            controller.databaseConfigWriteState.value.feedback?.resId == R.string.dbset_save_applied
        }
        assertTrue("写盘结束后 busy 必须释放（不残留「正在保存」）", !controller.databaseConfigWriteState.value.isSaving)
        runBlocking { session.close() }
    }
}
