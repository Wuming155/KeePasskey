package com.keepasskey.app.data.importer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.RealSettingsRepository
import com.keepasskey.app.sync.SyncSessionState
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.app.ui.screens.unlock.SafKeyFileAccess
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 所选密钥文件失败必须在打开第二库前停止；合法大文件保持既有兼容性。 */
@RunWith(AndroidJUnit4::class)
class MergeKeyFileDeviceTest {
    @Test fun 不可读密钥文件不能降级为密码并入() = runBlocking {
        fixture { directory, session, merge, password ->
            val other = File(directory, "other.kdbx")
            val creator = DatabaseSession()
            try { assertTrue(creator.create(other, "Other", password, useArgon2 = false).isSuccess) }
            finally { creator.close() }
            val before = session.currentFile!!.readBytes()
            assertTrue(merge.beginWithUri(Uri.fromFile(other)))
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                merge.submitCredentialsFromUri(password, Uri.fromFile(File(directory, "missing.key")))
            }
            val state = withTimeout(30_000) { merge.uiState.first { it is ImportUiState.Failed || it is ImportUiState.Done } }
            assertEquals(ImportFailureReason.KEY_FILE, (state as ImportUiState.Failed).reason)
            assertArrayEquals(before, session.currentFile!!.readBytes())
        }
    }

    @Test fun 合法大密钥文件经生产IO通道完整读取并完成并入() = runBlocking {
        fixture { directory, _, merge, password ->
            val key = File(directory, "large.key").apply { writeBytes(ByteArray(2_280_000) { (it % 251).toByte() }) }
            val other = File(directory, "other.kdbx")
            val keyBytes = key.readBytes()
            val creator = DatabaseSession()
            try { assertTrue(creator.create(other, "Other", password, useArgon2 = false, keyFileData = keyBytes).isSuccess) }
            finally { creator.close(); keyBytes.fill(0) }
            assertTrue(merge.beginWithUri(Uri.fromFile(other)))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { merge.submitCredentialsFromUri(password, Uri.fromFile(key)) }
            val state = withTimeout(30_000) { merge.uiState.first { it is ImportUiState.Failed || it is ImportUiState.Done } }
            assertTrue("完整密钥因子应能打开第二库，实际 ${state.javaClass.simpleName}", state is ImportUiState.Done)
        }
    }

    private suspend fun fixture(block: suspend (File, DatabaseSession, KdbxMergeController, CharArray) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "merge-key-${System.nanoTime()}").apply { mkdirs() }
        val session = DatabaseSession()
        val password = "Synthetic-Merge-2026".toCharArray()
        val log = DebugLogBuffer()
        val reader = SafKeyFileAccess(context, RealSettingsRepository(context), ExtendedSettingsStore(context), log)
        val merge = KdbxMergeController(context, session, SyncSessionState(), debugLog = log, keyFileAccess = reader)
        try {
            assertTrue(session.create(File(directory, "target.kdbx"), "Target", password, useArgon2 = false).isSuccess)
            block(directory, session, merge, password)
        } finally {
            merge.cancel()
            session.close()
            password.fill('0')
            directory.deleteRecursively()
        }
    }
}
