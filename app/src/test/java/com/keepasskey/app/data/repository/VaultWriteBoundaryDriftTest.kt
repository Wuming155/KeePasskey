package com.keepasskey.app.data.repository

import android.content.ContextWrapper
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.app.security.VaultFileBaseline
import com.keepasskey.app.security.VaultFileBaselineHolder
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.security.VaultFileDriftGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.exception.KdbxSessionChangedException
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * `ISSUE-P1-601` 整改验收（宿主可测面）：**库级写入统一提交边界** `VaultWriteBoundary` 的
 * 「漂移检查 → 候选变换 → 落盘」次序，以及换密 / 改绑入口共享同一份写前漂移判据。
 *
 * 此前漂移保护只覆盖 `RealVaultRepository.persistSession`（条目 / 分组编辑），设置页算法 /
 * KDF / Argon2 / 回收站、库 Meta 编辑与换密 / 改绑各自直连 `DatabaseSession.updateDatabaseMeta
 * + save` / `changeCredentials`，绕过漂移检查——解锁后文件被外部修改，再改设置或改密会把
 * 外部新版本**无提示覆盖**。
 *
 * 覆盖：
 * ① 外部修改（size 变化）⇒ `commitMeta` fail-closed 中止、**不应用**意图（内存树不变）、
 *    外部文件字节原样保留、并唤起三选提示；
 * ② 无漂移 ⇒ 提交真实落盘且成功后刷新基线，二次提交不误报漂移；
 * ③ `VaultLifecycleCoordinator` 换密 / 改绑入口在漂移时**先拒绝**（返回同一失败实例，
 *    不进入 `changeCredentials`）。
 *
 * **如实声明**：本用例覆盖本地 `File` 通道 + 真实 `DatabaseSession` 的边界行为；
 * SAF（`content://`）通道与真机写回按既有 `VaultFileDriftMetadataTest` 的限界声明，
 * 需真机走查（无 Robolectric，宿主不模拟 `ContentResolver`）。
 */
class VaultWriteBoundaryDriftTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var session: DatabaseSession
    private lateinit var file: File
    private lateinit var holder: VaultFileBaselineHolder
    private lateinit var coordinator: VaultFileDriftCoordinator
    private lateinit var boundary: VaultWriteBoundary

    private fun setupVault() {
        file = File(tempFolder.root, "boundary.kdbx")
        session = DatabaseSession()
        runBlocking {
            assertTrue(
                "建库应成功",
                session.create(file, "boundary", "boundary-password".toCharArray()) is KdbxResult.Success
            )
        }
        holder = VaultFileBaselineHolder(session)
        // ISSUE-P1-631：协调器追加会话引用（锁定时清空待决提示）
        coordinator = VaultFileDriftCoordinator(holder, session)
        boundary = VaultWriteBoundary(
            databaseSession = session,
            driftGuard = VaultFileDriftGuard(coordinator, null),
            strings = StringsProvider { _, _ -> "库文件已被外部修改" },
            autoLockGuard = AutoLockSessionGuard(session, FakeSettingsRepository())
        )
        // 等同「打开成功后留存基线」
        holder.capture(VaultFileBaseline.fromFile(file))
    }

    @Test
    fun `外部修改后元数据候选提交被拒绝且不应用意图`() {
        setupVault()
        val before = session.databaseFlow.value!!.recycleBinEnabled

        // 外部他端写入（size 变化即判漂移，比 mtime 稳定）
        val external = ByteArray(file.length().toInt() + 128) { 0x7 }
        file.writeBytes(external)

        val result = runBlocking {
            boundary.commitMeta { db -> db.copy(recycleBinEnabled = !before) }
        }

        assertTrue("漂移必须 fail-closed 中止覆盖", result is KdbxResult.Failure)
        assertNotNull("必须唤起用户三选提示", coordinator.pending.value)
        assertEquals(
            "漂移命中时用户意图不得落进内存树（避免被重载/合并误当成本地已改）",
            before,
            session.databaseFlow.value!!.recycleBinEnabled
        )
        assertTrue("外部文件字节必须原样保留（不得覆盖）", file.readBytes().contentEquals(external))
        runBlocking { session.close() }
    }

    @Test
    fun `无漂移时元数据提交真实落盘且基线刷新后不误报`() {
        setupVault()

        val first = runBlocking { boundary.commitMeta { db -> db.copy(recycleBinEnabled = false) } }
        assertTrue("无漂移提交应成功", first is KdbxResult.Success)
        assertEquals(false, session.databaseFlow.value!!.recycleBinEnabled)
        assertTrue("落盘完成（文件非空）", file.isFile && file.length() > 0)

        val second = runBlocking { boundary.commitMeta { db -> db.copy(recycleBinEnabled = true) } }
        assertTrue("保存成功后基线刷新，二次提交不得误报漂移", second is KdbxResult.Success)
        assertEquals(true, session.databaseFlow.value!!.recycleBinEnabled)
        runBlocking { session.close() }
    }

    @Test
    fun `外部修改后单一出口保存同样 fail-closed 且外部字节保持`() {
        setupVault()
        val external = ByteArray(file.length().toInt() + 64) { 0x3 }
        file.writeBytes(external)

        val result = runBlocking { boundary.persist() }

        assertTrue("持久化出口必须同源 fail-closed", result is KdbxResult.Failure)
        assertTrue("外部文件字节必须原样保留", file.readBytes().contentEquals(external))
        runBlocking { session.close() }
    }

    @Test
    fun `换密入口在漂移时先拒绝且不进入凭据重加密`() {
        setupVault()
        val rejection: KdbxResult.Failure = KdbxResult.Failure(IllegalStateException("drifted"), "库文件已被外部修改")
        var driftChecks = AtomicInteger(0)
        val coordinatorLifecycle = VaultLifecycleCoordinator(
            context = object : ContextWrapper(null) { override fun getFilesDir(): File = tempFolder.root },
            strings = StringsProvider { _, _ -> "" },
            databaseSession = session,
            catalog = VaultDatabaseCatalog(
                object : ContextWrapper(null) { override fun getFilesDir(): File = tempFolder.root },
                StringsProvider { _, _ -> "" },
                session
            ),
            refresh = { },
            selectDatabase = { },
            driftRejection = {
                driftChecks.incrementAndGet()
                rejection
            }
        )

        val passwordResult = runBlocking { coordinatorLifecycle.changeMasterPassword("new-pw".toCharArray()) }
        assertSame("换密漂移时必须原样返回漂移失败（不进入 changeCredentials）", rejection, passwordResult)

        val keyFileResult = runBlocking {
            coordinatorLifecycle.changeKeyFileOnly(ChangeKeyFileIntent.Remove)
        }
        assertSame("改绑漂移时必须原样返回漂移失败", rejection, keyFileResult)
        assertEquals("换密 / 改绑两入口各做一次写前漂移检查", 2, driftChecks.get())
        runBlocking { session.close() }
    }

    @Test
    fun `落盘出口沿用受理票据_换密后拒绝旧受理`() = runBlocking {
        setupVault()
        val accepted = session.captureCredentialRequest()
        val rotated = "rotated-boundary-password".toCharArray()
        try {
            assertTrue(session.changeCredentials(rotated) is KdbxResult.Success)
            val afterRotation = file.readBytes()
            val ticketOnlyBoundary = VaultWriteBoundary(
                databaseSession = session,
                driftGuard = VaultFileDriftGuard(null, null),
                strings = StringsProvider { _, _ -> "" },
                autoLockGuard = AutoLockSessionGuard(session, FakeSettingsRepository())
            )

            val result = withContext(accepted) { ticketOnlyBoundary.persist() }

            assertTrue("换密后必须拒绝受理时固定的旧票据", result is KdbxResult.Failure)
            assertEquals(KdbxError.SAVE_SESSION_CHANGED, (result as KdbxResult.Failure).code)
            assertArrayEquals("拒绝后正式文件必须保持换密后的内容", afterRotation, file.readBytes())
        } finally {
            rotated.fill('0')
            session.close()
        }
    }

    @Test
    fun `库Meta提交在当前受理票据下放行并落盘`() = runBlocking {
        setupVault()
        val accepted = session.captureCredentialRequest()
        try {
            val result = withContext(accepted) {
                boundary.commitMeta { db -> db.copy(databaseDescription = "accepted-meta") }
            }

            assertTrue("当前票据下的库 Meta 提交必须放行", result is KdbxResult.Success)
            assertEquals(
                "放行后内存树必须采用候选",
                "accepted-meta",
                session.databaseFlow.value!!.databaseDescription
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `库Meta提交沿用受理票据_换密后拒绝旧受理且文件与内存不变`() = runBlocking {
        setupVault()
        val accepted = session.captureCredentialRequest()
        val rotated = "rotated-meta-password".toCharArray()
        try {
            assertTrue(session.changeCredentials(rotated) is KdbxResult.Success)
            val afterRotation = file.readBytes()
            val descriptionAfterRotation = session.databaseFlow.value!!.databaseDescription
            val ticketOnlyBoundary = VaultWriteBoundary(
                databaseSession = session,
                driftGuard = VaultFileDriftGuard(null, null),
                strings = StringsProvider { _, _ -> "" },
                autoLockGuard = AutoLockSessionGuard(session, FakeSettingsRepository())
            )

            val result = withContext(accepted) {
                ticketOnlyBoundary.commitMeta { db -> db.copy(databaseDescription = "后来会话写入") }
            }

            assertTrue("换密后必须拒绝受理时固定的旧票据", result is KdbxResult.Failure)
            assertEquals(KdbxError.SAVE_SESSION_CHANGED, (result as KdbxResult.Failure).code)
            assertArrayEquals("拒绝后正式文件必须保持换密后的内容", afterRotation, file.readBytes())
            assertEquals(
                "拒绝后内存树不得被后来会话的候选改写",
                descriptionAfterRotation,
                session.databaseFlow.value!!.databaseDescription
            )
        } finally {
            rotated.fill('0')
            session.close()
        }
    }

    @Test
    fun `库Meta提交沿用受理票据_换库后拒绝旧受理且新库文件与内存不变`() = runBlocking {
        setupVault()
        val accepted = session.captureCredentialRequest()
        val second = File(tempFolder.root, "boundary-second.kdbx")
        val secondPassword = "second-password".toCharArray()
        try {
            assertTrue(
                "前提：换库成功",
                session.create(
                    second,
                    "boundary-second",
                    secondPassword,
                    useArgon2 = false
                ) is KdbxResult.Success
            )
            val secondAfterCreate = second.readBytes()
            val secondDescription = session.databaseFlow.value!!.databaseDescription
            val ticketOnlyBoundary = VaultWriteBoundary(
                databaseSession = session,
                driftGuard = VaultFileDriftGuard(null, null),
                strings = StringsProvider { _, _ -> "" },
                autoLockGuard = AutoLockSessionGuard(session, FakeSettingsRepository())
            )

            val result = withContext(accepted) {
                ticketOnlyBoundary.commitMeta { db -> db.copy(databaseDescription = "后来会话写入") }
            }

            assertTrue("换库后必须拒绝受理时固定的旧票据", result is KdbxResult.Failure)
            assertEquals(KdbxError.SAVE_SESSION_CHANGED, (result as KdbxResult.Failure).code)
            assertArrayEquals("拒绝后新库文件必须保持换库后的内容", secondAfterCreate, second.readBytes())
            assertEquals(
                "拒绝后新库内存树不得被旧受理的候选改写",
                secondDescription,
                session.databaseFlow.value!!.databaseDescription
            )
        } finally {
            secondPassword.fill('0')
            session.close()
        }
    }

    @Test
    fun `实际Meta修改排队下换密_咬住requestguard拒绝旧受理且文件保持`() = runBlocking {
        setupVault()
        val accepted = session.captureCredentialRequest()
        val rotated = "rotated-queued-meta-password".toCharArray()
        try {
            val ticketOnlyBoundary = VaultWriteBoundary(
                databaseSession = session,
                driftGuard = VaultFileDriftGuard(null, null),
                strings = StringsProvider { _, _ -> "" },
                autoLockGuard = AutoLockSessionGuard(session, FakeSettingsRepository())
            )
            // 占用会话锁，使后续写入请求在临界区外排队
            val mutexHeld = CompletableDeferred<Unit>()
            val releaseMutex = CompletableDeferred<Unit>()
            val locker = launch(Dispatchers.Default) {
                session.useCredentialTicket(requireNotNull(accepted.ticket)) {
                    mutexHeld.complete(Unit)
                    releaseMutex.await()
                }
            }
            mutexHeld.await()

            // 换密操作排队在会话锁上（FIFO 先于稍后的 commitMeta）
            val rotationJob = launch(Dispatchers.Default) {
                session.changeCredentials(rotated)
            }

            val beforeRotation = file.readBytes()
            val beforeDescription = session.databaseFlow.value!!.databaseDescription

            // 旧受理发起 commitMeta，其提交段同样排在会话锁上
            var result: KdbxResult<Unit>? = null
            val commitJob = launch(Dispatchers.Default) {
                result = withContext(accepted) {
                    ticketOnlyBoundary.commitMeta { db -> db.copy(databaseDescription = "排队污染写入") }
                }
            }

            // 释放首个占用锁，使换密先执行并换代，随后旧 commitMeta 咬入 requestguard
            releaseMutex.complete(Unit)
            locker.join()
            rotationJob.join()
            commitJob.join()

            val afterRotation = file.readBytes()
            val finalResult = requireNotNull(result)
            assertTrue("排队换密后必须拒绝旧受理", finalResult is KdbxResult.Failure)
            assertEquals(KdbxError.SAVE_SESSION_CHANGED, (finalResult as KdbxResult.Failure).code)
            assertTrue(
                "必须由 requestguard 咬住会话变化异常而非前置 snapshot guard 截获",
                finalResult.error is KdbxSessionChangedException
            )
            assertArrayEquals("拒绝后正式文件必须保持换密后的内容", afterRotation, file.readBytes())
            assertFalse("文件内容不得与换密前相同", afterRotation.contentEquals(beforeRotation))
            assertEquals(
                "拒绝后活动树不得被旧受理的候选改写",
                beforeDescription,
                session.databaseFlow.value!!.databaseDescription
            )
        } finally {
            rotated.fill('0')
            session.close()
        }
    }

    @Test
    fun `实际Meta修改排队下锁库_咬住requestguard拒绝旧受理且不落盘`() = runBlocking {
        setupVault()
        val snapshot = session.writeIntents.captureSnapshot()!!
        val accepted = session.captureCredentialRequest()
        try {
            val mutexHeld = CompletableDeferred<Unit>()
            val releaseMutex = CompletableDeferred<Unit>()
            val locker = launch(Dispatchers.Default) {
                session.useCredentialTicket(requireNotNull(accepted.ticket)) {
                    mutexHeld.complete(Unit)
                    releaseMutex.await()
                }
            }
            mutexHeld.await()

            // 锁库操作排队在会话锁上
            val lockJob = launch(Dispatchers.Default) {
                session.lock()
            }

            val fileBefore = file.readBytes()

            // 旧受理对已捕获的 snapshot 发起实际 Meta 提交（模拟已过前置快照检查后在提交段排队）
            var result: KdbxResult<Unit>? = null
            val commitJob = launch(Dispatchers.Default) {
                result = withContext(accepted) {
                    session.writeIntents.commitDatabaseMeta(snapshot.database) { db ->
                        db.copy(databaseDescription = "锁库污染写入")
                    }
                }
            }

            releaseMutex.complete(Unit)
            locker.join()
            lockJob.join()
            commitJob.join()

            val finalResult = requireNotNull(result)
            assertTrue("排队锁库后必须拒绝旧受理", finalResult is KdbxResult.Failure)
            assertEquals(KdbxError.SAVE_SESSION_CHANGED, (finalResult as KdbxResult.Failure).code)
            assertTrue(
                "必须由 requestguard 咬住会话变化异常而非前置 snapshot guard 截获活动数据库为空",
                finalResult.error is KdbxSessionChangedException
            )
            assertNull("拒绝后会话必须处于锁定态", session.databaseFlow.value)
            assertArrayEquals("拒绝后正式文件不得被污染", fileBefore, file.readBytes())
        } finally {
            session.close()
        }
    }
}
