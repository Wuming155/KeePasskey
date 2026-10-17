package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目写入意图的协调器级验收：分组落点（`ISSUE-P2-635`）与自定义字段删除 / 改名（`ISSUE-P2-634`）。
 *
 * 两件事共用同一条写入口（`VaultEntryWriteCoordinator.saveEntryInternal`），故同文件验收：
 * - 635：分组 chip 的 `null` 是「移动到根」，不是「保留原分组」；换址走会话单次变换
 *   （不再 `delete + save`，中间态不产生条目消失窗口）；目标失效一律拒绝写入。
 * - 634：载入基线里被移除 / 改名的旧键必须真的从库内消失；重名键与标准字段冲突被拒绝。
 *
 * 会话用 `setDatabaseForTesting` 注入（与既有仓库层用例同口径），`persistSession` 打桩成功，
 * 断言全部落在会话内存树上。
 */
class EntryWriteIntentCoordinatorTest {

    private val strings = StringsProvider { _, _ -> "" }
    private val rootGroupId = KdbxUuid.random()
    private val childGroupId = KdbxUuid.random()
    private val otherGroupId = KdbxUuid.random()
    private val recycleBinId = KdbxUuid.random()
    private val entryId = KdbxUuid.random()

    private fun existingEntry(): KdbxEntry = KdbxEntry(
        id = entryId,
        parentGroupId = childGroupId,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("Original", false),
            KdbxConstants.Fields.USER_NAME to ProtectedString("alice", false),
            KdbxConstants.Fields.URL to ProtectedString("https://example.com", false),
            KdbxConstants.Fields.NOTES to ProtectedString("", false)
        ),
        customFields = listOf(
            KdbxCustomField("X", ProtectedString("x-value", isProtected = false)),
            KdbxCustomField("SECRET", ProtectedString("s-value", isProtected = true))
        )
    )

    private fun newDatabase(): KdbxDatabase {
        val root = KdbxGroup(
            id = rootGroupId,
            name = "Root",
            subgroups = listOf(
                KdbxGroup(id = childGroupId, parentGroupId = rootGroupId, name = "Child"),
                KdbxGroup(id = otherGroupId, parentGroupId = rootGroupId, name = "Other"),
                KdbxGroup(id = recycleBinId, parentGroupId = rootGroupId, name = "RecycleBin")
            )
        )
        // 条目挂在 Child 下（与 parentGroupId 一致）
        val withEntry = root.copy(
            subgroups = root.subgroups.map {
                if (it.id == childGroupId) it.copy(entries = listOf(existingEntry())) else it
            }
        )
        return KdbxDatabase(
            header = KdbxHeader.createDefault(),
            rootGroup = withEntry,
            recycleBinUuid = recycleBinId,
            historyMaxItems = 10
        )
    }

    private fun newSession(): DatabaseSession = DatabaseSession().apply { setDatabaseForTesting(newDatabase()) }

    private fun coordinator(session: DatabaseSession): VaultEntryWriteCoordinator =
        VaultEntryWriteCoordinator(strings, session, VaultEntryMapper(strings)) { KdbxResult.Success(Unit) }

    private fun uiEntry(
        groupId: String?,
        customFields: List<UiCustomField> = emptyList()
    ): UiVaultEntry = UiVaultEntry(
        id = entryId.toHexString(),
        title = "Edited",
        username = "alice",
        url = "https://example.com",
        notes = "",
        groupId = groupId,
        customFields = customFields
    )

    private suspend fun DatabaseSession.loadedEntry(): KdbxEntry {
        val db = databaseFlow.first()!!
        return db.rootGroup.allEntries().single { it.id == entryId }
    }

    private suspend fun DatabaseSession.entryCount(): Int =
        databaseFlow.first()!!.rootGroup.allEntries().count { it.id == entryId }

    // ---------------- ISSUE-P2-635 ----------------

    @Test
    fun `选择根分组会真的把条目移回根`() = runTest {
        val session = newSession()

        val result = coordinator(session).saveEntryWithEraseContract(
            uiEntry(groupId = null),
            passwordChars = null,
            totpSecretChars = null,
            protectedFieldChars = emptyMap(),
            groupIntent = EntryGroupIntent.Root
        )

        assertTrue("移回根组必须成功", result is KdbxResult.Success)
        val moved = session.loadedEntry()
        assertEquals("父组必须变成根分组", rootGroupId, moved.parentGroupId)
        assertEquals("换址不得留下副本", 1, session.entryCount())
        assertEquals("换址同样产生一次历史快照", 1, moved.history.size)
    }

    @Test
    fun `保留意图不会把根级选中值误当成移动`() = runTest {
        val session = newSession()

        coordinator(session).saveEntryWithEraseContract(
            uiEntry(groupId = null),
            passwordChars = null,
            totpSecretChars = null,
            protectedFieldChars = emptyMap(),
            groupIntent = EntryGroupIntent.Keep
        )

        assertEquals(
            "Keep 意图必须保留原父组（导入 / 草稿等未表达移动意图的调用方依赖此语义）",
            childGroupId,
            session.loadedEntry().parentGroupId
        )
    }

    @Test
    fun `移动到另一个子分组成功`() = runTest {
        val session = newSession()

        val result = coordinator(session).saveEntryWithEraseContract(
            uiEntry(groupId = otherGroupId.toHexString()),
            passwordChars = null,
            totpSecretChars = null,
            protectedFieldChars = emptyMap(),
            groupIntent = EntryGroupIntent.ToGroup(otherGroupId.toHexString())
        )

        assertTrue(result is KdbxResult.Success)
        assertEquals(otherGroupId, session.loadedEntry().parentGroupId)
        assertEquals(1, session.entryCount())
    }

    @Test
    fun `目标分组不存在时拒绝写入且不改动树`() = runTest {
        val session = newSession()
        val missing = KdbxUuid.random().toHexString()

        val result = coordinator(session).saveEntryWithEraseContract(
            uiEntry(groupId = missing),
            passwordChars = null,
            totpSecretChars = null,
            protectedFieldChars = emptyMap(),
            groupIntent = EntryGroupIntent.ToGroup(missing)
        )

        assertTrue(result is KdbxResult.Failure)
        assertEquals(KdbxError.ENTRY_GROUP_TARGET_INVALID, (result as KdbxResult.Failure).code)
        val untouched = session.loadedEntry()
        assertEquals("失败路径不得改动条目", "Original", untouched.title)
        assertEquals("失败路径不得换址", childGroupId, untouched.parentGroupId)
        assertTrue("失败路径不得写历史", untouched.history.isEmpty())
    }

    @Test
    fun `移动到回收站被拒绝`() = runTest {
        val session = newSession()

        val result = coordinator(session).saveEntryWithEraseContract(
            uiEntry(groupId = recycleBinId.toHexString()),
            passwordChars = null,
            totpSecretChars = null,
            protectedFieldChars = emptyMap(),
            groupIntent = EntryGroupIntent.ToGroup(recycleBinId.toHexString())
        )

        assertTrue(result is KdbxResult.Failure)
        assertEquals(KdbxError.ENTRY_GROUP_TARGET_INVALID, (result as KdbxResult.Failure).code)
        assertEquals("条目必须留在原分组", childGroupId, session.loadedEntry().parentGroupId)
    }

    // ---------------- ISSUE-P2-634 ----------------

    @Test
    fun `删除自定义字段后库内不再含该键`() = runTest {
        val session = newSession()
        val loadedKeys = mapOf(
            "${entryId.toHexString()}_X" to "X",
            "${entryId.toHexString()}_SECRET" to "SECRET"
        )

        val result = coordinator(session).saveEntryWithEraseContract(
            uiEntry(
                groupId = childGroupId.toHexString(),
                customFields = listOf(
                    UiCustomField(
                        id = "${entryId.toHexString()}_SECRET",
                        key = "SECRET",
                        value = "",
                        isProtected = true
                    )
                )
            ),
            passwordChars = null,
            totpSecretChars = null,
            protectedFieldChars = emptyMap(),
            groupIntent = EntryGroupIntent.Keep,
            loadedCustomFieldKeys = loadedKeys
        )

        assertTrue(result is KdbxResult.Success)
        val stored = session.loadedEntry()
        assertEquals(
            "被删除的 X 必须真的从库内消失",
            listOf("SECRET"),
            stored.customFields.map { it.key }
        )
        assertEquals(
            "未编辑的受保护字段必须按既有口径回填原值",
            "s-value",
            stored.customFields.single().value.readString()
        )
    }

    @Test
    fun `重名字段保存被拒绝且草稿保留`() = runTest {
        val session = newSession()
        val duplicate = listOf(
            UiCustomField(id = "f1", key = "dup", value = "a"),
            UiCustomField(id = "f2", key = "dup", value = "b")
        )

        val result = coordinator(session).saveEntryWithEraseContract(
            uiEntry(groupId = childGroupId.toHexString(), customFields = duplicate),
            passwordChars = null,
            totpSecretChars = null,
            protectedFieldChars = emptyMap(),
            groupIntent = EntryGroupIntent.Keep
        )

        assertTrue(result is KdbxResult.Failure)
        assertEquals(KdbxError.ENTRY_CUSTOM_FIELD_INVALID, (result as KdbxResult.Failure).code)
        val untouched = session.loadedEntry()
        assertEquals("拒绝保存时库内字段不得变化", listOf("X", "SECRET"), untouched.customFields.map { it.key })
        assertTrue("拒绝保存时不得写历史", untouched.history.isEmpty())
    }

    @Test
    fun `与标准字段冲突的自定义键保存被拒绝`() = runTest {
        val session = newSession()

        val result = coordinator(session).saveEntryWithEraseContract(
            uiEntry(
                groupId = childGroupId.toHexString(),
                customFields = listOf(UiCustomField(id = "f1", key = KdbxConstants.Fields.PASSWORD, value = "x"))
            ),
            passwordChars = null,
            totpSecretChars = null,
            protectedFieldChars = emptyMap(),
            groupIntent = EntryGroupIntent.Keep
        )

        assertTrue(result is KdbxResult.Failure)
        assertEquals(KdbxError.ENTRY_CUSTOM_FIELD_INVALID, (result as KdbxResult.Failure).code)
        assertNotNull(session.loadedEntry())
        assertTrue("拒绝保存时不得写历史", session.loadedEntry().history.isEmpty())
    }
}
