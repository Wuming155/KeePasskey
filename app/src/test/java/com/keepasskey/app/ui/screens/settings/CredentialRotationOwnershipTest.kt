package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.data.repository.*
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.security.AutoLockHoldoff
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.session.CredentialRotationCancellationException
import com.keepasskey.database.session.SessionWriteReceipt
import com.keepasskey.database.session.SessionCredentialRequest
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.core.model.KdbxGroup
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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

    @Test fun `应用作用域取消导致launch未进入时输入和挂锁均收尾`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val holdoff = AutoLockHoldoff()
        val runtime = CredentialRotationRuntime(AutoLockSessionGuard(
            DatabaseSession(), FakeSettingsRepository(), DebugLogBuffer(), holdoff = holdoff))
        runtime.scope.cancel()
        val controller = SettingsMasterKeyChangeController(FakeVaultRepository(), backgroundScope, runtime = runtime)
        val password = "Queued#NeverRun".toCharArray()
        try {
            controller.submit(password)
            runCurrent()
            assertTrue(password.all { it == '0' })
            assertFalse(controller.state.value.isChanging)
            assertFalse(holdoff.isHolding)
        } finally {
            MainDispatcherGuard.tearDown()
        }
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

    @Test fun `排队后活动库与同步目标变化仍按受理目录快照收尾`() = runTest {
        val session = DatabaseSession()
        session.setDatabaseForTesting(KdbxDatabase(
            KdbxHeader.createDefault(useArgon2 = false), rootGroup = KdbxGroup(name = "Admission")
        ))
        val ticket = session.captureCredentialRequest().ticket!!
        var catalog = com.keepasskey.app.ui.model.VaultDatabaseInfo("A", "A", ticket.pathIdentifier, isRemote = true)
        var target = "remote-A"
        var activeId = "A"
        var committedId: String? = null
        var committedTarget: String? = null
        var requestTicket: Any? = null
        val repository = object : VaultRepository by FakeVaultRepository() {
            override fun databaseInfoForPath(path: String) = catalog.takeIf { it.path == path }
            override suspend fun changeMasterPassword(password: CharArray, keyFileIntent: ChangeKeyFileIntent): KdbxResult<Unit> =
                KdbxResult.Success(Unit)
        }
        val controller = SettingsMasterKeyChangeController(
            repository = repository,
            scope = backgroundScope,
            credentialSession = session,
            activeDbId = { activeId },
            syncBound = { activeId == "B" },
            captureSyncTarget = { target },
            completionWithRequest = { request, id, _, _, syncTarget ->
                requestTicket = request.ticket
                committedId = id
                committedTarget = syncTarget
                true
            }
        )
        controller.submit("Admission#Password".toCharArray())
        catalog = com.keepasskey.app.ui.model.VaultDatabaseInfo("B", "B", "other.kdbx", isRemote = false)
        activeId = "B"
        target = "remote-B"
        runCurrent()
        assertSame(ticket, requestTicket)
        assertEquals("A", committedId)
        assertEquals("remote-A", committedTarget)
        assertFalse(controller.state.value.isChanging)
    }

    @Test fun `云端标记失败仍报告本地成功并提示旧副本`() = runTest {
        val session = DatabaseSession()
        session.setDatabaseForTesting(KdbxDatabase(
            KdbxHeader.createDefault(useArgon2 = false), rootGroup = KdbxGroup(name = "CloudMark")
        ))
        val ticket = session.captureCredentialRequest().ticket!!
        val repository = object : VaultRepository by FakeVaultRepository() {
            override fun databaseInfoForPath(path: String) = com.keepasskey.app.ui.model.VaultDatabaseInfo(
                "A", "A", ticket.pathIdentifier, isRemote = true
            )
            override suspend fun changeMasterPassword(password: CharArray, keyFileIntent: ChangeKeyFileIntent): KdbxResult<Unit> =
                KdbxResult.Success(Unit)
        }
        val password = "Cloud#MarkFake".toCharArray()
        val controller = SettingsMasterKeyChangeController(
            repository = repository,
            scope = backgroundScope,
            credentialSession = session,
            captureSyncTarget = { "remote-A" },
            completionWithRequest = { _, _, _, _, _ -> false },
            strings = StringsProvider { id, _ -> "res:$id" }
        )
        controller.submit(password)
        runCurrent()
        assertFalse(controller.state.value.isChanging)
        assertTrue(password.all { it == '0' })
        assertEquals(R.string.set_master_key_updated, controller.state.value.feedback?.resId)
        assertEquals("res:${R.string.set_master_key_cloud_mark_failed_note}", controller.state.value.feedback?.args?.get(0))
    }

    @Test fun `withContext普通取消仍运行提交收尾并固定A的归属`() = runTest {
        val session = DatabaseSession()
        val oldPassword = "Old#TicketPassword".toCharArray()
        val newPassword = "New#TicketPassword".toCharArray()
        var disk = byteArrayOf()
        try {
            val created = session.createStream(
                pathIdentifier = "/virtual/A.kdbx",
                name = "A",
                passwordChars = oldPassword,
                useArgon2 = false,
                keyFileData = null,
                cipherUuid = KdbxConstants.Cipher.AES_256_CBC,
                writeNew = { bytes -> disk = bytes.copyOf() },
                saveWriter = { bytes -> disk = bytes.copyOf() },
                trackedSaveWriter = { bytes, receipt ->
                    receipt.writing()
                    disk.fill(0)
                    disk = bytes.copyOf()
                    receipt.committed()
                    withContext(Dispatchers.IO) {
                        currentCoroutineContext().cancel(CancellationException("提交后的普通取消"))
                        currentCoroutineContext().ensureActive()
                    }
                }
            )
            assertTrue(created is KdbxResult.Success)
            val oldTicket = session.captureCredentialRequest().ticket!!
            var activeId = "A"
            var target = "remote-A"
            var finishCalls = 0
            var observedRequest: SessionCredentialRequest? = null
            val repository = object : VaultRepository by FakeVaultRepository() {
                override fun databaseInfoForPath(path: String) = com.keepasskey.app.ui.model.VaultDatabaseInfo(
                    "A", "A", path, isRemote = true
                )
                override suspend fun changeMasterPassword(
                    password: CharArray, keyFileIntent: ChangeKeyFileIntent
                ): KdbxResult<Unit> {
                    observedRequest = currentCoroutineContext().fold(null as SessionCredentialRequest?) { found, element ->
                        if (element is SessionCredentialRequest) element else found
                    }
                    return session.changeCredentials(password)
                }
            }
            val controller = SettingsMasterKeyChangeController(
                repository = repository,
                scope = backgroundScope,
                credentialSession = session,
                activeDbId = { activeId },
                captureSyncTarget = { target },
                completionWithRequest = { request, databaseId, _, _, syncTarget ->
                    finishCalls++
                    assertSame(oldTicket, request.ticket)
                    assertEquals("A", databaseId)
                    assertEquals("remote-A", syncTarget)
                    assertEquals(SessionWriteReceipt.Status.COMMITTED, request.writeStatus)
                    assertSame(session.captureCredentialRequest().ticket, request.committedTicket)
                    false
                },
                strings = StringsProvider { id, _ -> "res:$id" }
            )
            val submitted = newPassword.copyOf()
            controller.submit(submitted)
            activeId = "B"
            target = "remote-B"
            runCurrent()
            withContext(Dispatchers.Default) {
                withTimeout(20_000) { controller.state.first { !it.isChanging } }
            }
            assertTrue(submitted.all { it == '0' })
            assertEquals(SessionWriteReceipt.Status.COMMITTED, observedRequest?.writeStatus)
            assertSame(session.captureCredentialRequest().ticket, observedRequest?.committedTicket)
            assertEquals(1, finishCalls)
            assertFalse(controller.state.value.isChanging)
            assertEquals(R.string.set_master_key_updated, controller.state.value.feedback?.resId)
            assertEquals(
                "res:${R.string.set_master_key_cloud_mark_failed_note}",
                controller.state.value.feedback?.args?.get(0)
            )
            session.useCredentials { password, _ -> assertArrayEquals(newPassword, password) }
        } finally {
            session.close()
            oldPassword.fill('0')
            newPassword.fill('0')
            disk.fill(0)
        }
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
