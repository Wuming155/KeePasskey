package com.keepasskey.app.data.repository

import android.content.Context
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * ISSUE-P2-386：同名附件经编辑保存不得被折叠。
 *
 * 背景：`VaultEntryWriteCoordinator.mergeAttachments` 曾按名称 `firstOrNull` 匹配既有附件，
 * KDBX 同名附件是合法形态（KeePassXC 注释实证），两份同名附件经编辑保存会被折叠为一份、
 * 数据静默丢失。整改后按 [UiAttachment.refIndex] 身份匹配（与导出侧 ISSUE-P3-295 同口径）。
 *
 * 构造：既有条目含两份同名附件（不同字节），UI 以 refIndex=0/1 分别投影后保存，
 * 断言保存结果仍为两份且各自字节不丢。
 */
class AttachmentSameNameEditSaveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val bin0 = "BIN_SAME_NAME_0".toByteArray()
    private val bin1 = "BIN_SAME_NAME_1".toByteArray()

    private fun createMockContext(filesDir: File): Context {
        val prefsMap = mutableMapOf<String, String?>()
        val mockPrefs = object : android.content.SharedPreferences {
            override fun getAll(): MutableMap<String, *> = prefsMap.toMutableMap()
            override fun getString(key: String?, defValue: String?): String? = prefsMap[key] ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
            override fun getInt(key: String?, defValue: Int): Int = defValue
            override fun getLong(key: String?, defValue: Long): Long = defValue
            override fun getFloat(key: String?, defValue: Float): Float = defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
            override fun contains(key: String?): Boolean = prefsMap.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor =
                object : android.content.SharedPreferences.Editor {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                        if (key != null) prefsMap[key] = value
                        return this
                    }
                    override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
                    override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor = this
                    override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor = this
                    override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor = this
                    override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor = this
                    override fun remove(key: String?): android.content.SharedPreferences.Editor {
                        prefsMap.remove(key)
                        return this
                    }
                    override fun clear(): android.content.SharedPreferences.Editor {
                        prefsMap.clear()
                        return this
                    }
                    override fun commit(): Boolean = true
                    override fun apply() = Unit
                }
            override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
            override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        }
        return object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = mockPrefs
        }
    }

    private fun createTestStrings(): StringsProvider = StringsProvider { _, _ -> "" }

    private fun TestScope.newRepository(
        session: DatabaseSession,
        filesDir: File = tempFolder.root,
        projectionDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    ): RealVaultRepository = RealVaultRepository(
        createMockContext(filesDir),
        session,
        DebugLogBuffer(),
        createTestStrings(),
        projectionDispatcher,
        TotpPreferencesSource { TotpPreferences.DEFAULT },
        AutoLockSessionGuard(
            session,
            FakeSettingsRepository(),
            DebugLogBuffer()
        )
    )

    private fun createInitialDatabase(): Pair<KdbxDatabase, KdbxEntry> {
        val rootGroupId = KdbxUuid.random()
        val entryId = KdbxUuid.random()

        val initialEntry = KdbxEntry(
            id = entryId,
            parentGroupId = rootGroupId,
            iconId = 1,
            attachments = listOf(
                KdbxAttachment(name = "shared.bin", refIndex = 0, data = bin0),
                KdbxAttachment(name = "shared.bin", refIndex = 1, data = bin1)
            ),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Same Name", false),
                KdbxConstants.Fields.USER_NAME to ProtectedString("alice", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("old_password_123", true)
            ),
            times = KdbxTimes(creationTime = Instant.now().minusSeconds(3600))
        )
        val rootGroup = KdbxGroup(id = rootGroupId, name = "Root", entries = listOf(initialEntry))
        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(),
            rootGroup = rootGroup,
            binaries = listOf(),
            historyMaxItems = 5
        )
        return database to initialEntry
    }

    @Test
    fun `两份同名附件经编辑保存仍为两份且字节不丢`() = runTest {
        val (database, initialEntry) = createInitialDatabase()
        val session = DatabaseSession()
        session.setDatabaseForTesting(database)
        val repository = newRepository(session)

        // 模拟编辑页投影：两份同名附件均以 data=null + 正确 refIndex 回传（未改动内容）
        val updateUiEntry = UiVaultEntry(
            id = initialEntry.id.toHexString(),
            title = "Same Name",
            username = "alice",
            url = "https://example.com",
            notes = "",
            attachments = listOf(
                UiAttachment(
                    id = "${initialEntry.id.toHexString()}_0",
                    fileName = "shared.bin",
                    fileSizeFormatted = "16 B",
                    refIndex = 0,
                    data = null
                ),
                UiAttachment(
                    id = "${initialEntry.id.toHexString()}_1",
                    fileName = "shared.bin",
                    fileSizeFormatted = "16 B",
                    refIndex = 1,
                    data = null
                )
            ),
            customFields = emptyList()
        )

        repository.saveEntry(updateUiEntry, passwordChars = "old_password_123".toCharArray())

        val updatedDb = session.databaseFlow.first()!!
        val resultEntry = updatedDb.rootGroup.allEntries().first { it.id == initialEntry.id }

        assertEquals(
            "同名附件经编辑保存必须仍为两份（不得折叠）",
            2,
            resultEntry.attachments.size
        )
        assertEquals("shared.bin", resultEntry.attachments[0].name)
        assertEquals("shared.bin", resultEntry.attachments[1].name)

        val pool = resultEntry.attachments.map { it.resolveData(emptyList()) }
        assertTrue("第一份字节必须保留", pool[0].contentEquals(bin0))
        assertTrue(
            "第二份字节必须保留（不得被 firstOrNull 折叠到第一份）",
            pool[1].contentEquals(bin1)
        )
    }
}
