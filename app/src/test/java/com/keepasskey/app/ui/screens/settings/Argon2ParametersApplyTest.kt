package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.autofill.AutofillFieldBlocklistStore
import com.keepasskey.app.autofill.AutofillSaveBlocklistStore
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.screens.settings.subscreens.argon2AcceptanceRejection
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ISSUE-P2-22 整改验收（契约测试）：「Argon2 参数」对话框「应用参数」必须真实生效。
 *
 * 此前 `setArgon2Parameters` 仅回写设置页内存回显：既不更新会话头也不落盘，
 * 设备实测（字节级地面真值）确认外层头 `P` 恒为建库默认 2。
 * 本测试锁定整改后的契约：
 * ① 应用参数后会话内存头（外层头变体字典 I/M/P）与所选值一致；
 * ② 落盘文件经重派生后可由同一主密码重新解锁，且重开后的文件头参数一致；
 * ③ 错误主密码无法解开重加密后的文件（重派生真实发生，非只改头）。
 *
 * 有意使用真实 [CoroutineScope] + 真实时间轮询（非 runTest 虚拟时间）：
 * 被测链路内含 `Dispatchers.Default` 上的真实 Argon2 重派生与落盘，
 * 与 `ChildDatabaseSettingsWiringTest.awaitCondition` 同一手法。
 */
class Argon2ParametersApplyTest {

    /** 控制器真实作用域（与生产 viewModelScope 同语义；用毕取消） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun awaitCondition(describe: String, timeoutMs: Long = 60_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                error("等待「$describe」超时（${timeoutMs}ms）")
            }
            Thread.sleep(10)
        }
    }

    private fun buildController(session: DatabaseSession): SettingsPreferencesController =
        SettingsPreferencesController(
            settingsRepository = FakeSettingsRepository(),
            // ISSUE-P1-601：库级写入改经仓库统一边界 commitDatabaseMeta，替身须接真实会话方可验证落盘
            vaultRepository = FakeVaultRepository(databaseSession = session),
            autofillBlocklistStore = AutofillBlocklistStore(null),
            autofillSaveBlocklistStore = AutofillSaveBlocklistStore(null),
            autofillFieldBlocklistStore = AutofillFieldBlocklistStore(null),
            debugLogBuffer = DebugLogBuffer(),
            databaseSession = session,
            scope = scope
        )

    private fun assertKdf(kdf: KdfParameters) {
        assertTrue("KDF 应为 Argon2，实际 $kdf", kdf is KdfParameters.Argon2)
        kdf as KdfParameters.Argon2
        assertEquals(5L, kdf.iterations)
        assertEquals(32L * 1024L * 1024L, kdf.memoryInBytes)
        assertEquals(4, kdf.parallelism)
    }

    @Test
    fun `应用参数后会话头与落盘文件头均为所选值且凭据语义保持`() {
        val dir = Files.createTempDirectory("argon2_apply").toFile()
        val file = File(dir, "apply-test.kdbx")
        val password = "apply-test-password".toCharArray()

        val session = DatabaseSession()
        runBlocking {
            assertTrue(session.create(file, "apply-test", password) is KdbxResult.Success)
        }

        val controller = buildController(session)
        controller.setArgon2Parameters(iterations = 5L, memoryMb = 32L, parallelism = 4)

        // ① 会话内存头变体字典 I/M/P 与所选值一致
        awaitCondition("会话头参数更新") {
            val kdf = session.databaseFlow.value?.header?.kdfParameters
            kdf is KdfParameters.Argon2 && kdf.iterations == 5L && kdf.parallelism == 4
        }
        assertKdf(session.databaseFlow.value!!.header.kdfParameters)

        // ② 落盘完成（save 置回 OPENED）后，文件以同一主密码重开成功且头参数一致
        awaitCondition("落盘完成") {
            file.exists() && file.length() > 0 && session.state.value == DatabaseSession.SessionState.OPENED
        }
        val reopen = DatabaseSession()
        runBlocking {
            assertTrue(reopen.open(file, password) is KdbxResult.Success)
        }
        assertKdf(reopen.databaseFlow.value!!.header.kdfParameters)
        runBlocking { reopen.close() }

        // ③ 错误主密码无法解开（证明密钥按新参数真实重派生，而非只改头不改密钥）
        val wrongPassword = DatabaseSession()
        runBlocking {
            assertTrue(wrongPassword.open(file, "wrong-password".toCharArray()) is KdbxResult.Failure)
        }
        runBlocking { wrongPassword.close() }

        runBlocking { session.close() }
    }

    @Test
    fun `无活动会话时应用参数如实不生效（不产生假回显）`() {
        val controller = buildController(DatabaseSession())
        // 未打开库 → updateDatabaseMeta 与 save() 均如实 no-op/失败，回显保持空态占位 0
        controller.setArgon2Parameters(9L, 128L, 8)
        awaitCondition("调度完成") { true }
        Thread.sleep(200)
        assertEquals(0L, controller.databaseConfigState.value.argon2Iterations)
    }

    /**
     * ISSUE-P1-595：与读写侧同一判据——**写出本应用读侧会拒绝的组合必须被拒**。
     * 256 MiB × 50 = 12.5 GiB 字节·轮 > `2^33` 联合预算 ⇒ 提交前拒绝，不改会话头、不落盘、不派生。
     */
    @Test
    fun `超出联合预算的组合被拒且不改变会话与磁盘`() {
        val dir = Files.createTempDirectory("argon2_reject").toFile()
        val file = File(dir, "reject-test.kdbx")
        val password = "reject-test-password".toCharArray()

        val session = DatabaseSession()
        runBlocking {
            assertTrue(session.create(file, "reject-test", password) is KdbxResult.Success)
        }
        val controller = buildController(session)
        val before = session.databaseFlow.value!!.header.kdfParameters

        controller.setArgon2Parameters(iterations = 50L, memoryMb = 256L, parallelism = 2)
        awaitCondition("调度完成") { true }
        Thread.sleep(300)

        assertEquals("非法组合不得改变会话头", before, session.databaseFlow.value!!.header.kdfParameters)
        assertTrue("状态不得停留 DIRTY", session.state.value != DatabaseSession.SessionState.DIRTY)
        runBlocking { session.close() }
    }

    /**
     * ISSUE-P1-595：对话框「应用」按钮的禁用判据（`argon2AcceptanceRejection`）与读 / 写侧同源：
     * ① 设置页可达的越界档 256 MiB × 33~50 轮必须给出拒绝原因（联合预算面，与堆容量无关）；
     * ② MiB 换算溢出按非法处置（fail-closed）；③ 常用低配组合不得被误拒。
     */
    @Test
    fun `对话框接受域判据锁定越界档与溢出面`() {
        for (rounds in 33L..50L) {
            assertNotNull("256 MiB × $rounds 轮必须禁用「应用」", argon2AcceptanceRejection(rounds, 256L, 2))
        }
        assertNotNull("MiB 换算溢出须按非法处置", argon2AcceptanceRejection(2L, Long.MAX_VALUE, 2))
        assertNotNull("负数 MiB 须按非法处置", argon2AcceptanceRejection(2L, -1L, 2))
        assertNotNull("低于每通道下界须拒绝", argon2AcceptanceRejection(2L, 0L, 4))
        assertNull("官方默认档不得误拒", argon2AcceptanceRejection(2L, 64L, 2))
        assertNull("低配高档（64 MiB × 50）在联合预算内不得误拒", argon2AcceptanceRejection(50L, 64L, 8))
    }
}
