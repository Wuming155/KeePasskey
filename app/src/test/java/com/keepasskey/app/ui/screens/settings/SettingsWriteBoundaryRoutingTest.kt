package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.autofill.AutofillFieldBlocklistStore
import com.keepasskey.app.autofill.AutofillSaveBlocklistStore
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * `ISSUE-P1-601` 接线回归锁：设置控制器**只提交意图**（经 `VaultRepository.commitDatabaseMeta`），
 * **不再**直接调用 `DatabaseSession.updateDatabaseMeta / save`。
 *
 * 手法：传入一个只记录 `commitDatabaseMeta` 调用、**不落任何写**的仓库替身 + 真实会话。
 * 若控制器仍直连会话（旧实现），会话树会被就地改写 ⇒ 本用例红；若已收口到统一边界，
 * 会话树保持不变、且边界被调用一次 ⇒ 绿。
 */
class SettingsWriteBoundaryRoutingTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class RecordingRepository(private val delegate: VaultRepository) : VaultRepository by delegate {
        val commitCount = AtomicInteger(0)
        override suspend fun commitDatabaseMeta(
            transform: (KdbxDatabase) -> KdbxDatabase
        ): KdbxResult<Unit> {
            // 只记录意图，不落写——用于区分「经边界提交」与「直连会话写」两种路径
            commitCount.incrementAndGet()
            return KdbxResult.Success(Unit)
        }
    }

    private fun buildController(session: DatabaseSession, repo: VaultRepository): SettingsPreferencesController =
        SettingsPreferencesController(
            settingsRepository = FakeSettingsRepository(),
            vaultRepository = repo,
            autofillBlocklistStore = AutofillBlocklistStore(null),
            autofillSaveBlocklistStore = AutofillSaveBlocklistStore(null),
            autofillFieldBlocklistStore = AutofillFieldBlocklistStore(null),
            debugLogBuffer = DebugLogBuffer(),
            databaseSession = session,
            scope = scope
        )

    @Test
    fun `回收站开关经统一边界提交而不直连会话写`() {
        val dir = Files.createTempDirectory("routing").toFile()
        val file = File(dir, "routing.kdbx")
        val session = DatabaseSession()
        runBlocking {
            assertTrue(session.create(file, "routing", "routing-password".toCharArray()) is KdbxResult.Success)
        }
        val repo = RecordingRepository(FakeVaultRepository())
        val controller = buildController(session, repo)

        assertEquals("建库缺省回收站应开启", true, session.databaseFlow.value!!.recycleBinEnabled)

        controller.setRecycleBinEnabled(false)

        // 等待调度完成（边界替身同步返回，无需轮询真实落盘）
        Thread.sleep(200)
        assertEquals("设置意图必须且仅经统一边界提交一次", 1, repo.commitCount.get())
        assertEquals(
            "控制器不得直连会话写：替身未落盘，会话树必须原样",
            true,
            session.databaseFlow.value!!.recycleBinEnabled
        )
        runBlocking { session.close() }
    }
}
