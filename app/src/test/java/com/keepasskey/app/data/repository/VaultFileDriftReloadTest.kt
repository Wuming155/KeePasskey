package com.keepasskey.app.data.repository

import com.keepasskey.app.security.VaultFileBaselineHolder
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * ENG-20261027-02 回归：
 * 漂移重载（以磁盘为准）以候选方式读取磁盘内容；
 * 失败（损坏文件、外部换密、读取异常）必须保留活动会话树、脏态、未保存改动与凭据；
 * 成功时才替换会话并刷新基线。
 */
class VaultFileDriftReloadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var masterPassword: CharArray
    private lateinit var session: DatabaseSession
    private val strings = StringsProvider { _, _ -> "" }

    @Before
    fun setUp() {
        masterPassword = "{{SECRET_g0z1x67g}}".toCharArray()
        session = DatabaseSession()
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
        masterPassword.fill('0')
    }

    private fun createDiskVault(file: File, entries: List<KdbxEntry>, password: CharArray = masterPassword) {
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = entries)
        )
        try {
            FileOutputStream(file).use { fos ->
                KdbxFile.save(fos, db, password)
            }
        } finally {
            db.clearSensitiveData()
        }
    }

    @Test
    fun `漂移重载遇损坏文件失败时保留原会话脏态未保存改动与凭据`() = runBlocking {
        val file = File(tempFolder.root, "corrupt_drift.kdbx")
        assertTrue(session.create(file, "Vault", masterPassword, useArgon2 = false) is KdbxResult.Success)

        val unsavedEntry = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("UnsavedDraft", false))
        )
        session.saveEntry(unsavedEntry)
        assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)

        file.writeBytes(byteArrayOf(0x03, 0xd9.toByte(), 0xa2.toByte(), 0x9a.toByte(), 0, 0, 0, 0))

        val holder = VaultFileBaselineHolder(session)
        val coordinator = VaultFileDriftCoordinator(holder, session)
        val sourceAccess = VaultFileDriftSourceAccess(context = null, maxDriftReadBytes = 10_000_000L)
        var refreshedPath: String? = null
        val reloader = VaultFileDriftReloader(
            databaseSession = session,
            strings = strings,
            driftCoordinator = coordinator,
            sourceAccess = sourceAccess,
            onBaselineRefreshed = { path, _ -> refreshedPath = path }
        )

        val result = reloader.reload(file.absolutePath, file)
        assertTrue("损坏文件重载必须失败", result is KdbxResult.Failure)

        assertEquals("重载失败必须保持会话 DIRTY 态", DatabaseSession.SessionState.DIRTY, session.state.value)
        val liveEntries = session.databaseFlow.value?.rootGroup?.allEntries().orEmpty()
        assertTrue(
            "未保存改动必须仍在内存会话树中",
            liveEntries.any { it.fields[KdbxConstants.Fields.TITLE]?.readString() == "UnsavedDraft" }
        )
        session.useCredentials { pwd, _ ->
            assertNotNull("会话凭据不得被擦除", pwd)
            assertArrayEquals("会话凭据与主密码一致", masterPassword, pwd)
        }
        assertNotNull("重载失败必须唤起待决提示", coordinator.pending.value)
        assertNull("基线刷新回调不得被调用", refreshedPath)
    }

    @Test
    fun `漂移重载遇外部换密失败时保留原会话与未保存改动`() = runBlocking {
        val file = File(tempFolder.root, "rotated_drift.kdbx")
        assertTrue(session.create(file, "Vault", masterPassword, useArgon2 = false) is KdbxResult.Success)

        val unsavedEntry = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("LocalDraft", false))
        )
        session.saveEntry(unsavedEntry)
        assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)

        val newExternalPassword = "NewExternalPassword#2026".toCharArray()
        try {
            createDiskVault(
                file,
                listOf(KdbxEntry(fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("ExternalEntry", false)))),
                newExternalPassword
            )

            val holder = VaultFileBaselineHolder(session)
            val coordinator = VaultFileDriftCoordinator(holder, session)
            val sourceAccess = VaultFileDriftSourceAccess(context = null, maxDriftReadBytes = 10_000_000L)
            val reloader = VaultFileDriftReloader(
                databaseSession = session,
                strings = strings,
                driftCoordinator = coordinator,
                sourceAccess = sourceAccess,
                onBaselineRefreshed = { _, _ -> }
            )

            val result = reloader.reload(file.absolutePath, file)
            assertTrue("外部换密后以旧凭据重载必须失败", result is KdbxResult.Failure)

            assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)
            val liveEntries = session.databaseFlow.value?.rootGroup?.allEntries().orEmpty()
            assertTrue(
                "未保存改动必须保留",
                liveEntries.any { it.fields[KdbxConstants.Fields.TITLE]?.readString() == "LocalDraft" }
            )
            session.useCredentials { pwd, _ ->
                assertNotNull("凭据保留", pwd)
                assertArrayEquals(masterPassword, pwd)
            }
        } finally {
            newExternalPassword.fill('0')
        }
    }

    @Test
    fun `漂移重载遇文件缺失时保留原会话`() = runBlocking {
        val file = File(tempFolder.root, "missing_drift.kdbx")
        assertTrue(session.create(file, "Vault", masterPassword, useArgon2 = false) is KdbxResult.Success)

        val unsavedEntry = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("KeepMe", false))
        )
        session.saveEntry(unsavedEntry)

        val missingFile = File(tempFolder.root, "does_not_exist.kdbx")
        val holder = VaultFileBaselineHolder(session)
        val coordinator = VaultFileDriftCoordinator(holder, session)
        val sourceAccess = VaultFileDriftSourceAccess(context = null, maxDriftReadBytes = 10_000_000L)
        val reloader = VaultFileDriftReloader(
            databaseSession = session,
            strings = strings,
            driftCoordinator = coordinator,
            sourceAccess = sourceAccess,
            onBaselineRefreshed = { _, _ -> }
        )

        val result = reloader.reload(missingFile.absolutePath, missingFile)
        assertTrue("文件缺失必须失败", result is KdbxResult.Failure)

        assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)
        val liveEntries = session.databaseFlow.value?.rootGroup?.allEntries().orEmpty()
        assertTrue(liveEntries.any { it.fields[KdbxConstants.Fields.TITLE]?.readString() == "KeepMe" })
    }

    @Test
    fun `漂移重载成功时以磁盘版本替换会话并刷新基线`() = runBlocking {
        val file = File(tempFolder.root, "success_drift.kdbx")
        assertTrue(session.create(file, "Vault", masterPassword, useArgon2 = false) is KdbxResult.Success)

        val externalEntry = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("ExternalWinner", false))
        )
        createDiskVault(file, listOf(externalEntry), masterPassword)

        val holder = VaultFileBaselineHolder(session)
        val coordinator = VaultFileDriftCoordinator(holder, session)
        val sourceAccess = VaultFileDriftSourceAccess(context = null, maxDriftReadBytes = 10_000_000L)
        var refreshed = false
        val reloader = VaultFileDriftReloader(
            databaseSession = session,
            strings = strings,
            driftCoordinator = coordinator,
            sourceAccess = sourceAccess,
            onBaselineRefreshed = { path, _ ->
                if (path == file.absolutePath) refreshed = true
            }
        )

        val result = reloader.reload(file.absolutePath, file)
        assertTrue("合法外部更新重载应成功", result is KdbxResult.Success)

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        val liveEntries = session.databaseFlow.value?.rootGroup?.allEntries().orEmpty()
        assertTrue(
            "活动库已替换为磁盘版本",
            liveEntries.any { it.fields[KdbxConstants.Fields.TITLE]?.readString() == "ExternalWinner" }
        )
        assertTrue("基线已成功刷新", refreshed)
    }
}
