package com.keepasskey.app.data.repository

import android.content.Context
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
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
            override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
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
                override fun apply() {}
            }
            override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
        return object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = mockPrefs
        }
    }

    /** TASK-21：仓库消息已资源化（StringsProvider）；单测无资源环境，注入返回占位文本的假实现 */
    private fun createTestStrings(): com.keepasskey.app.ui.model.StringsProvider =
        com.keepasskey.app.ui.model.StringsProvider { _, _ -> "" }

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

        val repository = RealVaultRepository(createMockContext(tempFolder.root), session, com.keepasskey.app.data.logger.DebugLogBuffer(), createTestStrings())

        // UI 触发条目编辑：修改标题、密码，传入新的普通自定义字段，不传 Passkey 字段。
        // H4-断点补齐后，附件/标签/OverrideUrl/图标/AutoType 由 UI 投影全量携带
        // （与真实编辑页 loadEntry 行为一致——loadEntry 从仓库投影回填全部字段）
        val updateUiEntry = UiVaultEntry(
            id = initialEntry.id.toHexString(),
            title = "Updated New Title",
            username = "updated_user",
            url = "https://updated.example.com",
            notes = "Updated note content",
            iconName = "public", // mapIconIdToName(1)=public（KDBX World 图标）
            tags = listOf("finance", "critical"),
            overrideUrl = "https://custom.override.url",
            attachments = listOf(
                // data=null 表示已落库附件，仓库按名称匹配保留既有 refIndex 引用
                UiAttachment(id = "att1", fileName = "secret.key", fileSizeFormatted = "1 KB")
            ),
            customFields = listOf(
                UiCustomField(id = "cf1", key = "AppLanguage", value = "zh-CN", isProtected = false)
            )
        )

        // M1 整改：密码以独立参数显式提交
        repository.saveEntry(updateUiEntry, passwordChars = "brand_new_secret_pwd!".toCharArray())

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
        assertEquals(1, resultEntry.iconId) // iconName=public 反演回官方 PwIcon World=1
        assertEquals("#FF0000", resultEntry.backgroundColor)
        assertEquals("#00FF00", resultEntry.foregroundColor)
        assertEquals("https://custom.override.url", resultEntry.overrideUrl)
        assertEquals(listOf("finance", "critical"), resultEntry.tags)
        assertEquals(1, resultEntry.attachments.size)
        assertEquals("secret.key", resultEntry.attachments[0].name)
        // 已落库附件按名称匹配保留引用，二进制内容不丢
        assertTrue(resultEntry.attachments[0].resolveData(listOf("BIN_DATA_0".toByteArray())).contentEquals("BIN_DATA_0".toByteArray()))

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

        val repository = RealVaultRepository(createMockContext(tempFolder.root), session, com.keepasskey.app.data.logger.DebugLogBuffer(), createTestStrings())

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
        val repository = RealVaultRepository(createMockContext(tempFolder.root), session, com.keepasskey.app.data.logger.DebugLogBuffer(), createTestStrings())

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

        val repository = RealVaultRepository(createMockContext(tempFolder.root), session, com.keepasskey.app.data.logger.DebugLogBuffer(), createTestStrings())

        // 添加一个测试条目
        val newEntry = UiVaultEntry(
            id = KdbxUuid.random().toHexString(),
            title = "Item to recycle",
            username = "user1",
            url = "https://example.com"
        )
        repository.saveEntry(newEntry, passwordChars = "pass1".toCharArray())

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
        val reloadedRepo = RealVaultRepository(createMockContext(tempFolder.root), openSession, com.keepasskey.app.data.logger.DebugLogBuffer(), createTestStrings())
        val reloadedGroups = reloadedRepo.getGroups().first()
        assertTrue("回收站分组应在重开后持久存在", reloadedGroups.any { it.isRecycleBin })

        val reloadedEntries = reloadedRepo.getEntries().first()
        val reloadedItem = reloadedEntries.firstOrNull { it.id == newEntry.id }
        assertNotNull(reloadedItem)
        assertEquals(reloadedDb.recycleBinUuid?.toHexString(), reloadedItem!!.groupId)

        password.fill('0')
    }

    @Test
    fun `saveGroup 重命名与改图标后子条目与子分组完好无损`() = runTest {
        val testFile = File(tempFolder.root, "group_rename_test.kdbx")
        val password = "StrongPassword#2026".toCharArray()

        val session = DatabaseSession()
        val createResult = session.create(
            file = testFile,
            name = "GroupVault",
            passwordChars = password,
            useArgon2 = false // 单元测试快速使用 AES-KDF
        )
        assertTrue(createResult is com.keepasskey.core.result.KdbxResult.Success)

        val repository = RealVaultRepository(createMockContext(tempFolder.root), session, com.keepasskey.app.data.logger.DebugLogBuffer(), createTestStrings())

        // 1. 创建分组（模拟 VaultListViewModel.createGroup：非 UUID 临时 id → 仓库生成新 UUID）
        val groupCreateResult = repository.saveGroup(
            VaultGroup(id = "group_new", name = "财务", parentId = null, iconName = "folder")
        )
        assertTrue(groupCreateResult is com.keepasskey.core.result.KdbxResult.Success)
        val createdUiGroup = repository.getGroups().first().firstOrNull { it.name == "财务" }
        assertNotNull("新建分组应出现在分组投影中", createdUiGroup)

        // 2. 向该分组添加子条目
        val childEntryId = KdbxUuid.random().toHexString()
        val entrySaveResult = repository.saveEntry(
            UiVaultEntry(
                id = childEntryId,
                title = "银行卡",
                username = "alice",
                url = "https://bank.example.com",
                groupId = createdUiGroup!!.id
            ),
            passwordChars = "child_pwd".toCharArray()
        )
        assertTrue(entrySaveResult is com.keepasskey.core.result.KdbxResult.Success)

        // 3. 向该分组添加子分组，并在子分组内放置孙条目
        val subGroupCreateResult = repository.saveGroup(
            VaultGroup(id = "group_sub", name = "子分组", parentId = createdUiGroup.id, iconName = "folder")
        )
        assertTrue(subGroupCreateResult is com.keepasskey.core.result.KdbxResult.Success)
        val subUiGroup = repository.getGroups().first().firstOrNull { it.name == "子分组" }
        assertNotNull(subUiGroup)
        repository.saveEntry(
            UiVaultEntry(
                id = KdbxUuid.random().toHexString(),
                title = "孙条目",
                username = "bob",
                url = "https://sub.example.com",
                groupId = subUiGroup!!.id
            ),
            passwordChars = "grand_pwd".toCharArray()
        )

        // 4. P0-1 原灾难路径：以 UI 投影（仅名称/图标等元数据）回写保存——重命名 + 改图标
        //    原实现会构造仅 4 字段的 KdbxGroup 覆盖既有分组，子条目与子分组全部被清空
        val renameResult = repository.saveGroup(
            createdUiGroup.copy(name = "财务-已重命名", iconName = "work")
        )
        assertTrue(renameResult is com.keepasskey.core.result.KdbxResult.Success)

        // 5. 验证内存树：名称与图标更新生效，子条目与子分组完好无损
        val dbAfterRename = session.databaseFlow.first()!!
        val targetUuid = KdbxUuid.fromHexString(createdUiGroup.id)
        val renamedGroup = dbAfterRename.rootGroup.findGroup(targetUuid)
        assertNotNull("重命名后分组必须仍存在于分组树中", renamedGroup)
        assertEquals("财务-已重命名", renamedGroup!!.name)
        assertEquals("图标应更新为 work 对应的官方 PwIcon 67", 67, renamedGroup.iconId)
        assertEquals("子条目必须完好保留", 1, renamedGroup.entries.size)
        assertEquals("银行卡", renamedGroup.entries[0].title)
        assertEquals(childEntryId, renamedGroup.entries[0].id.toHexString())
        assertEquals("子分组必须完好保留", 1, renamedGroup.subgroups.size)
        assertEquals("子分组", renamedGroup.subgroups[0].name)
        assertEquals("孙条目必须随子分组完好保留", 1, renamedGroup.subgroups[0].entries.size)
        assertEquals("孙条目", renamedGroup.subgroups[0].entries[0].title)
        assertEquals("全树条目数量不得丢失", 2, dbAfterRename.rootGroup.allEntries().size)

        // 6. 持久化往返验证：关闭会话重新打开后，重命名结果与子项均从磁盘完整恢复
        session.close()
        val reopenSession = DatabaseSession()
        val reopenResult = reopenSession.open(testFile, password)
        assertTrue(reopenResult is com.keepasskey.core.result.KdbxResult.Success)
        val reloadedDb = reopenSession.databaseFlow.first()!!
        val reloadedGroup = reloadedDb.rootGroup.findGroup(targetUuid)
        assertNotNull("重开后分组应存在", reloadedGroup)
        assertEquals("财务-已重命名", reloadedGroup!!.name)
        assertEquals(67, reloadedGroup.iconId)
        assertEquals("重开后子条目必须仍在", 1, reloadedGroup.entries.size)
        assertEquals("银行卡", reloadedGroup.entries[0].title)
        assertEquals("重开后子分组必须仍在", 1, reloadedGroup.subgroups.size)
        assertEquals("孙条目", reloadedGroup.subgroups[0].entries[0].title)
        assertEquals(2, reloadedDb.rootGroup.allEntries().size)

        password.fill('0')
    }

    @Test
    fun `duplicateEntry 克隆保真新UUID清历史并持久化往返`() = runTest {
        val testFile = File(tempFolder.root, "duplicate_test.kdbx")
        val password = "StrongPassword#2026".toCharArray()

        val session = DatabaseSession()
        val createResult = session.create(
            file = testFile,
            name = "DupVault",
            passwordChars = password,
            useArgon2 = false
        )
        assertTrue(createResult is com.keepasskey.core.result.KdbxResult.Success)

        val repository = RealVaultRepository(createMockContext(tempFolder.root), session, com.keepasskey.app.data.logger.DebugLogBuffer(), createTestStrings())

        // 1. 创建源条目（含历史修订，验证克隆体不继承 history）
        val srcId = KdbxUuid.random().toHexString()
        val saveResult = repository.saveEntry(
            UiVaultEntry(
                id = srcId,
                title = "原始条目",
                username = "alice",
                url = "https://example.com",
                tags = listOf("finance")
            ),
            passwordChars = "src_pwd_123".toCharArray()
        )
        assertTrue(saveResult is com.keepasskey.core.result.KdbxResult.Success)
        // Fake 投影外的 revisions 走 KDBX 层——再次编辑以制造 history 快照
        repository.saveEntry(
            UiVaultEntry(id = srcId, title = "原始条目v2", username = "alice", url = "https://example.com", tags = listOf("finance")),
            passwordChars = "src_pwd_v2".toCharArray()
        )

        // 2. 克隆
        val dupResult = repository.duplicateEntry(srcId)
        assertTrue("克隆应成功（含真实落盘）", dupResult is com.keepasskey.core.result.KdbxResult.Success)
        val cloneId = (dupResult as com.keepasskey.core.result.KdbxResult.Success).data
        assertTrue("克隆体必须持有全新 UUID", cloneId != srcId)

        // 3. 内存树验证：两份条目共存，克隆体全字段保真且无历史
        val dbAfterDup = session.databaseFlow.first()!!
        val entries = dbAfterDup.rootGroup.allEntries()
        assertEquals("克隆后应为两条条目", 2, entries.size)
        val clone = entries.first { it.id.toHexString() == cloneId }
        val original = entries.first { it.id.toHexString() == srcId }
        assertEquals("标题应保真", "原始条目v2", clone.title)
        assertEquals("用户名应保真", "alice", clone.userName)
        assertEquals("URL 应保真", "https://example.com", clone.url)
        assertEquals("密码应保真", "src_pwd_v2", clone.password?.readString())
        assertEquals("标签应保真", listOf("finance"), clone.tags)
        assertEquals("克隆体不得继承历史修订", 0, clone.history.size)
        assertTrue("原条目应保留其历史修订", original.history.isNotEmpty())
        assertEquals("克隆体父组应与原条目一致", original.parentGroupId, clone.parentGroupId)

        // 4. 不存在的条目 / 非法 id → 如实失败
        assertTrue(repository.duplicateEntry("deadbeef") is com.keepasskey.core.result.KdbxResult.Failure)

        // 5. 持久化往返：重开后克隆体仍在
        session.close()
        val reopenSession = DatabaseSession()
        assertTrue(reopenSession.open(testFile, password) is com.keepasskey.core.result.KdbxResult.Success)
        assertEquals(2, reopenSession.databaseFlow.first()!!.rootGroup.allEntries().size)
        assertTrue(
            "重开后克隆体应存在",
            reopenSession.databaseFlow.first()!!.rootGroup.allEntries().any { it.id.toHexString() == cloneId }
        )

        password.fill('0')
    }

    @Test
    fun `本地尚无 kdbx 文件时 getDatabases 如实返回空列表不伪造默认库`() = runTest {
        val emptyDir = tempFolder.newFolder("empty_vault_dir")
        val session = DatabaseSession()
        val repository = RealVaultRepository(createMockContext(emptyDir), session, com.keepasskey.app.data.logger.DebugLogBuffer(), createTestStrings())

        val databases = repository.getDatabases().first()
        assertTrue("无文件时必须返回空列表，绝不能伪造 default_vault", databases.isEmpty())
    }

    @Test
    fun `importExternalDatabase 导入外部文件成功后自动更新列表并设为当前激活库`() = runTest {
        val storageDir = tempFolder.newFolder("app_storage")
        val externalDir = tempFolder.newFolder("external_storage")
        val externalFile = File(externalDir, "source_vault.kdbx")
        externalFile.writeBytes(byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte())) // KDBX signature prefix

        val session = DatabaseSession()
        val repository = RealVaultRepository(createMockContext(storageDir), session, com.keepasskey.app.data.logger.DebugLogBuffer(), createTestStrings())

        val importResult = repository.importExternalDatabase("source_vault.kdbx", externalFile.absolutePath)
        assertTrue("导入应成功", importResult is com.keepasskey.core.result.KdbxResult.Success)

        val databases = repository.getDatabases().first()
        assertEquals(1, databases.size)
        assertEquals("source_vault.kdbx", databases[0].name)
        assertTrue("新导入的数据库应自动设为当前激活态", databases[0].isActive)
    }

    @Test
    fun `外部物理数据库文件通过 unlockActiveDatabase 正确解锁且不复制到内部目录`() = runTest {
        val storageDir = tempFolder.newFolder("internal_storage")
        val externalDir = tempFolder.newFolder("external_docs")
        val externalKdbx = File(externalDir, "my_external.kdbx")
        val password = "MySecretPassword#2026".toCharArray()

        // 预先创建一个真实的有效 KDBX 库
        val tempSession = DatabaseSession()
        tempSession.create(externalKdbx, "MyExternal", password, useArgon2 = false)
        tempSession.close()

        val session = DatabaseSession()
        val repository = RealVaultRepository(createMockContext(storageDir), session, com.keepasskey.app.data.logger.DebugLogBuffer(), createTestStrings())

        // 登记外部数据库
        repository.importExternalDatabase("my_external.kdbx", externalKdbx.absolutePath)

        // 验证内部沙盒目录下未产生该文件（绝不强制复制到私有沙盒）
        val internalCopy = File(storageDir, "my_external.kdbx")
        assertFalse("外部库绝不应被强制复制到内部沙盒目录", internalCopy.exists())

        // 解锁外部数据库
        val unlockResult = repository.unlockActiveDatabase(password)
        assertTrue("外部数据库应顺利解锁成功: $unlockResult", unlockResult is com.keepasskey.core.result.KdbxResult.Success)

        // 验证会话已打开，且条目与群组正常可读
        assertFalse("解锁后仓库不应处于锁定态", repository.isLocked())
        val groups = repository.getGroups().first()
        assertTrue("应包含根群组", groups.isNotEmpty())

        password.fill('0')
    }

    /**
     * ISSUE-P1-03 数据完整性：删除「包含回收站」的祖先组必须走物理删除（官方
     * pgRecycleBin.IsContainedIn(pg) 分支），绝不能把回收站连同子树移入自身——
     * 旧实现会因 saveGroup 找不到已被删的父组而静默丢库。
     */
    @Test
    fun `删除包含回收站的父组走物理删除避免自嵌套丢库`() = runTest {
        val rootId = KdbxUuid.random()
        val binId = KdbxUuid.random()
        val ancestorId = KdbxUuid.random()
        val binEntryId = KdbxUuid.random()

        val binEntry = KdbxEntry(
            id = binEntryId,
            parentGroupId = binId,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("In Bin", false))
        )
        val recycleBin = KdbxGroup(
            id = binId,
            parentGroupId = ancestorId,
            name = "Recycle Bin",
            iconId = 43,
            entries = listOf(binEntry)
        )
        val ancestor = KdbxGroup(
            id = ancestorId,
            parentGroupId = rootId,
            name = "Ancestor Of Bin",
            subgroups = listOf(recycleBin)
        )
        val root = KdbxGroup(id = rootId, name = "Root", subgroups = listOf(ancestor))
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(),
            rootGroup = root,
            recycleBinEnabled = true,
            recycleBinUuid = binId
        )

        val session = DatabaseSession()
        session.setDatabaseForTesting(db)
        val repository = RealVaultRepository(
            createMockContext(tempFolder.root), session,
            com.keepasskey.app.data.logger.DebugLogBuffer(), createTestStrings()
        )

        repository.deleteGroup(ancestorId.toHexString())

        val after = session.databaseFlow.first()!!
        val remainingGroups = after.rootGroup.allGroups()
        assertNull("祖先组应被物理删除", remainingGroups.firstOrNull { it.id == ancestorId })
        assertNull("其内嵌回收站随子树物理删除", remainingGroups.firstOrNull { it.id == binId })
        assertNull("回收站内条目随子树物理删除", after.rootGroup.allEntries().firstOrNull { it.id == binEntryId })
        assertTrue("根组必须存活，不得整库丢失", remainingGroups.any { it.id == rootId })
        assertTrue("应追加祖先组墓碑", after.deletedObjects.any { it.id == ancestorId })
    }

    /**
     * ISSUE-P1-03：条目位于回收站的**嵌套子分组**内时按官方
     * pgParent.IsContainedIn(pgRecycleBin) 物理删除并追加墓碑；
     * 旧实现只比对直接父组，会漏判嵌套情形而错误地再次「移入回收站」。
     */
    @Test
    fun `删除回收站子分组内的条目走物理删除并追加墓碑`() = runTest {
        val rootId = KdbxUuid.random()
        val binId = KdbxUuid.random()
        val binSubId = KdbxUuid.random()
        val entryId = KdbxUuid.random()

        val nestedEntry = KdbxEntry(
            id = entryId,
            parentGroupId = binSubId,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Nested In Bin", false))
        )
        val binSub = KdbxGroup(
            id = binSubId,
            parentGroupId = binId,
            name = "Bin Subfolder",
            entries = listOf(nestedEntry)
        )
        val recycleBin = KdbxGroup(
            id = binId,
            parentGroupId = rootId,
            name = "Recycle Bin",
            iconId = 43,
            subgroups = listOf(binSub)
        )
        val root = KdbxGroup(id = rootId, name = "Root", subgroups = listOf(recycleBin))
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(),
            rootGroup = root,
            recycleBinEnabled = true,
            recycleBinUuid = binId
        )

        val session = DatabaseSession()
        session.setDatabaseForTesting(db)
        val repository = RealVaultRepository(
            createMockContext(tempFolder.root), session,
            com.keepasskey.app.data.logger.DebugLogBuffer(), createTestStrings()
        )

        repository.deleteEntry(entryId.toHexString())

        val after = session.databaseFlow.first()!!
        assertNull("嵌套于回收站内的条目应被物理删除", after.rootGroup.allEntries().firstOrNull { it.id == entryId })
        assertTrue("应追加该条目墓碑", after.deletedObjects.any { it.id == entryId })
        assertNotNull("回收站组自身应保留", after.rootGroup.allGroups().firstOrNull { it.id == binId })
    }
}
