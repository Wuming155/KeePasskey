package com.keepasskey.app.security

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * ISSUE-P0-01 (ZT-01) 自动锁定守护单测。
 *
 * 背景：应用存在 AutofillUnlockActivity / CredentialUnlockActivity 两条不经 MainActivity 的
 * 独立冷启动入口，熄屏熔断此前仅经 MainActivity 注册 → 会话在进程存活期内无限期保持解锁。
 * 本测以纯 JVM 内核（[AutoLockSessionGuard] + 真实 [DatabaseSession]，AES-KDF 路径免 NDK）
 * 验证「不启动 MainActivity，仅经解锁入口打开会话 → 熄屏 → 会话锁定」全链路语义。
 */
class AutoLockSessionGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 模拟「仅经 Autofill / Credential 链式解锁入口打开会话」：全程无 MainActivity 参与 */
    private fun unlockViaUnlockEntry(): Pair<DatabaseSession, File> {
        val session = DatabaseSession()
        val file = File(tempFolder.root, "entry_vault.kdbx")
        val result = runBlocking {
            session.create(file, "EntryVault", ENTRY_PASSWORD, useArgon2 = false)
        }
        assertTrue("解锁入口建库应成功", result.isSuccess)
        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        return session to file
    }

    private fun newGuard(
        session: DatabaseSession,
        settings: FakeSettingsRepository = FakeSettingsRepository().apply {
            runBlocking { setLockWhenScreenOff(true) }
        }
    ): AutoLockSessionGuard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

    @Test
    fun `仅经解锁入口打开会话后熄屏即触发会话锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val guard = newGuard(session)

        guard.lockOnScreenOff()

        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue(guard.isLocked.value)
    }

    @Test
    fun `仅后台锁定开关开启时熄屏同样熔断`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setLockWhenScreenOff(false)
        settings.setAutoLockBackground(true)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        guard.lockOnScreenOff()

        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue(guard.isLocked.value)
    }

    @Test
    fun `两防护开关均关闭时熄屏不锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setLockWhenScreenOff(false)
        settings.setAutoLockBackground(false)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        guard.lockOnScreenOff()

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        assertFalse(guard.isLocked.value)
    }

    @Test
    fun `后台停留超时后切回前台即熔断`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setLockWhenScreenOff(false)
        settings.setAutoLockBackground(true)
        settings.setAutoLockTimeoutSeconds(30)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        val now = System.currentTimeMillis()
        guard.lockOnBackgroundResume(backgroundTimestamp = now - 31_000L, now = now)

        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue(guard.isLocked.value)
    }

    @Test
    fun `后台停留未达超时切回前台不锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setLockWhenScreenOff(false)
        settings.setAutoLockBackground(true)
        settings.setAutoLockTimeoutSeconds(30)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        val now = System.currentTimeMillis()
        guard.lockOnBackgroundResume(backgroundTimestamp = now - 10_000L, now = now)

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        assertFalse(guard.isLocked.value)
    }

    @Test
    fun `从未退至后台（时间戳为零）切回前台不锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setAutoLockBackground(true)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        guard.lockOnBackgroundResume(backgroundTimestamp = 0L)

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        assertFalse(guard.isLocked.value)
    }

    @Test
    fun `熄屏熔断前对未落盘修改先补存再锁定`() = runBlocking {
        val (session, file) = unlockViaUnlockEntry()
        val guard = newGuard(session)

        // 解锁后新增条目（经解锁入口场景同样成立），会话进入 DIRTY
        session.saveEntry(
            KdbxEntry(
                fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("Banking", isProtected = false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString("Bank1234", isProtected = true)
                )
            )
        )
        assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)

        guard.lockOnScreenOff()

        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)

        // best-effort 补存生效：重新打开后修改仍在
        val reopen = runBlocking { session.open(file, ENTRY_PASSWORD) }
        assertTrue("锁库前补存应已落盘", reopen.isSuccess)
        val loadedEntries = session.databaseFlow.value?.rootGroup?.allEntries()
        assertNotNull(loadedEntries)
        assertEquals("Banking", loadedEntries!!.single().title)
    }

    // ===== ISSUE-P1-592：补存失败后的独立保全（明确结果 + 恢复策略注入） =====

    private class RecordingRecovery(private val outcome: LockOutcome) : SessionRecoveryPolicy {
        var calls = 0
        override suspend fun onSaveFailedBeforeLock(): LockOutcome {
            calls++
            return outcome
        }
    }

    /** 以「保存通道必然抛异常」的方式打开会话（`saveWriter` 抛 IOException，模拟磁盘满）。 */
    private fun unlockWithFailingSave(): Pair<DatabaseSession, File> {
        val file = File(tempFolder.root, "entry_vault.kdbx")
        val seed = DatabaseSession()
        runBlocking { assertTrue(seed.create(file, "EntryVault", ENTRY_PASSWORD, useArgon2 = false).isSuccess) }
        runBlocking { seed.lock() }

        val session = DatabaseSession()
        val opened = runBlocking {
            session.openStream(
                pathIdentifier = file.absolutePath,
                inputStreamProvider = { java.io.FileInputStream(file) },
                saveWriter = { throw java.io.IOException("disk full") },
                passwordChars = ENTRY_PASSWORD
            )
        }
        assertTrue("流式打开应成功", opened.isSuccess)
        return session to file
    }

    private fun makeDirty(session: DatabaseSession) {
        runBlocking {
            session.saveEntry(
                KdbxEntry(
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString("Banking", isProtected = false),
                        KdbxConstants.Fields.PASSWORD to ProtectedString("Bank1234", isProtected = true)
                    )
                )
            )
        }
        assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)
    }

    @Test
    fun `补存成功时结果标记 Saved 且不触发恢复策略`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val recovery = RecordingRecovery(LockOutcome.RecoveryCommitted)
        val guard = AutoLockSessionGuard(session, FakeSettingsRepository().apply { setLockWhenScreenOff(true) }, DebugLogBuffer(), recovery = recovery)
        makeDirty(session)

        val outcome = guard.triggerLock("测试")

        assertEquals(LockOutcome.Saved, outcome)
        assertEquals("补存成功不得触发恢复策略", 0, recovery.calls)
        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
    }

    @Test
    fun `无未落盘修改时结果标记 NoPendingChanges`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val recovery = RecordingRecovery(LockOutcome.RecoveryCommitted)
        val guard = AutoLockSessionGuard(session, FakeSettingsRepository().apply { setLockWhenScreenOff(true) }, DebugLogBuffer(), recovery = recovery)

        val outcome = guard.triggerLock("测试")

        assertEquals(LockOutcome.NoPendingChanges, outcome)
        assertEquals(0, recovery.calls)
    }

    @Test
    fun `补存失败时触发恢复策略并回传其明确结果`() = runBlocking {
        val (session, _) = unlockWithFailingSave()
        val recovery = RecordingRecovery(LockOutcome.RecoveryCommitted)
        val guard = AutoLockSessionGuard(session, FakeSettingsRepository().apply { setLockWhenScreenOff(true) }, DebugLogBuffer(), recovery = recovery)
        makeDirty(session)

        val outcome = guard.triggerLock("测试")

        assertEquals(LockOutcome.RecoveryCommitted, outcome)
        assertEquals("补存失败必须触发一次恢复策略", 1, recovery.calls)
        assertEquals("无论保全成败，锁定照常生效", DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue(guard.isLocked.value)
    }

    @Test
    fun `全部保全失败时回传 PreservationFailed 并携带告警持久化标志`() = runBlocking {
        val (session, _) = unlockWithFailingSave()
        val recovery = RecordingRecovery(LockOutcome.PreservationFailed(alertPersisted = false))
        val guard = AutoLockSessionGuard(session, FakeSettingsRepository().apply { setLockWhenScreenOff(true) }, DebugLogBuffer(), recovery = recovery)
        makeDirty(session)

        val outcome = guard.triggerLock("测试")

        assertEquals(LockOutcome.PreservationFailed(alertPersisted = false), outcome)
        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
    }

    // ===== ENG-20261027-01：自动锁定前消费统一漂移判据，保全本地脏树到独立加密恢复副本 =====

    @Test
    fun `外部修改漂移时锁库不覆盖正式文件且保全本地脏树到独立加密恢复副本`() = runBlocking {
        val (session, file) = unlockViaUnlockEntry()
        val holder = VaultFileBaselineHolder(session)
        holder.capture(VaultFileBaseline.fromFile(file))
        val coordinator = VaultFileDriftCoordinator(holder, session)

        session.saveEntry(
            KdbxEntry(
                fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("LocalUnsavedEntry", false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString("LocalSecret123", true)
                )
            )
        )
        assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)

        val otherSession = DatabaseSession()
        val externalFile = File(tempFolder.root, "external_src.kdbx")
        var externalBytes: ByteArray? = null
        try {
            assertTrue(otherSession.create(externalFile, "ExternalVault", ENTRY_PASSWORD, useArgon2 = false).isSuccess)
            otherSession.saveEntry(
                KdbxEntry(
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString("ExternalOnlyEntry", false),
                        KdbxConstants.Fields.PASSWORD to ProtectedString("ExternalSecret456", true)
                    )
                )
            )
            externalBytes = otherSession.exportToBytes().getOrThrow()
        } finally {
            otherSession.close()
        }

        try {
            Files.write(file.toPath(), externalBytes!!)
            val bumpedTime = holder.current()!!.lastModifiedMillis + 5000L
            file.setLastModified(bumpedTime)

            assertTrue("统一漂移判据必须检出外部文件已被修改", coordinator.checkDrift(file.absolutePath, file))

            val recoveryStore = VaultRecoveryStore(null, DebugLogBuffer()).apply {
                baseDirOverride = tempFolder.newFolder("recovery")
            }
            val alertStore = RecoveryAlertStore(null)
            val recovery = SessionRecoveryCoordinator(session, recoveryStore, alertStore, DebugLogBuffer())
            val settings = FakeSettingsRepository().apply { setLockWhenScreenOff(true) }
            val guard = AutoLockSessionGuard(
                databaseSession = session,
                settingsRepository = settings,
                debugLog = DebugLogBuffer(),
                recovery = recovery,
                driftCoordinator = coordinator
            )

            val outcome = guard.triggerLock("外部漂移熔断测试")

            assertEquals(LockOutcome.RecoveryCommitted, outcome)
            assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
            assertTrue(guard.isLocked.value)

            assertArrayEquals("正式文件必须严格保持外部版本，禁止静默覆盖", externalBytes, file.readBytes())

            val reopened = DatabaseSession()
            try {
                assertTrue(reopened.open(file, ENTRY_PASSWORD).isSuccess)
                val formalEntries = reopened.databaseFlow.value?.rootGroup?.allEntries().orEmpty()
                assertTrue("正式库必须包含外部条目", formalEntries.any { it.title == "ExternalOnlyEntry" })
                assertFalse("正式库绝对不得包含未落盘本地条目", formalEntries.any { it.title == "LocalUnsavedEntry" })
            } finally {
                reopened.close()
            }

            val recoveryEntry = recoveryStore.list().single()
            assertEquals(file.absolutePath, recoveryEntry.vaultId)
            val recoveryBytes = recoveryStore.readPayload(recoveryEntry)
            assertNotNull("恢复副本密文载荷必须存在", recoveryBytes)
            try {
                val restored = KdbxFile.load(ByteArrayInputStream(recoveryBytes!!), ENTRY_PASSWORD)
                val restoredTitles = restored.rootGroup.allEntries().map { it.title }
                assertTrue("独立加密恢复副本必须完整保全本地脏树条目", restoredTitles.contains("LocalUnsavedEntry"))
                restored.clearSensitiveData()
            } finally {
                recoveryBytes!!.fill(0)
            }
        } finally {
            externalBytes?.fill(0)
        }
    }

    @Test
    fun `外部修改漂移时禁用恢复策略仍不覆盖正式文件并继续锁定`() = runBlocking {
        val (session, file) = unlockViaUnlockEntry()
        val holder = VaultFileBaselineHolder(session)
        holder.capture(VaultFileBaseline.fromFile(file))
        val coordinator = VaultFileDriftCoordinator(holder, session)

        makeDirty(session)

        val externalBytes = ByteArray(file.length().toInt() + 128) { 0x5a }
        try {
            Files.write(file.toPath(), externalBytes)
            file.setLastModified(holder.current()!!.lastModifiedMillis + 5000L)

            val settings = FakeSettingsRepository().apply { setLockWhenScreenOff(true) }
            val guard = AutoLockSessionGuard(
                databaseSession = session,
                settingsRepository = settings,
                debugLog = DebugLogBuffer(),
                recovery = SessionRecoveryPolicy.Disabled,
                driftCoordinator = coordinator
            )

            val outcome = guard.triggerLock("漂移无恢复策略测试")

            assertEquals(LockOutcome.PreservationFailed(alertPersisted = false), outcome)
            assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
            assertArrayEquals("无论保全策略成败，正式文件绝对不得被覆盖", externalBytes, file.readBytes())
        } finally {
            externalBytes.fill(0)
        }
    }

    @Test
    fun `写边界与自动锁定守卫共享同一coordinator时漂移判定同源且保全恢复副本`() = runBlocking {
        val (session, file) = unlockViaUnlockEntry()
        val holder = VaultFileBaselineHolder(session)
        holder.capture(VaultFileBaseline.fromFile(file))
        val coordinator = VaultFileDriftCoordinator(holder, session)
        val driftGuard = VaultFileDriftGuard(coordinator, null)

        val recoveryStore = VaultRecoveryStore(null, DebugLogBuffer()).apply {
            baseDirOverride = tempFolder.newFolder("recovery_boundary")
        }
        val recovery = SessionRecoveryCoordinator(session, recoveryStore, RecoveryAlertStore(null), DebugLogBuffer())
        val settings = FakeSettingsRepository().apply { setLockWhenScreenOff(true) }
        val guard = AutoLockSessionGuard(
            databaseSession = session,
            settingsRepository = settings,
            debugLog = DebugLogBuffer(),
            recovery = recovery,
            driftCoordinator = coordinator
        )

        assertFalse("写边界初始化必须就绪", driftGuard.isDrifted(file.absolutePath, file))

        makeDirty(session)

        val otherSession = DatabaseSession()
        val externalFile = File(tempFolder.root, "external_boundary.kdbx")
        var externalBytes: ByteArray? = null
        try {
            assertTrue(otherSession.create(externalFile, "ExternalVault", ENTRY_PASSWORD, useArgon2 = false).isSuccess)
            otherSession.saveEntry(KdbxEntry(fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("ExtBoundaryEntry", false))))
            externalBytes = otherSession.exportToBytes().getOrThrow()
        } finally {
            otherSession.close()
        }

        try {
            Files.write(file.toPath(), externalBytes!!)
            file.setLastModified(holder.current()!!.lastModifiedMillis + 5000L)

            assertTrue("装配后 guard 与 driftGuard 漂移判定同源", guard.isDrifted())
            assertTrue("driftGuard 自身判定也为漂移", driftGuard.isDrifted(file.absolutePath, file))

            guard.lockOnScreenOff()

            assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
            assertArrayEquals("正式文件不得被覆盖", externalBytes, file.readBytes())
            val recoveryEntry = recoveryStore.list().single()
            assertEquals(file.absolutePath, recoveryEntry.vaultId)
        } finally {
            externalBytes?.fill(0)
        }
    }

    @Test
    fun `未发生漂移时锁库成功落盘正式文件且不写入恢复副本`() = runBlocking {
        val (session, file) = unlockViaUnlockEntry()
        val holder = VaultFileBaselineHolder(session)
        holder.capture(VaultFileBaseline.fromFile(file))
        val coordinator = VaultFileDriftCoordinator(holder, session)

        makeDirty(session)
        assertFalse("未修改时不应判漂移", coordinator.checkDrift(file.absolutePath, file))

        val recoveryStore = VaultRecoveryStore(null, DebugLogBuffer()).apply {
            baseDirOverride = tempFolder.newFolder("recovery_clean")
        }
        val recovery = SessionRecoveryCoordinator(session, recoveryStore, RecoveryAlertStore(null), DebugLogBuffer())
        val guard = AutoLockSessionGuard(
            databaseSession = session,
            settingsRepository = FakeSettingsRepository().apply { setLockWhenScreenOff(true) },
            debugLog = DebugLogBuffer(),
            recovery = recovery,
            driftCoordinator = coordinator
        )

        val outcome = guard.triggerLock("无漂移保存测试")

        assertEquals(LockOutcome.Saved, outcome)
        assertTrue("正常补存不得写恢复副本", recoveryStore.list().isEmpty())
        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)

        val reopen = DatabaseSession()
        try {
            assertTrue(reopen.open(file, ENTRY_PASSWORD).isSuccess)
            assertEquals("Banking", reopen.databaseFlow.value?.rootGroup?.allEntries()?.single()?.title)
        } finally {
            reopen.close()
        }
    }

    // ===== ISSUE-P2-355 AC③：锁定丢弃未保存编辑的一次性告知 =====

    @Test
    fun `锁定时存在未保存编辑则登记一次性丢弃告知`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val registry = UnsavedEditRegistry()
        val owner = Any()
        var dirty = true
        registry.register(owner) { dirty }
        val settings = FakeSettingsRepository()
        settings.setLockWhenScreenOff(true)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer(), registry)

        guard.lockOnScreenOff()

        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue("锁定存在脏表单时必须登记告知", registry.consumeDiscardNotice())
        assertFalse("告知为一次性：再次消费必须为空", registry.consumeDiscardNotice())

        // 脏态已消失（如保存后未再编辑）：再次锁定不得重复登记
        dirty = false
        registry.markDirtyEditsDiscarded()
        assertFalse("干净表单不再登记告知", registry.consumeDiscardNotice())
        registry.unregister(owner)
    }

    @Test
    fun `锁定时无未保存编辑不登记告知`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val registry = UnsavedEditRegistry()
        val settings = FakeSettingsRepository()
        settings.setLockWhenScreenOff(true)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer(), registry)

        guard.lockOnScreenOff()

        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertFalse("无脏表单的锁定不得给出丢弃告知", registry.consumeDiscardNotice())
    }

    // ===== ISSUE-P2-13 (ZT-18/ZT-19)：自动锁定超时三档语义 =====

    @Test
    fun `永不档（-1）后台停留任意时长回前台均不锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setAutoLockBackground(true)
        settings.setAutoLockTimeoutSeconds(AutoLockTimeoutPolicy.NEVER_SECONDS)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        val now = System.currentTimeMillis()
        // 停留 24 小时（旧实现因 timeoutMillis <= 0 会立即误锁，本断言锁定 ZT-18 回归）
        guard.lockOnBackgroundResume(
            backgroundTimestamp = now - ONE_DAY_MILLIS,
            now = now
        )

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        assertFalse(guard.isLocked.value)
    }

    @Test
    fun `立即档（0）退至后台即视为超时并立即锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setAutoLockBackground(true)
        settings.setAutoLockTimeoutSeconds(AutoLockTimeoutPolicy.IMMEDIATE_SECONDS)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        val now = System.currentTimeMillis()
        // 仅离开 1 毫秒也必须锁定：0 档语义 = 退后台即超时
        guard.lockOnBackgroundResume(backgroundTimestamp = now - 1L, now = now)

        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue(guard.isLocked.value)
    }

    @Test
    fun `后台锁定开关关闭时永不档与立即档均不锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setAutoLockBackground(false)
        settings.setAutoLockTimeoutSeconds(AutoLockTimeoutPolicy.IMMEDIATE_SECONDS)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        val now = System.currentTimeMillis()
        guard.lockOnBackgroundResume(backgroundTimestamp = now - ONE_DAY_MILLIS, now = now)

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        assertFalse(guard.isLocked.value)
    }

    // ===== ISSUE-P3-366 AC②：长任务挂锁（延迟补执行，不静默丢锁） =====

    @Test
    fun `长任务挂起期间熄屏不锁定，挂起结束后补执行锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val guard = newGuard(session)

        guard.beginLongTask()
        guard.lockOnScreenOff()
        assertEquals("挂起期间不得锁定", DatabaseSession.SessionState.OPENED, session.state.value)

        guard.endLongTask()
        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue(guard.isLocked.value)
    }

    @Test
    fun `嵌套长任务仅最外层结束后才补执行锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val guard = newGuard(session)

        guard.beginLongTask()
        guard.beginLongTask()
        guard.lockOnScreenOff()
        guard.endLongTask()
        assertEquals("内层结束不得锁定", DatabaseSession.SessionState.OPENED, session.state.value)

        guard.endLongTask()
        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
    }

    @Test
    fun `挂起期间回前台超时补偿延迟至挂起结束补执行`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setAutoLockBackground(true)
        settings.setAutoLockTimeoutSeconds(30)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        val now = System.currentTimeMillis()
        guard.beginLongTask()
        guard.lockOnBackgroundResume(backgroundTimestamp = now - 31_000L, now = now)
        assertEquals("挂起期间不得锁定", DatabaseSession.SessionState.OPENED, session.state.value)

        guard.endLongTask()
        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
    }

    @Test
    fun `多余的结束不触发锁定也不抛异常`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val guard = newGuard(session)

        guard.endLongTask()
        guard.endLongTask()

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        assertFalse(guard.isLocked.value)
    }

    companion object {
        private val ENTRY_PASSWORD = "EntryPass!42".toCharArray()
        private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
