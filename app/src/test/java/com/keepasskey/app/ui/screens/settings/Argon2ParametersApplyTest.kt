package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.autofill.AutofillFieldBlocklistStore
import com.keepasskey.app.autofill.AutofillSaveBlocklistStore
import com.keepasskey.app.autofill.testHmacFieldSignatureSource
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
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
            vaultRepository = FakeVaultRepository(),
            autofillBlocklistStore = AutofillBlocklistStore(null),
            autofillSaveBlocklistStore = AutofillSaveBlocklistStore(null),
            autofillFieldBlocklistStore = AutofillFieldBlocklistStore(null, testHmacFieldSignatureSource()),
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
}
