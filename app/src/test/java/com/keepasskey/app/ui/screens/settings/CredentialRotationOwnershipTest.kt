package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.data.repository.*
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.AutoLockHoldoff
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.session.CredentialRotationCancellationException
import com.keepasskey.database.session.SessionWriteReceipt
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 任务先拥有输入；页面取消或 launch 一次未进入均不能遗留自有凭据。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialRotationOwnershipTest {
    @Test fun `复制密码和因子再擦调用者且关闭只擦私有副本`() {
        val password = "Fake#Input".toCharArray()
        val key = byteArrayOf(1, 2, 3)
        val owned = CredentialRotationInput.copyAndEraseCaller(password, ChangeKeyFileIntent.Use(key))
        try {
            assertTrue(password.all { it == '0' })
            assertTrue(key.all { it == 0.toByte() })
            assertArrayEquals("Fake#Input".toCharArray(), owned.password)
            assertArrayEquals(byteArrayOf(1, 2, 3), (owned.keyFile as ChangeKeyFileIntent.Use).bytes)
        } finally { owned.close() }
        assertTrue(owned.password.all { it == '0' })
        assertTrue((owned.keyFile as ChangeKeyFileIntent.Use).bytes.all { it == 0.toByte() })
    }

    @Test fun `取消页面作用域不取消应用任务且挂锁成对释放`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val holdoff = AutoLockHoldoff()
        val runtime = CredentialRotationRuntime(AutoLockSessionGuard(
            DatabaseSession(), FakeSettingsRepository(), DebugLogBuffer(), holdoff = holdoff))
        val page = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var borrowed: CharArray? = null
        val repository = object : VaultRepository by FakeVaultRepository() {
            override suspend fun changeMasterPassword(password: CharArray, keyFileIntent: ChangeKeyFileIntent): KdbxResult<Unit> {
                borrowed = password
                entered.complete(Unit)
                release.await()
                assertArrayEquals("Fake#Lifecycle".toCharArray(), password)
                return KdbxResult.Success(Unit)
            }
        }
        val controller = SettingsMasterKeyChangeController(repository, page, runtime = runtime, activeDbId = { "A" })
        try {
            controller.submit("Fake#Lifecycle".toCharArray())
            runCurrent()
            assertTrue(entered.isCompleted)
            assertTrue(holdoff.isHolding)
            page.cancel()
            runCurrent()
            assertTrue(controller.state.value.isChanging)
            assertArrayEquals("Fake#Lifecycle".toCharArray(), borrowed)
            release.complete(Unit)
            advanceUntilIdle()
            assertFalse(controller.state.value.isChanging)
            assertFalse(holdoff.isHolding)
            assertTrue(borrowed!!.all { it == '0' })
            assertNotNull(controller.state.value.feedback)
        } finally {
            page.cancel()
            runtime.scope.cancel()
            MainDispatcherGuard.tearDown()
        }
    }

    @Test fun `launch未进入也擦除输入并复位忙态`() = runTest {
        val page = CoroutineScope(Job().apply { cancel() } + StandardTestDispatcher(testScheduler))
        val controller = SettingsMasterKeyChangeController(FakeVaultRepository(), page)
        val password = "Never#Entered".toCharArray()
        val bytes = byteArrayOf(5, 6)
        controller.submit(password, ChangeKeyFileIntent.Use(bytes))
        advanceUntilIdle()
        assertTrue(password.all { it == '0' })
        assertTrue(bytes.all { it == 0.toByte() })
        assertFalse(controller.state.value.isChanging)
    }

    @Test fun `已提交取消仍执行捕获目标收尾再释放输入`() = runTest {
        val target = "captured-A"
        var completedTarget: String? = null
        var intactAtCompletion = false
        val repository = object : VaultRepository by FakeVaultRepository() {
            override suspend fun changeMasterPassword(password: CharArray, keyFileIntent: ChangeKeyFileIntent): KdbxResult<Unit> {
                throw CredentialRotationCancellationException(SessionWriteReceipt.Status.COMMITTED, CancellationException("返回取消"))
            }
        }
        val controller = SettingsMasterKeyChangeController(repository, backgroundScope,
            activeDbId = { "A" }, captureSyncTarget = { target },
            completion = { id, _, password, syncTarget ->
                assertEquals("A", id)
                completedTarget = syncTarget
                intactAtCompletion = password!!.contentEquals("Committed#Fake".toCharArray())
            })
        val password = "Committed#Fake".toCharArray()
        controller.submit(password)
        runCurrent()
        assertEquals(target, completedTarget)
        assertTrue(intactAtCompletion)
        assertTrue(password.all { it == '0' })
        assertFalse(controller.state.value.isChanging)
        assertNotNull(controller.state.value.feedback)
    }
    @Test fun `首任务完成触发下一受理时旧完成钩子不得覆盖新忙态`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val runtime = CredentialRotationRuntime(AutoLockSessionGuard(
            DatabaseSession(), FakeSettingsRepository(), DebugLogBuffer()))
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        val repository = object : VaultRepository by FakeVaultRepository() {
            override suspend fun changeMasterPassword(newPassword: CharArray, keyFileIntent: ChangeKeyFileIntent): KdbxResult<Unit> {
                calls++
                if (calls == 2) pending.await()
                return KdbxResult.Success(Unit)
            }
        }
        val controller = SettingsMasterKeyChangeController(repository, backgroundScope,
            runtime = runtime, activeDbId = { "A" })
        val listener = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.state.drop(1).filter { !it.isChanging }.take(1).collect {
                controller.submit("Second#Task".toCharArray())
            }
        }
        try {
            controller.submit("First#Task".toCharArray())
            runCurrent()
            assertEquals(2, calls)
            assertTrue("第二次写入仍挂起，忙态必须属于第二个任务", controller.state.value.isChanging)
            pending.complete(Unit)
            runCurrent()
            assertFalse(controller.state.value.isChanging)
        } finally {
            listener.cancel()
            runtime.scope.cancel()
            MainDispatcherGuard.tearDown()
        }
    }

}
