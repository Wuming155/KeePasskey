package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
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
import java.time.Instant

/**
 * `ISSUE-P2-633`：历史回滚必须恢复**历史版本自己的内容与附件**。
 *
 * 缺陷背景：整改前回滚把历史 UI 投影交给通用 `saveEntry`，而投影里没有历史附件字节来源，
 * `AttachmentMergePolicy` 只能按当前条目的同下标 / 同名项取数据 ⇒ 附件被当前内容顶替
 * （或未命中时构造空附件）。用户看到「已回滚」，导出的附件却不是那一版。
 *
 * 现行路径：`VaultEntryRevisionRollback` 直接以历史 `KdbxEntry` 落树，附件连同字节所有权一起恢复。
 * 本用例覆盖：同名异字节、历史独有附件、回滚前版本归档、句柄失效零写入、库内元数据保留。
 */
class VaultEntryRevisionRollbackTest {

    private val rootGroupId = KdbxUuid.random()
    private val entryId = KdbxUuid.random()
    private val olderBytes = "ATTACHMENT_OLDER_VERSION".toByteArray()
    private val currentBytes = "ATTACHMENT_CURRENT_VERSION".toByteArray()

    private val olderInstant: Instant = Instant.ofEpochMilli(1_700_000_100_000)

    /**
     * 库内条目：当前版附件 = [currentBytes]；历史里那版附件 = [olderBytes]，
     * 且历史版还有一个当前已删除的 `old-only.bin`（验证「历史独有附件」也能恢复）。
     */
    private fun newDatabase(): Pair<KdbxDatabase, KdbxEntry> {
        val olderSnapshot = KdbxEntry(
            id = entryId,
            parentGroupId = rootGroupId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Older Title", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("older_password", true)
            ),
            customFields = listOf(
                KdbxCustomField("PIN", ProtectedString("1234", isProtected = true))
            ),
            attachments = listOf(
                KdbxAttachment(name = "report.bin", refIndex = 0, data = olderBytes),
                KdbxAttachment(name = "old-only.bin", refIndex = 1, data = "ONLY_IN_OLDER".toByteArray())
            ),
            tags = listOf("old-tag"),
            times = KdbxTimes(
                creationTime = olderInstant,
                lastModificationTime = olderInstant,
                lastAccessTime = olderInstant,
                expiryTime = olderInstant,
                locationChanged = olderInstant
            )
        )
        val current = KdbxEntry(
            id = entryId,
            parentGroupId = rootGroupId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Current Title", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("current_password", true)
            ),
            attachments = listOf(
                // 同名、同下标，字节不同——正是「按当前索引取数据」会出错的那一态
                KdbxAttachment(name = "report.bin", refIndex = 0, data = currentBytes)
            ),
            tags = listOf("current-tag"),
            // 收藏标记存于 customData：回滚不得翻转它
            customData = mapOf(RealVaultRepository.FAVORITE_CUSTOM_DATA_KEY to "true"),
            history = listOf(olderSnapshot),
            times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1_700_000_200_000))
        )
        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(),
            rootGroup = KdbxGroup(id = rootGroupId, name = "Root", entries = listOf(current)),
            historyMaxItems = 10
        )
        return database to current
    }

    private suspend fun newFixture(): Pair<DatabaseSession, KdbxEntry> {
        val (database, entry) = newDatabase()
        val session = DatabaseSession()
        session.setDatabaseForTesting(database)
        return session to entry
    }

    private fun rollback(session: DatabaseSession) =
        VaultEntryRevisionRollback(session) { KdbxResult.Success(Unit) }

    private fun handleOf(entry: KdbxEntry): String =
        EntryRevisionHandle.encode(0, entry.history[0])

    private suspend fun DatabaseSession.loadedEntry(): KdbxEntry {
        val db = databaseFlow.first()!!
        return db.rootGroup.allEntries().first { it.id == entryId }
    }

    @Test
    fun `回滚恢复历史附件字节而非当前索引内容`() = runTest {
        val (session, entry) = newFixture()

        val result = rollback(session).rollback(entryId.toHexString(), handleOf(entry))
        assertTrue("回滚必须成功", result is KdbxResult.Success)

        val restored = session.loadedEntry()
        assertEquals("同名同下标附件必须恢复历史字节", 2, restored.attachments.size)
        assertEquals("report.bin", restored.attachments[0].name)
        assertTrue(
            "第一份附件必须是历史版本的字节（不是当前版本）",
            restored.attachments[0].data.contentEquals(olderBytes)
        )
        assertEquals("历史独有附件必须一并恢复", "old-only.bin", restored.attachments[1].name)
        assertTrue(
            "历史独有附件字节必须恢复",
            restored.attachments[1].data.contentEquals("ONLY_IN_OLDER".toByteArray())
        )
    }

    @Test
    fun `回滚恢复历史字段与自定义字段且保留条目身份与库内元数据`() = runTest {
        val (session, entry) = newFixture()

        rollback(session).rollback(entryId.toHexString(), handleOf(entry))

        val restored = session.loadedEntry()
        assertEquals("标题取历史版本", "Older Title", restored.title)
        assertEquals("密码取历史版本", "older_password", restored.password?.readString())
        assertEquals("自定义字段取历史版本", "1234", restored.customFields.single().value.readString())
        assertEquals("标签取历史版本", listOf("old-tag"), restored.tags)
        assertEquals("条目 UUID 必须保持不变", entryId, restored.id)
        assertEquals("父组必须保持不变", rootGroupId, restored.parentGroupId)
        assertEquals(
            "收藏标记等库内元数据不得随内容回滚",
            "true",
            restored.customData[RealVaultRepository.FAVORITE_CUSTOM_DATA_KEY]
        )
    }

    @Test
    fun `回滚把回滚前的当前版本归档为最新历史`() = runTest {
        val (session, entry) = newFixture()

        rollback(session).rollback(entryId.toHexString(), handleOf(entry))

        val restored = session.loadedEntry()
        val archived = restored.history.firstOrNull()
        assertNotNull("回滚前版本必须归档为历史", archived)
        assertEquals("归档的应是回滚前的当前内容", "Current Title", archived?.title)
        assertTrue(
            "归档快照不得携带嵌套历史",
            archived?.history?.isEmpty() == true
        )
    }

    @Test
    fun `句柄对应快照已被修剪时零写入并如实失败`() = runTest {
        val (database, entry) = newDatabase()
        // 库内已无该快照（模拟按龄修剪后再点旧列表行）
        val trimmed = database.copy(
            rootGroup = database.rootGroup.copy(
                entries = listOf(entry.copy(history = emptyList()))
            )
        )
        val session = DatabaseSession()
        session.setDatabaseForTesting(trimmed)

        val result = rollback(session).rollback(entryId.toHexString(), handleOf(entry))

        assertTrue("必须如实失败，不得谎报已回滚", result is KdbxResult.Failure)
        assertEquals(
            KdbxError.HISTORY_REVISION_UNAVAILABLE,
            (result as KdbxResult.Failure).code
        )
        val untouched = session.loadedEntry()
        assertEquals("失败路径不得改动条目", "Current Title", untouched.title)
        assertTrue("失败路径不得新增历史", untouched.history.isEmpty())
    }

    @Test
    fun `回滚后再次回滚到归档版本可往返`() = runTest {
        val (session, entry) = newFixture()

        rollback(session).rollback(entryId.toHexString(), handleOf(entry))
        // 回滚后列表头部即「回滚前的当前版本」，按句柄再回滚一次应回到当前内容
        val restoredNow = session.loadedEntry()
        // 句柄取自**快照**（history 头部即回滚前的当前版本），不是条目自身的修改时间
        val backHandle = EntryRevisionHandle.encode(0, restoredNow.history[0])
        rollback(session).rollback(entryId.toHexString(), backHandle)

        val back = session.loadedEntry()
        assertEquals("往返回滚后应回到原当前版本", "Current Title", back.title)
        assertTrue(
            "往返回滚后附件应回到当前版本字节",
            back.attachments.single().data.contentEquals(currentBytes)
        )
    }
}
