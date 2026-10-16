package com.keepasskey.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.repository.ActiveDatabaseIdStore
import com.keepasskey.app.data.repository.RealSettingsRepository
import com.keepasskey.app.MainActivity
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import dagger.hilt.android.EntryPointAccessors

/** P2-625：慢 HTTPS MOVE 在途时以真实熄屏广播触发安全锁并检查正式 KDBX 可重开。 */
@RunWith(AndroidJUnit4::class)
class SyncSlowHttpsScreenOffLockDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 熄屏在途慢Https同步须于预算内锁库取消并保全正式文件() = runBlocking {
        val runId = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_RUN_ID)) {
            "缺少 p2_625_run_id"
        }
        val webdavPort = requireNotNull(
            InstrumentationRegistry.getArguments().getString(ARG_WEBDAV_PORT)?.toIntOrNull()
        ) { "缺少 p2_625_webdav_port" }
        val lockMode = InstrumentationRegistry.getArguments().getString(ARG_LOCK_MODE) ?: LOCK_MODE_SCREEN_OFF
        require(lockMode == LOCK_MODE_SCREEN_OFF || lockMode == LOCK_MODE_BACKGROUND) {
            "p2_625_lock_mode 只接受 screen_off 或 background"
        }
        val vaultFile = File(context.filesDir, "p2-625-slow-lock-$runId.kdbx")
        val remotePath = "/p2-625-slow-lock-$runId.kdbx"
        val previousActiveId = ActiveDatabaseIdStore(context).load()
        val settings = RealSettingsRepository(context)
        val originalSettings = settings.getSettings().first()
        val services = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PeriodicSyncWorker.PeriodicSyncEntryPoint::class.java
        )
        val session: DatabaseSession = services.databaseSession()
        val coordinator: SyncCoordinator = services.syncCoordinator()
        val credentials: SyncCredentialsStore = services.syncCredentialsStore()
        val autoLockManager: AutoLockManager = services.autoLockManager()
        val lockAt = AtomicLong(NO_LOCK_BROADCAST)
        val lockBroadcast = kotlinx.coroutines.CompletableDeferred<Unit>()
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        val backgroundObserver = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (lockMode == LOCK_MODE_BACKGROUND) {
                    lockAt.compareAndSet(NO_LOCK_BROADCAST, SystemClock.elapsedRealtime())
                    lockBroadcast.complete(Unit)
                }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    lockAt.compareAndSet(NO_LOCK_BROADCAST, SystemClock.elapsedRealtime())
                    lockBroadcast.complete(Unit)
                }
            }
        }
        val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // 此设备用例只测熄屏锁；避免进程生命周期后台计时器抢先锁库。
            settings.setLockWhenScreenOff(lockMode == LOCK_MODE_SCREEN_OFF)
            settings.setAutoLockBackground(lockMode == LOCK_MODE_BACKGROUND)
            if (lockMode == LOCK_MODE_BACKGROUND) settings.setAutoLockTimeoutSeconds(BACKGROUND_LOCK_TIMEOUT_SECONDS)
            settings.setAutoLockForegroundEnabled(false)
            autoLockManager.initialize()
            withContext(Dispatchers.Main.immediate) { processLifecycle.addObserver(backgroundObserver) }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            session.close()
            val password = MASTER_PASSWORD.toCharArray()
            try {
                assertTrue(
                    "合成同步库创建失败",
                    session.create(vaultFile, VAULT_NAME, password, useArgon2 = false) is KdbxResult.Success
                )
                session.saveEntry(entry("revision A"))
                assertTrue("revision A 保存失败", session.save() is KdbxResult.Success)
            } finally {
                password.fill('\u0000')
            }
            ActiveDatabaseIdStore(context).save(vaultFile.absolutePath)
            assertTrue(
                "生产 WebDAV 凭据封存失败",
                credentials.saveWebDavConfig(
                    url = "https://127.0.0.1:$webdavPort",
                    username = WEBDAV_USER,
                    password = WEBDAV_PASSWORD.toCharArray(),
                    remotePath = remotePath,
                    dbId = vaultFile.absolutePath
                )
            )
            credentials.saveProvider(CloudSyncProvider.WEBDAV, vaultFile.absolutePath)
            assertEquals("设备验收不得注入测试 Provider", null, coordinator.testSyncProvider)
            assertEquals("revision A 基线同步失败", SyncOutcome.UploadedLocal, coordinator.syncNow())

            session.saveEntry(entry("revision B"))
            assertTrue("revision B 必须在网络同步前原子落盘", session.save() is KdbxResult.Success)
            if (lockMode == LOCK_MODE_BACKGROUND) {
                InstrumentationRegistry.getInstrumentation().startActivitySync(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                withTimeout(ACTIVITY_START_TIMEOUT_MS) {
                    while (!processLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                        kotlinx.coroutines.delay(ACTIVITY_POLL_MS)
                    }
                }
            }
            val job = syncScope.async { coordinator.syncNow() }

            withTimeout(MOVE_WAIT_TIMEOUT_MS) { lockBroadcast.await() }
            val broadcastAt = lockAt.get()
            assertTrue("SCREEN_OFF 广播必须真实触发", broadcastAt != NO_LOCK_BROADCAST)
            withTimeout(LOCK_COMPLETION_BUDGET_MS) {
                session.state.first { it == DatabaseSession.SessionState.LOCKED }
            }
            val lockCompletedAt = SystemClock.elapsedRealtime()
            assertTrue(
                "熄屏至会话锁定耗时超过预算: ${lockCompletedAt - broadcastAt}ms",
                lockCompletedAt - broadcastAt <= LOCK_COMPLETION_BUDGET_MS
            )
            withTimeout(LOCK_COMPLETION_BUDGET_MS) { job.join() }
            val syncOutcome = runCatching { job.await() }.getOrNull()
            assertTrue(
                "锁库后同步不得报告成功上传",
                syncOutcome == null || syncOutcome !is SyncOutcome.UploadedLocal
            )

            // 网络服务保持慢响应期间，锁定应完成且正式文件保持可由用户主密码重开。
            val reopenPassword = MASTER_PASSWORD.toCharArray()
            try {
                assertTrue("熄屏取消网络调用后正式 KDBX 必须可重开", session.open(vaultFile, reopenPassword) is KdbxResult.Success)
                val database = requireNotNull(session.databaseFlow.value)
                assertTrue(
                    "正式库必须保留熄屏前已保存的 revision B",
                    database.rootGroup.entries.any { entry ->
                        entry.fields[KdbxConstants.Fields.TITLE]?.readString() == "revision B"
                    }
                )
            } finally {
                reopenPassword.fill('\u0000')
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            withContext(Dispatchers.Main.immediate) { processLifecycle.removeObserver(backgroundObserver) }
            runCatching { syncScope.cancel(CancellationException("P2-625 device test finished")) }
            runCatching { session.close() }
            credentials.clearFor(vaultFile.absolutePath)
            ActiveDatabaseIdStore(context).save(previousActiveId)
            File(vaultFile.absolutePath + ".bak").delete()
            vaultFile.delete()
            settings.setLockWhenScreenOff(originalSettings.lockWhenScreenOff)
            settings.setAutoLockBackground(originalSettings.autoLockBackground)
            settings.setAutoLockTimeoutSeconds(originalSettings.autoLockTimeoutSeconds)
            settings.setAutoLockForegroundEnabled(originalSettings.autoLockForegroundEnabled)
            settings.setAutoLockForegroundTimeoutSeconds(originalSettings.autoLockForegroundTimeoutSeconds)
        }
    }

    private fun entry(title: String) = KdbxEntry(
        id = KdbxUuid.random(),
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(MASTER_PASSWORD, true)
        )
    )

    private companion object {
        const val MASTER_PASSWORD = "Synthetic-P2-625-Master-Password"
        const val ARG_RUN_ID = "p2_625_run_id"
        const val VAULT_NAME = "P2-625 Slow Lock"
        const val ARG_WEBDAV_PORT = "p2_625_webdav_port"
        const val WEBDAV_USER = "p2-627-test"
        const val WEBDAV_PASSWORD = "Synthetic-P2-627-WebDav-Password"
        const val ARG_LOCK_MODE = "p2_625_lock_mode"
        const val LOCK_MODE_SCREEN_OFF = "screen_off"
        const val LOCK_MODE_BACKGROUND = "background"
        const val BACKGROUND_LOCK_TIMEOUT_SECONDS = 3
        const val ACTIVITY_START_TIMEOUT_MS = 15_000L
        const val ACTIVITY_POLL_MS = 100L
        const val MOVE_WAIT_TIMEOUT_MS = 30_000L
        const val LOCK_COMPLETION_BUDGET_MS = 5_000L
        const val NO_LOCK_BROADCAST = -1L
    }
}
