package com.keepasskey.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.MainApplication
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.RealSettingsRepository
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import android.os.PowerManager
import android.os.SystemClock
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import dagger.hilt.android.EntryPointAccessors
import android.os.ParcelFileDescriptor

/**
 * ISSUE-P1-592：在 Android 文件系统上验证自动锁定的正式写失败保全通道。
 *
 * 测试只操作本用例创建的合成库：正式保存器注入写失败，恢复副本走生产 `filesDir/recovery`，
 * 随后以新建的 Store/Coordinator 对象模拟冷启动扫描、预览、覆盖保护和确认恢复。
 */
@RunWith(AndroidJUnit4::class)
class SessionRecoveryDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 写入失败后自动锁定保全并经冷启动编排恢复合成库() = runBlocking {
        val suffix = System.nanoTime().toString()
        val vaultFile = File(context.filesDir, "p1592-recovery-$suffix.kdbx")
        val password = "Recovery-Device-2026".toCharArray()
        val debugLog = DebugLogBuffer()
        val seedSession = DatabaseSession()
        val session = DatabaseSession()
        val settingsRepository = RealSettingsRepository(context)
        val oldLockWhenScreenOff = settingsRepository.getSettings().first().lockWhenScreenOff
        val vaultId = vaultFile.absolutePath
        val recoveryStore = VaultRecoveryStore(context, debugLog)
        val alertStore = RecoveryAlertStore(context)

        try {
            assertTrue(
                "合成正式库创建失败",
                seedSession.create(vaultFile, "P1-592 device recovery", password, useArgon2 = false).isSuccess
            )
            seedSession.lock()

            assertTrue(
                "注入正式写失败的会话打开失败",
                session.openStream(
                    pathIdentifier = vaultId,
                    inputStreamProvider = { FileInputStream(vaultFile) },
                    saveWriter = { throw IOException("synthetic-device-save-failure") },
                    passwordChars = password.clone()
                ).isSuccess
            )
            session.saveEntry(
                KdbxEntry(
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString("Uncommitted recovery entry"),
                        KdbxConstants.Fields.USER_NAME to ProtectedString("synthetic-user"),
                        KdbxConstants.Fields.PASSWORD to ProtectedString("Synthetic-Secret-2026", isProtected = true)
                    )
                )
            )
            assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)

            settingsRepository.setLockWhenScreenOff(true)
            val recovery = SessionRecoveryCoordinator(session, recoveryStore, alertStore, debugLog)
            val guard = AutoLockSessionGuard(
                databaseSession = session,
                settingsRepository = settingsRepository,
                debugLog = debugLog,
                recovery = recovery
            )
            guard.lockOnScreenOff()
            assertEquals("模拟 ACTION_SCREEN_OFF 的 guard 入口必须保全修改", DatabaseSession.SessionState.LOCKED, session.state.value)
            assertTrue(guard.isLocked.value)
            assertTrue("恢复副本必须已写入", recoveryStore.list().any { it.vaultId == vaultId })

            // 新 Store / 新 Coordinator 从实际 filesDir/recovery 扫描，验证状态不依赖旧进程内存。
            val coldStore = VaultRecoveryStore(context, DebugLogBuffer())
            val coldCoordinator = VaultRecoveryCoordinator(
                context = context,
                store = coldStore,
                alertStore = alertStore,
                keyFileCopyStore = KeyFileVaultCopyStore(context, null),
                debugLog = DebugLogBuffer()
            )
            coldCoordinator.refresh()
            val pending = coldCoordinator.pending.value.single { it.entry.vaultId == vaultId }
            assertTrue("正式库应仍存在，覆盖前必须由用户确认", pending.officialFileExists)

            val preview = coldCoordinator.preview(pending, password)
            assertTrue("解密预览必须成功", preview is KdbxResult.Success)
            val previewValue = (preview as KdbxResult.Success).data
            assertEquals(1, previewValue.addedEntries)
            assertTrue("预览必须如实显示正式库与恢复副本有差异", previewValue.differs)

            val blocked = coldCoordinator.restore(pending, forceOverwrite = false)
            assertTrue("未确认覆盖不得改写正式库", blocked is KdbxResult.Failure)
            assertTrue("覆盖拒绝后恢复副本必须保留", coldStore.list().any { it.vaultId == vaultId })

            assertTrue("显式确认后恢复必须成功", coldCoordinator.restore(pending, forceOverwrite = true) is KdbxResult.Success)
            assertTrue("成功恢复后副本必须删除", coldStore.list().none { it.vaultId == vaultId })

            val restored = KdbxFile.load(ByteArrayInputStream(vaultFile.readBytes()), password.clone())
            try {
                assertEquals("恢复后的正式库必须含未落盘条目", "Uncommitted recovery entry", restored.rootGroup.allEntries().single().title)
            } finally {
                restored.clearSensitiveData()
            }
        } finally {
            password.fill('\u0000')
            settingsRepository.setLockWhenScreenOff(oldLockWhenScreenOff)
            session.lock()
            seedSession.lock()
            recoveryStore.deleteByVaultId(vaultId)
            vaultFile.delete()
        }
    }

    @Test
    fun 系统熄屏事件经生产广播接收器触发锁定和恢复副本() = runBlocking {
        val suffix = System.nanoTime().toString()
        val vaultFile = File(context.filesDir, "p1592-screen-off-$suffix.kdbx")
        val password = "Recovery-ScreenOff-2026".toCharArray()
        val app = context.applicationContext as MainApplication
        val session = EntryPointAccessors.fromApplication(context, AutofillDeviceGraph::class.java).databaseSession()
        val settings = RealSettingsRepository(context)
        val oldSettings = settings.getSettings().first()
        val vaultId = vaultFile.absolutePath
        val recoveryStore = VaultRecoveryStore(context, DebugLogBuffer())
        try {
            assertTrue("系统熄屏夹具建库失败", session.create(vaultFile, "P1-592 screen off", password, useArgon2 = false) is KdbxResult.Success)
            session.lock()
            assertTrue(
                "注入写失败会话打开失败",
                session.openStream(
                    pathIdentifier = vaultId,
                    inputStreamProvider = { FileInputStream(vaultFile) },
                    saveWriter = { throw IOException("synthetic-screen-off-save-failure") },
                    passwordChars = password.clone()
                ) is KdbxResult.Success
            )
            session.saveEntry(
                KdbxEntry(
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString("Screen-off recovery entry"),
                        KdbxConstants.Fields.USER_NAME to ProtectedString("screen-off-user"),
                        KdbxConstants.Fields.PASSWORD to ProtectedString("Synthetic-ScreenOff-Secret", true)
                    )
                )
            )
            assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)

            settings.setLockWhenScreenOff(true)
            settings.setAutoLockBackground(false)
            settings.setAutoLockTimeoutSeconds(-1)
            settings.setAutoLockForegroundEnabled(false)
            app.autoLockManager.initialize()
            setScreenInteractive(true)
            setScreenInteractive(false)
            withTimeout(8_000) { session.state.first { it == DatabaseSession.SessionState.LOCKED } }

            assertEquals("正式 ACTION_SCREEN_OFF 必须锁定生产会话", DatabaseSession.SessionState.LOCKED, session.state.value)
            assertTrue("正式广播路径须写出加密恢复副本", recoveryStore.list().any { it.vaultId == vaultId })
        } finally {
            setScreenInteractive(true)
            settings.setLockWhenScreenOff(oldSettings.lockWhenScreenOff)
            settings.setAutoLockBackground(oldSettings.autoLockBackground)
            settings.setAutoLockTimeoutSeconds(oldSettings.autoLockTimeoutSeconds)
            settings.setAutoLockForegroundEnabled(oldSettings.autoLockForegroundEnabled)
            settings.setAutoLockForegroundTimeoutSeconds(oldSettings.autoLockForegroundTimeoutSeconds)
            session.close()
            recoveryStore.deleteByVaultId(vaultId)
            vaultFile.delete()
            File(vaultFile.absolutePath + ".bak").delete()
            password.fill('\u0000')
        }
    }

    private fun setScreenInteractive(interactive: Boolean) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive == interactive) return
        val keyCode = if (interactive) KEYCODE_WAKEUP else KEYCODE_SLEEP
        val descriptor = getInstrumentation().uiAutomation.executeShellCommand("input keyevent $keyCode")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (SystemClock.elapsedRealtime() < deadline && powerManager.isInteractive != interactive) {
            SystemClock.sleep(50L)
        }
        assertEquals("AVD 屏幕电源状态切换失败", interactive, powerManager.isInteractive)
    }

    private companion object {
        const val KEYCODE_SLEEP = 223
        const val KEYCODE_WAKEUP = 224
    }
}
