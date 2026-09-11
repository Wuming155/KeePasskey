package com.keepasskey.app.data.childdb

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.app.security.FakeUnlockThrottleStore
import com.keepasskey.app.security.UnlockThrottleManager
import com.keepasskey.app.security.UnlockThrottlePolicy
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子库挂载编排单测（设计要点 1 / 3 / 6 的端到端证据）。
 *
 * 覆盖：挂载成功（真实解密 + 计数 + 投影）→ 失败不留痕 → 去重 → 卸载 →
 * 凭据失效与重试 → 根库锁定联动（含经 [AutoLockSessionGuard] 熄屏熔断的同一触发点）→
 * 进程重启后状态回落 → 挂载不改动根库会话与对象树。
 */
class ChildDatabaseSessionManagerTest {

    private val factory = RecordingContextFactory()

    private val credentials = ChildDatabaseCredentialStore()

    private val databaseSession = DatabaseSession()

    private val source = FakeChildDatabaseStreamSource()

    private val mountStore = ChildDatabaseMountStore(factory.context)

    private val password = "child-master-pw".toCharArray()

    // ISSUE-P2-17：子库解锁节流状态机（复用主库同一实现，按 mountId 计次）
    private val throttleStore = FakeUnlockThrottleStore()

    private fun newManager(): ChildDatabaseSessionManager = ChildDatabaseSessionManager(
        mountStore = mountStore,
        credentials = credentials,
        streamSource = source,
        databaseSession = databaseSession,
        debugLog = DebugLogBuffer(),
        unlockThrottleManager = UnlockThrottleManager(throttleStore)
    )

    private fun reasonOf(result: KdbxResult<*>): ChildDatabaseFailureReason =
        ChildDatabaseFailureReason.of((result as KdbxResult.Failure).error)

    /** 以真实密文作来源并完成一次成功挂载 */
    private suspend fun mountOnce(manager: ChildDatabaseSessionManager): ChildDatabaseMount {
        source.payload = ChildDatabaseFixtures.kdbxBytes(password)
        val result = manager.mount("工作子库", validLocalSource(), password, null)
        assertTrue("挂载应成功但实际失败: ${result.isFailure}", result.isSuccess)
        return result.getOrThrow()
    }

    @Test
    fun `挂载成功后完成首次解密并对齐计数与投影`() = runTest {
        val manager = newManager()

        val record = mountOnce(manager)

        assertTrue("首版恒为只读挂载", record.readOnly)
        assertEquals(1, manager.mountedCount.value)
        assertEquals(listOf(record), manager.mounts.value)
        val state = manager.stateOf(record.id)
        assertTrue("状态应为已打开: $state", state is ChildDatabaseMountState.Opened)
        val snapshot = requireNotNull(manager.snapshotOf(record.id)) { "打开后应有只读投影" }
        assertEquals(ChildDatabaseFixtures.DATABASE_NAME, snapshot.databaseName)
        assertEquals(ChildDatabaseFixtures.ENTRY_COUNT, snapshot.entries.size)
        assertEquals(ChildDatabaseFixtures.ENTRY_COUNT, manager.projectedEntries.value.size)
        assertTrue(manager.projectedEntries.value.all { it.mountId == record.id })
        assertTrue(manager.projectedEntries.value.all { it.mountAlias == "工作子库" })
    }

    @Test
    fun `挂载失败不留半成品记录且不保留凭据`() = runTest {
        val manager = newManager()
        source.payload = ChildDatabaseFixtures.kdbxBytes(password)

        val result = manager.mount("工作子库", validLocalSource(), "wrong-pw".toCharArray(), null)

        assertTrue(result.isFailure)
        assertEquals(ChildDatabaseFailureReason.CREDENTIAL_REJECTED, reasonOf(result))
        assertEquals("失败不得计入已挂载", 0, manager.mountedCount.value)
        assertTrue(manager.mounts.value.isEmpty())
        assertEquals("失败路径不得留下凭据槽位", 0, credentials.trackedSlotCount())
        assertTrue(manager.projectedEntries.value.isEmpty())
    }

    @Test
    fun `同一来源重复挂载被拒且计数不变`() = runTest {
        val manager = newManager()
        val record = mountOnce(manager)

        val duplicated = manager.mount("另一个别名", record.sourceUri, password, null)

        assertEquals(ChildDatabaseFailureReason.DUPLICATE_MOUNT, reasonOf(duplicated))
        assertEquals(1, manager.mountedCount.value)
    }

    @Test
    fun `别名或来源非法时直接拒绝`() = runTest {
        val manager = newManager()

        assertEquals(
            ChildDatabaseFailureReason.INVALID_ALIAS,
            reasonOf(manager.mount("   ", validLocalSource(), password, null))
        )
        assertEquals(
            ChildDatabaseFailureReason.INVALID_SOURCE,
            reasonOf(manager.mount("别名", "relative/child.txt", password, null))
        )
        assertEquals(0, manager.mountedCount.value)
        assertEquals("未挂载时不得有任何凭据槽位", 0, credentials.trackedSlotCount())
    }

    @Test
    fun `卸载终止会话清零凭据并幂等`() = runTest {
        val manager = newManager()
        val record = mountOnce(manager)
        assertEquals(1, credentials.trackedSlotCount())

        val removed = manager.unmount(record.id)

        assertTrue(removed.isSuccess)
        assertEquals(0, manager.mountedCount.value)
        assertTrue(manager.mounts.value.isEmpty())
        assertEquals(ChildDatabaseMountState.Closed, manager.stateOf(record.id))
        assertEquals("卸载必须走独立清零路径", 0, credentials.trackedSlotCount())
        assertTrue(manager.projectedEntries.value.isEmpty())

        assertEquals(
            ChildDatabaseFailureReason.MOUNT_NOT_FOUND,
            reasonOf(manager.unmount(record.id))
        )
    }

    @Test
    fun `凭据失效后状态如实流转且可重新提供凭据打开`() = runTest {
        val manager = newManager()
        val record = mountOnce(manager)

        val rejected = manager.open(record.id, "wrong-pw".toCharArray(), null)

        assertEquals(ChildDatabaseFailureReason.CREDENTIAL_REJECTED, reasonOf(rejected))
        assertEquals(ChildDatabaseMountState.CredentialRejected, manager.stateOf(record.id))
        assertEquals("错误凭据不得保留", 0, credentials.trackedSlotCount())
        assertTrue(manager.projectedEntries.value.isEmpty())
        assertEquals("挂载登记不因解密失败而丢失", 1, manager.mountedCount.value)

        val retry = manager.open(record.id, password, null)

        assertTrue(retry.isSuccess)
        assertEquals(ChildDatabaseFixtures.ENTRY_COUNT, manager.projectedEntries.value.size)
        assertEquals(1, credentials.trackedSlotCount())
    }

    @Test
    fun `未登记挂载的打开与刷新如实报不存在`() = runTest {
        val manager = newManager()

        assertEquals(
            ChildDatabaseFailureReason.MOUNT_NOT_FOUND,
            reasonOf(manager.open("不存在的挂载", password, null))
        )
        assertEquals(
            ChildDatabaseFailureReason.MOUNT_NOT_FOUND,
            reasonOf(manager.refresh("不存在的挂载"))
        )
    }

    @Test
    fun `根库锁定时子库会话终止凭据清零但登记保留`() = runTest {
        val manager = newManager()
        val record = mountOnce(manager)
        assertEquals(1, credentials.trackedSlotCount())

        databaseSession.lock()

        assertEquals(ChildDatabaseMountState.Closed, manager.stateOf(record.id))
        assertEquals("锁定必须清零独立凭据通道", 0, credentials.trackedSlotCount())
        assertEquals("挂载登记属非敏感配置，锁定后仍保留", 1, manager.mountedCount.value)
        assertTrue(manager.projectedEntries.value.isEmpty())
        assertNull(manager.snapshotOf(record.id))
        // 锁后刷新如实报凭据缺失，绝不隐式试探或复用根库凭据
        assertEquals(
            ChildDatabaseFailureReason.CREDENTIAL_MISSING,
            reasonOf(manager.refresh(record.id))
        )
    }

    @Test
    fun `熄屏熔断经同一触发点一并终止子库会话`() = runTest {
        val manager = newManager()
        val record = mountOnce(manager)
        val settings = FakeSettingsRepository()
        settings.setLockWhenScreenOff(true)
        val guard = AutoLockSessionGuard(databaseSession, settings, DebugLogBuffer())

        guard.lockOnScreenOff()

        assertTrue(guard.isLocked.value)
        assertEquals(DatabaseSession.SessionState.LOCKED, databaseSession.state.value)
        assertEquals(ChildDatabaseMountState.Closed, manager.stateOf(record.id))
        assertEquals(0, credentials.trackedSlotCount())
    }

    @Test
    fun `进程重启后登记保留但状态回落为 Closed 且刷新如实报凭据缺失`() = runTest {
        val manager = newManager()
        val record = mountOnce(manager)
        val openedBefore = source.openCount

        // 模拟进程重启：注册表（持久化）复用，凭据通道与会话全新
        val restarted = ChildDatabaseSessionManager(
            mountStore = ChildDatabaseMountStore(factory.context),
            credentials = ChildDatabaseCredentialStore(),
            streamSource = source,
            databaseSession = databaseSession,
            debugLog = DebugLogBuffer(),
            unlockThrottleManager = UnlockThrottleManager(throttleStore)
        )

        assertEquals(1, restarted.mountedCount.value)
        assertEquals(listOf(record), restarted.mounts.value)
        assertEquals(ChildDatabaseMountState.Closed, restarted.stateOf(record.id))
        assertEquals(
            ChildDatabaseFailureReason.CREDENTIAL_MISSING,
            reasonOf(restarted.refresh(record.id))
        )
        assertEquals("无凭据时不得读取来源", openedBefore, source.openCount)
    }

    @Test
    fun `挂载不改动根库会话与根库对象树`() = runTest {
        val rootDatabase = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "根库",
            rootGroup = KdbxGroup(name = "根库")
        )
        databaseSession.setDatabaseForTesting(rootDatabase)
        val manager = newManager()

        val record = mountOnce(manager)

        assertSame("根库对象树实例不得被替换", rootDatabase, databaseSession.databaseFlow.value)
        assertEquals(DatabaseSession.SessionState.OPENED, databaseSession.state.value)
        assertEquals("子库条目绝不写入根库树", 0, rootDatabase.rootGroup.allEntries().size)
        assertEquals(
            "本阶段不向根库写入任何挂载元数据",
            emptyMap<String, String>(),
            rootDatabase.rootGroup.customData
        )
        assertNull("挂载不得改变根库同步来源", databaseSession.currentFile)
        assertNull(databaseSession.currentPathIdentifier)
        assertEquals(1, manager.mountedCount.value)
        assertFalse(record.id.isBlank())
    }

    // ===== ISSUE-P2-17：子库解锁节流 =====

    @Test
    fun `子库口令连续失败达阈值后锁定期内不再进入解密管线`() = runTest {
        val manager = newManager()
        val record = mountOnce(manager)

        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD) {
            assertEquals(
                ChildDatabaseFailureReason.CREDENTIAL_REJECTED,
                reasonOf(manager.open(record.id, "wrong-pw".toCharArray(), null))
            )
        }
        assertEquals(
            UnlockThrottlePolicy.FAILURE_THRESHOLD,
            throttleStore.read(record.id).failureCount
        )

        // 锁定期内闸门 fail-closed：返回 THROTTLED 且绝不读取来源（不进入 KdbxFile.load）
        val openedBefore = source.openCount
        val throttled = manager.open(record.id, password, null)
        assertEquals(ChildDatabaseFailureReason.THROTTLED, reasonOf(throttled))
        assertEquals("锁定期内不得进入解密管线", openedBefore, source.openCount)
    }

    @Test
    fun `子库成功解锁清零节流计数`() = runTest {
        val manager = newManager()
        val record = mountOnce(manager)

        repeat(2) { manager.open(record.id, "wrong-pw".toCharArray(), null) }
        assertEquals(2, throttleStore.read(record.id).failureCount)

        val success = manager.open(record.id, password, null)

        assertTrue("正确口令应解锁成功", success.isSuccess)
        assertEquals(0, throttleStore.read(record.id).failureCount)
    }

    @Test
    fun `子库非认证失败不计入节流`() = runTest {
        val manager = newManager()
        val record = mountOnce(manager)

        source.failure = java.io.FileNotFoundException("测试：来源不可读")
        val failed = manager.open(record.id, password, null)

        assertEquals(ChildDatabaseFailureReason.SOURCE_UNAVAILABLE, reasonOf(failed))
        assertEquals("IO / 来源失败不得计入节流", 0, throttleStore.read(record.id).failureCount)
    }
}
