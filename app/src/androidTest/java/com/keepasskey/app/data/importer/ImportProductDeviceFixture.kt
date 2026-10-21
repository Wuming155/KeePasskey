package com.keepasskey.app.data.importer

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.EntryGroupIntent
import com.keepasskey.app.data.repository.VaultDatabaseCatalog
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.sync.SyncSessionState
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.app.ui.screens.importer.VaultImportController
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*

internal class ImportProductDeviceFixture(val directory: File) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = EntryPointAccessors.fromApplication(context, AutofillDeviceGraph::class.java)
    val session = graph.databaseSession()
    val real = graph.vaultRepository()
    val password = "Synthetic-Import-Product-2026".toCharArray()
    val first = File(directory, "A.kdbx")
    val later = File(directory, "B.kdbx")
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val exited = CompletableDeferred<Unit>()
    private val armed = AtomicBoolean(true)
    private val repository = object : VaultRepository by real {
        override suspend fun saveEntry(
            entry: UiVaultEntry, passwordChars: CharArray?, totpSecretChars: CharArray?,
            protectedFieldChars: Map<String, CharArray>, groupIntent: EntryGroupIntent,
            loadedCustomFieldKeys: Map<String, String>
        ): KdbxResult<Unit> {
            val result = real.saveEntry(entry, passwordChars, totpSecretChars, protectedFieldChars, groupIntent, loadedCustomFieldKeys)
            if (result.isSuccess && armed.compareAndSet(true, false)) {
                entered.complete(Unit)
                try { release.await() } finally { exited.complete(Unit) }
            }
            return result
        }
    }
    private val log = DebugLogBuffer()
    val controller = VaultImportController(context, ImporterRegistry(setOf(BrowserCsvImporter()), log),
        VaultImporter(repository, StringsProvider { _, _ -> "未命名" }, log, session), log,
        KdbxMergeController(context, session, SyncSessionState(), debugLog = log))

    /**
     * 关闭自动锁定：设备用例在无交互的前台跑数十秒，`AutoLockManager` 的闲置/后台定时器
     * 会在导入中途锁库 ⇒ 落库判定 `isLocked()` 全批失败（实测 `uiState=Failed(PERSIST)`）。
     * 生产语义不变，只在**测试进程**内临时关闭，并在 `cleanup()` 恢复。
     */
    private val settings = com.keepasskey.app.data.repository.RealSettingsRepository(context)
    private var autoLockSnapshot: com.keepasskey.app.data.repository.UserSettings? = null

    private suspend fun disableAutoLock() {
        val current = settings.getSettings().first()
        autoLockSnapshot = current
        settings.setAutoLockBackground(false)
        settings.setAutoLockForegroundEnabled(false)
        settings.setLockWhenScreenOff(false)
    }

    private suspend fun restoreAutoLock() {
        autoLockSnapshot?.let { original ->
            settings.setAutoLockBackground(original.autoLockBackground)
            settings.setAutoLockForegroundEnabled(original.autoLockForegroundEnabled)
            settings.setLockWhenScreenOff(original.lockWhenScreenOff)
        }
        autoLockSnapshot = null
    }

    suspend fun prepare() {
        directory.mkdirs()
        disableAutoLock()
        real.lockDatabase()
        for (file in listOf(first, later)) {
            val creator = DatabaseSession()
            try { assertTrue(creator.create(file, file.nameWithoutExtension, password, useArgon2 = false).isSuccess) }
            finally { creator.close() }
            assertTrue(real.importExternalDatabase("P2640-${directory.name}-${file.name}", file.absolutePath).isSuccess)
        }
        open(first)
    }

    suspend fun open(file: File) {
        real.selectDatabase(file.absolutePath)
        withTimeout(TIMEOUT_MS) {
            real.getDatabases().first { list -> list.any { it.path == file.absolutePath && it.isActive } }
        }
        val copy = password.copyOf()
        try { assertTrue(real.unlockActiveDatabase(copy, null, readOnly = false).isSuccess) }
        finally { copy.fill('\u0000') }
        assertEquals(file.absolutePath, session.currentPathIdentifier)
        awaitUnlocked()
        assertFalse("解锁后会话不得仍为锁定态（file=${file.absolutePath}）", real.isLocked())
    }

    /** 等待会话锁态稳定（`lockDatabase` 是异步生效的，紧接着 start 会被迟到的锁事件打断）。 */
    private suspend fun awaitUnlocked() {
        withTimeout(TIMEOUT_MS) {
            while (real.isLocked()) kotlinx.coroutines.delay(20)
        }
    }

    fun start(prefix: String = "Old") {
        val csv = File(directory, "$prefix.csv").apply {
            writeText("name,url,username,password\n$prefix-1,https://example.test,u,Fake-1\n$prefix-2,https://example.test,u,Fake-2\n")
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.startImport(ImportSource.BROWSER_CSV, Uri.fromFile(csv))
        }
    }

    suspend fun awaitFirst() {
        try {
            withTimeout(TIMEOUT_MS) { entered.await() }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "首条写入未到达屏障：uiState=${controller.uiState.value::class.simpleName} " +
                    "reason=${(controller.uiState.value as? ImportUiState.Failed)?.reason} " +
                    "locked=${real.isLocked()} sessionState=${session.state.value}",
                timeout
            )
        }
    }

    suspend fun outcome(): ImportOutcome {
        val state = withTimeout(TIMEOUT_MS) {
            controller.uiState.first { it is ImportUiState.Done || it is ImportUiState.Failed }
        }
        assertTrue("实际终态=${state.javaClass.simpleName}", state is ImportUiState.Done)
        return (state as ImportUiState.Done).outcome
    }

    fun titles(file: File): Set<String> {
        val copy = password.copyOf()
        val parsed = try { file.inputStream().use { KdbxFile.load(it, copy) } }
        finally { copy.fill('\u0000') }
        return try {
            parsed.rootGroup.allEntries().mapNotNull { it.fields[KdbxConstants.Fields.TITLE]?.readString() }.toSet()
        } finally { parsed.clearSensitiveData() }
    }

    suspend fun cleanup() {
        release.complete(Unit)
        controller.cancelImport()
        real.lockDatabase()
        // `lockDatabase` 异步生效：不等它落定，下一条用例刚解锁的会话会被这条迟到的锁事件锁上
        // （实测表现为「首条写入未到达屏障 + locked=true」）。
        withTimeout(TIMEOUT_MS) { while (!real.isLocked()) kotlinx.coroutines.delay(20) }
        val catalog = VaultDatabaseCatalog(context, { _, _ -> "" }, session)
        val paths = setOf(first.absolutePath, later.absolutePath)
        catalog.saveKnownDatabases(catalog.loadKnownDatabases().filterNot { it.path in paths })
        password.fill('\u0000')
        directory.deleteRecursively()
        restoreAutoLock()
    }

    companion object { const val TIMEOUT_MS = 30_000L }
}
