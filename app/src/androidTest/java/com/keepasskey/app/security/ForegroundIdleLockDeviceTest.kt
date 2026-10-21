package com.keepasskey.app.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.RealSettingsRepository
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ISSUE-P2-637：真实前台计时入口锁库后，已落盘的合成库仍可重新解密。 */
@RunWith(AndroidJUnit4::class)
class ForegroundIdleLockDeviceTest {

    @Test
    fun foregroundIdleDeadlineLocksSessionAndPreservesPersistedVault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = RealSettingsRepository(context)
        val previousSettings = settings.getSettings().first()
        val file = File(context.cacheDir, "p2-637-${System.nanoTime()}.kdbx")
        val password = "P2-637-device-password".toCharArray()
        val session = DatabaseSession()
        val reopened = DatabaseSession()
        try {
            settings.setAutoLockBackground(false)
            settings.setLockWhenScreenOff(false)
            settings.setAutoLockForegroundEnabled(true)
            settings.setAutoLockForegroundTimeoutSeconds(ForegroundIdleLockPolicy.IMMEDIATE)

            assertTrue("合成数据库创建失败", session.create(file, "P2-637", password, useArgon2 = false) is KdbxResult.Success)
            session.saveEntry(
                KdbxEntry(
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString("Persisted foreground timer fixture"),
                        KdbxConstants.Fields.USER_NAME to ProtectedString("synthetic-user"),
                        KdbxConstants.Fields.PASSWORD to ProtectedString("synthetic-secret", isProtected = true)
                    )
                )
            )
            assertTrue("合成数据库初始写入失败", session.save() is KdbxResult.Success)
            val persistedBytes = file.readBytes()

            val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())
            val manager = AutoLockManager(context, guard, settings)
            manager.onUserInteraction()
            withTimeout(10_000) { guard.isLocked.first { it } }

            assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
            assertArrayEquals("自动锁定不得改写正式数据库", persistedBytes, file.readBytes())
            assertTrue(
                "锁定后的正式数据库必须仍可解密",
                reopened.openStream(
                    pathIdentifier = file.absolutePath,
                    inputStreamProvider = { FileInputStream(file) },
                    passwordChars = password.clone()
                ) is KdbxResult.Success
            )
            assertTrue(
                "重新解密后必须保留已提交条目",
                reopened.databaseFlow.value?.rootGroup?.allEntries()?.any {
                    it.title == "Persisted foreground timer fixture"
                } == true
            )
        } finally {
            settings.setAutoLockBackground(previousSettings.autoLockBackground)
            settings.setLockWhenScreenOff(previousSettings.lockWhenScreenOff)
            settings.setAutoLockForegroundEnabled(previousSettings.autoLockForegroundEnabled)
            settings.setAutoLockForegroundTimeoutSeconds(previousSettings.autoLockForegroundTimeoutSeconds)
            session.close()
            reopened.close()
            file.delete()
            File(file.absolutePath + ".bak").delete()
            password.fill('\u0000')
        }
    }
}
