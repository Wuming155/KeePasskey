package com.keepasskey.app.data.importer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.EntryGroupIntent
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.app.ui.screens.importer.VaultImportController
import com.keepasskey.app.sync.SyncSessionState
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxFile
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 真实 CSV 控制器、生产仓库与会话，首条写盘后用屏障确定性换库。 */
@RunWith(AndroidJUnit4::class)
class ImportSessionBindingDeviceTest {
    @Test fun 首条写入A后换B停止导入并保全两个正式文件() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = EntryPointAccessors.fromApplication(context, AutofillDeviceGraph::class.java)
        val session = graph.databaseSession()
        val real = graph.vaultRepository()
        val directory = File(context.cacheDir, "import-session-${System.nanoTime()}").apply { mkdirs() }
        val password = "Synthetic-Import-2026".toCharArray()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : VaultRepository by real {
            override suspend fun saveEntry(
                entry: UiVaultEntry, passwordChars: CharArray?, totpSecretChars: CharArray?,
                protectedFieldChars: Map<String, CharArray>, groupIntent: EntryGroupIntent,
                loadedCustomFieldKeys: Map<String, String>
            ): KdbxResult<Unit> {
                val result = real.saveEntry(entry, passwordChars, totpSecretChars, protectedFieldChars, groupIntent, loadedCustomFieldKeys)
                if (result.isSuccess && !entered.isCompleted) { entered.complete(Unit); release.await() }
                return result
            }
        }
        val debugLog = DebugLogBuffer()
        val controller = VaultImportController(context, ImporterRegistry(setOf(BrowserCsvImporter()), debugLog),
            VaultImporter(repository, StringsProvider { _, _ -> "未命名" }, debugLog, session), debugLog,
            KdbxMergeController(context, session, SyncSessionState(), debugLog = debugLog))
        try {
            val first = File(directory, "A.kdbx")
            val later = File(directory, "B.kdbx")
            assertTrue(session.create(first, "A", password, useArgon2 = false).isSuccess)
            val csv = File(directory, "source.csv").apply {
                writeText("name,url,username,password\nSynthetic-1,https://example.test,u,Fake-1\nSynthetic-2,https://example.test,u,Fake-2\n")
            }
            controller.startImport(ImportSource.BROWSER_CSV, Uri.fromFile(csv))
            withTimeout(30_000) { entered.await() }
            assertTrue(session.create(later, "B", password, useArgon2 = false).isSuccess)
            val originalB = later.readBytes()
            release.complete(Unit)
            val state = withTimeout(30_000) { controller.uiState.first { it is ImportUiState.Done || it is ImportUiState.Failed } }
            assertTrue(state is ImportUiState.Done)
            val outcome = (state as ImportUiState.Done).outcome
            assertTrue(outcome.sessionChanged)
            assertEquals(1, outcome.imported)
            assertEquals(1, outcome.unprocessed)
            assertArrayEquals(originalB, later.readBytes())
            assertTrue(session.databaseFlow.value!!.rootGroup.allEntries().isEmpty())
            val parsedA = first.inputStream().use { KdbxFile.load(it, password.copyOf()) }
            try { assertEquals(1, parsedA.rootGroup.allEntries().size) }
            finally { parsedA.clearSensitiveData() }
        } finally {
            release.complete(Unit)
            controller.cancelImport()
            session.close()
            password.fill('0')
            directory.deleteRecursively()
        }
    }
}
