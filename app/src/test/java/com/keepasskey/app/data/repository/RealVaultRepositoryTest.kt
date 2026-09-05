package com.keepasskey.app.data.repository

import android.content.Context
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.InnerHeader
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * 针对 RealVaultRepository 的全真单元测试（Wave 3-E P0-6 与 P1-10）：
 * 1. 既有条目编辑保存完整保留：history 新增快照、tags/图标/颜色/附件/自定义字段（含 Passkey）完整保留，密码更新生效；
 * 2. KDBX 真实库内回收站：移入回收站、组树内还原、彻底物理删除追加墓碑、清空回收站与持久化往返。
 */
class RealVaultRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createMockContext(filesDir: File): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }
    }

    private fun createInitialDatabase(): Pair<KdbxDatabase, KdbxEntry> {
        val rootGroupId = KdbxUuid.random()
        val entryId = KdbxUuid.random()

        val passkeyFields = listOf(
            KdbxCustomField(PasskeyData.FIELD_RP_ID, ProtectedString("example.com", false)),
            KdbxCustomField(PasskeyData.FIELD_USER_NAME, ProtectedString("alice", false)),
            KdbxCustomField(PasskeyData.FIELD_CREDENTIAL_ID, ProtectedString("cred_123456", false)),
            KdbxCustomField(PasskeyData.FIELD_PRIVATE_KEY, ProtectedString("private_key_pem", true)),
            KdbxCustomField("CustomNoteTag", ProtectedString("important_metadata", false))
        )

        val initialEntry = KdbxEntry(
            id = entryId,
            parentGroupId = rootGroupId,
            iconId = 1,
            backgroundColor = "#FF0000",
            foregroundColor = "#00FF00",
            overrideUrl = "https://custom.override.url",
            tags = listOf("finance", "critical"),
            attachments = listOf(
                KdbxAttachment(name = "secret.key", refIndex = 0, data = "BIN_DATA_0".toByteArray())
            ),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Existing Title", false),
                KdbxConstants.Fields.USER_NAME to ProtectedString("existing_user", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("old_password_123", true),
                KdbxConstants.Fields.URL to ProtectedString("https://example.com", false),
                KdbxConstants.Fields.NOTES to ProtectedString("Initial notes", false)
            ),
            customFields = passkeyFields,
            times = KdbxTimes(creationTime = Instant.now().minusSeconds(3600))
        )

        val rootGroup = KdbxGroup(
            id = rootGroupId,
            name = "Root",
            entries = listOf(initialEntry)
        )

        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(),
            rootGroup = rootGroup,
            binaries = listOf(InnerHeader.BinaryItem(flags = 1, data = "BIN_DATA_0".toByteArray())),
            historyMaxItems = 5
        )

        return Pair(database, initialEntry)
    }

    @Test
    fun `saveEntry 既有条目编辑完整保留元数据且追加历史快照`() = runTest {
        val (database, initialEntry) = createInitialDatabase()
        val session = DatabaseSession()
        session.setDatabaseForTesting(database)

        val repository = RealVaultRepository(createMockContext(tempFolder.root), session)

        // UI 触发条目编辑：修改标题、密码，传入新的普通自定义字段，不传 Passkey 字段
        val updateUiEntry = UiVaultEntry(
            id = initialEntry.id.toHexString(),
            title = "Updated New Title",
            username = "updated_user",
            passwordPlain = "brand_new_secret_pwd!",
            url = "https://updated.example.com",
            notes = "Updated note content",
            customFields = listOf(
                UiCustomField(id = "cf1", key = "AppLanguage", value = "zh-CN", isProtected = false)
            )
        )

        repository.saveEntry(updateUiEntry)

        // 验证内存与领域模型
        val updatedDb = session.databaseFlow.first()!!
        val resultEntry = updatedDb.rootGroup.allEntries().first { it.id == initialEntry.id }

        // 1. 核心提交字段已生效
        assertEquals("Updated New Title", resultEntry.title)
        assertEquals("updated_user", resultEntry.userName)
        assertEquals("brand_new_secret_pwd!", resultEntry.password?.readString())
        assertEquals("https://updated.example.com", resultEntry.url)
        assertEquals("Updated note content", resultEntry.notes)

        // 2. 既有属性完整保留
        assertEquals(initialEntry.iconId, resultEntry.iconId)
        assertEquals("#FF0000", resultEntry.backgroundColor)
        assertEquals("#00FF00", resultEntry.foregroundColor)
        assertEquals("https://custom.override.url", resultEntry.overrideUrl)
        assertEquals(listOf("finance", "critical"), resultEntry.tags)
        assertEquals(1, resultEntry.attachments.size)
        assertEquals("secret.key", resultEntry.attachments[0].name)

        // 3. Passkey 等未在 UI 展示的自定义字段完整保留，且新增的自定义字段也写入
        val customKeys = resultEntry.customFields.map { it.key }
        assertTrue(customKeys.contains(PasskeyData.FIELD_CREDENTIAL_ID))
        assertTrue(customKeys.contains(PasskeyData.FIELD_RP_ID))
        assertTrue(customKeys.contains("AppLanguage"))

        // 4. 历史记录 (history) 新增快照，且快照包含编辑前的老密码
        assertEquals(1, resultEntry.history.size)
        val historySnapshot = resultEntry.history.first()
        assertEquals("Existing Title", historySnapshot.title)
        assertEquals("old_password_123", historySnapshot.password?.readString())
    }

    @Test
    fun `KDBX 库内回收站删除还原往返与彻底删除追加墓碑`() = runTest {
        val (database, initialEntry) = createInitialDatabase()
        val session = DatabaseSession()
        session.setDatabaseForTesting(database)

        val repository = RealVaultRepository(createMockContext(tempFolder.root), session)

        val entryIdHex = initialEntry.id.toHexString()

        // 1. 初次删除：移入库内回收站组
        repository.deleteEntry(entryIdHex)

        val dbAfterRecycle = session.databaseFlow.first()!!
        assertNotNull("应自动创建或选定回收站", dbAfterRecycle.recycleBinUuid)
        val binUuid = dbAfterRecycle.recycleBinUuid!!

        val groups = repository.getGroups().first()
        val binGroup = groups.firstOrNull { it.isRecycleBin }
        assertNotNull(binGroup)
        assertEquals(binUuid.toHexString(), binGroup!!.id)

        val recycledEntry = dbAfterRecycle.rootGroup.allEntries().first { it.id == initialEntry.id }
        assertEquals("条目父组应变为回收站组", binUuid, recycledEntry.parentGroupId)
        assertEquals("原父组应被记录", database.rootGroup.id, recycledEntry.previousParentGroup)

        // 2. 还原条目：移回原父组
        repository.restoreEntry(entryIdHex)
        val dbAfterRestore = session.databaseFlow.first()!!
        val restoredEntry = dbAfterRestore.rootGroup.allEntries().first { it.id == initialEntry.id }
        assertEquals("恢复后父组应归位", database.rootGroup.id, restoredEntry.parentGroupId)
        assertNull("恢复后 previousParentGroup 应置空", restoredEntry.previousParentGroup)

        // 3. 再次移入回收站
        repository.deleteEntry(entryIdHex)

        // 4. 在回收站内二次删除：物理彻底抹除并写入墓碑
        repository.deleteEntry(entryIdHex)
        val dbAfterPermanentDelete = session.databaseFlow.first()!!
        assertNull(
            "条目应从分组树中物理消失",
            dbAfterPermanentDelete.rootGroup.allEntries().firstOrNull { it.id == initialEntry.id }
        )

        assertEquals("deletedObjects 必须追加对应墓碑", 1, dbAfterPermanentDelete.deletedObjects.size)
        assertEquals(initialEntry.id, dbAfterPermanentDelete.deletedObjects.first().id)
    }

    @Test
    fun `emptyRecycleBin 清空回收站并记录全量墓碑`() = runTest {
        val (database, initialEntry) = createInitialDatabase()
        // 添加第二个条目
        val entry2 = KdbxEntry(
            id = KdbxUuid.random(),
            parentGroupId = database.rootGroup.id,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Second Item", false))
        )
        val rootWithTwo = database.rootGroup.copy(entries = listOf(initialEntry, entry2))
        val dbWithTwo = database.copy(rootGroup = rootWithTwo)

        val session = DatabaseSession()
        session.setDatabaseForTesting(dbWithTwo)
        val repository = RealVaultRepository(createMockContext(tempFolder.root), session)

        // 将两个条目均移入回收站
        repository.deleteEntry(initialEntry.id.toHexString())
        repository.deleteEntry(entry2.id.toHexString())

        // 执行清空回收站
        repository.emptyRecycleBin()

        val dbFinal = session.databaseFlow.first()!!
        val survivingEntries = dbFinal.rootGroup.allEntries()
        assertTrue("条目应全部物理删除", survivingEntries.isEmpty())

        assertEquals("墓碑应记录两个已删除条目", 2, dbFinal.deletedObjects.size)
        val deletedIds = dbFinal.deletedObjects.map { it.id }.toSet()
        assertTrue(deletedIds.contains(initialEntry.id))
        assertTrue(deletedIds.contains(entry2.id))
    }

    @Test
    fun `真实 KDBX 文件持久化保存与重开后回收站元数据与墓碑不丢`() = runTest {
        val testFile = File(tempFolder.root, "test_vault.kdbx")
        val password = "StrongPassword#2026".toCharArray()

        val session = DatabaseSession()
        val createResult = session.create(
            file = testFile,
            name = "TestVault",
            passwordChars = password,
            useArgon2 = false // 单元测试快速使用 AES-KDF
        )
        assertTrue(createResult is com.keepasskey.core.result.KdbxResult.Success)

        val repository = RealVaultRepository(createMockContext(tempFolder.root), session)

        // 添加一个测试条目
        val newEntry = UiVaultEntry(
            id = KdbxUuid.random().toHexString(),
            title = "Item to recycle",
            username = "user1",
            passwordPlain = "pass1",
            url = "https://example.com"
        )
        repository.saveEntry(newEntry)

        // 移入回收站
        repository.deleteEntry(newEntry.id)

        // 关闭会话
        session.close()

        // 重新 open 该文件
        val openSession = DatabaseSession()
        val openResult = openSession.open(testFile, password)
        assertTrue(openResult is com.keepasskey.core.result.KdbxResult.Success)

        val reloadedDb = openSession.databaseFlow.first()!!
        assertNotNull("持久化重开后 recycleBinUuid 应完好保留", reloadedDb.recycleBinUuid)
        val reloadedRepo = RealVaultRepository(createMockContext(tempFolder.root), openSession)
        val reloadedGroups = reloadedRepo.getGroups().first()
        assertTrue("回收站分组应在重开后持久存在", reloadedGroups.any { it.isRecycleBin })

        val reloadedEntries = reloadedRepo.getEntries().first()
        val reloadedItem = reloadedEntries.firstOrNull { it.id == newEntry.id }
        assertNotNull(reloadedItem)
        assertEquals(reloadedDb.recycleBinUuid?.toHexString(), reloadedItem!!.groupId)

        password.fill('0')
    }
}
