package com.keepasskey.app.data.repository

import android.content.Intent
import android.net.Uri
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * `ISSUE-P2-632/633/634/635` 的**设备侧产品仓库验收**。
 *
 * 与 JVM 用例的分工：
 * - 本件只经公开的 `VaultRepository` 写入 / 读取 / 回滚，再以真实 `File` 或 SAF 文档字节
 *   独立重解析核对，不复用会话内存树作为唯一证据；
 * - 覆盖历史句柄选择、历史附件恢复、自定义字段删除 / 改名 / 合法键拒绝、分组三向换址；
 * - 另含只读会话拒绝写、失效分组拒绝写，以及 `historyMaxItems` 已变小后旧句柄的零写入失败。
 *
 * 未覆盖（不得据本件外推）：
 * - 历史列表 / 编辑页 / 回滚按钮的 Compose 点击级 UI（本件走同一生产仓库 API）；
 * - 独立 KeePass / KeePassXC 二进制读取（仓库内无该客户端；`KdbxFile` 重解析不是第三方互操作）。
 */
@RunWith(AndroidJUnit4::class)
class HistoryFieldGroupDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val password = "P2-632-635-Device-2026".toCharArray()

    private val repository: VaultRepository
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.keepasskey.app.di.AutofillDeviceGraph::class.java
        ).vaultRepository()

    private val session: DatabaseSession
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.keepasskey.app.di.AutofillDeviceGraph::class.java
        ).databaseSession()

    private val ownedFiles = mutableListOf<File>()
    private val ownedSaf = mutableListOf<Pair<SafCreationProbeClient, Uri>>()

    @After
    fun tearDown() {
        runCatching { runBlocking { repository.lockDatabase() } }
        ownedSaf.forEach { (client, uri) ->
            runCatching { releasePersisted(uri) }
            runCatching { client.cleanupAndEnd() }
        }
        ownedFiles.forEach { file ->
            runCatching { file.delete() }
            runCatching { File(file.absolutePath + ".bak").delete() }
        }
        password.fill('\u0000')
    }

    @Test
    fun `三项历史可选不同密码且重开后回滚目标准确`() = runBlocking {
        val file = registerFreshFile("p2-632")
        val entryId = registerEntry(file, title = "History target", password = "P0")

        save(file, entryId, title = "History target", password = "P1")
        save(file, entryId, title = "History target", password = "P2")
        save(file, entryId, title = "History target", password = "P3")

        // 每次写入都带一次历史；最新一次保存后，文件的当前密码必须是 P3。
        assertEquals("P3", currentPassword(file, entryId))

        val current = requireNotNull(repository.getKdbxEntry(entryId)) {
            "活动库必须能按 id 找到条目"
        }
        assertEquals("三次保存必须产生三份历史", 3, current.history.size)

        val handles = projectedRevisionIds(entryId)
        assertEquals("历史投影必须逐行可区分", 3, handles.toSet().size)
        val historicalPasswords = handles.map { handle ->
            val chars = repository.getEntryRevisionPasswordChars(entryId, handle)
                ?: error("历史句柄必须命中快照: $handle")
            try {
                String(chars)
            } finally {
                chars.fill('\u0000')
            }
        }
        assertEquals("历史列表必须分别对应当前版本之前的三个版本", listOf("P2", "P1", "P0"), historicalPasswords)

        val rollback = repository.rollbackEntryToRevision(entryId, handles[1])
        assertTrue("回滚到第二历史版本必须成功: $rollback", rollback is KdbxResult.Success)
        assertEquals("回滚后的当前值必须来自所选版本", "P1", currentPassword(file, entryId))

        val afterRollback = requireNotNull(repository.getKdbxEntry(entryId))
        assertEquals(
            "回滚前版本必须归档为最新历史",
            "P3",
            afterRollback.history.first().password?.readString()
        )

        // 锁库并重新打开同一正式文件，确认不是只看会话内存。
        repository.lockDatabase()
        unlockActiveFile()
        assertEquals("重开后仍须看到回滚目标", "P1", currentPassword(file, entryId))
        val reopenedHistory = projectedRevisionIds(entryId)
        assertEquals("回滚后正式文件必须保存历史", 3, reopenedHistory.size)

        // 再点回滚前归档版本，往返恢复（历史索引与时间戳仍需唯一命中）。
        val backHandle = reopenedHistory.first()
        val back = repository.rollbackEntryToRevision(entryId, backHandle)
        assertTrue("回滚归档版本必须成功: $back", back is KdbxResult.Success)
        assertEquals("往返后恢复回滚前当前值", "P3", currentPassword(file, entryId))
    }

    @Test
    fun `头插后详情列表旧历史句柄仍命中原修订`() = runBlocking {
        val file = registerFreshFile("p2-632-head-insert")
        val entryId = registerEntry(file, title = "Head insert target", password = "P0")
        save(file, entryId, title = "Head insert target", password = "P1")
        save(file, entryId, title = "Head insert target", password = "P2")
        save(file, entryId, title = "Head insert target", password = "P3")

        val visibleHandles = projectedRevisionIds(entryId)
        assertEquals("插入前历史行句柄必须逐项唯一", 3, visibleHandles.toSet().size)
        val staleVisibleHandle = visibleHandles[1]
        val beforeInsert = repository.getEntryRevisionPasswordChars(entryId, staleVisibleHandle)
            ?: error("头插前详情页第二历史行必须对应 P1")
        try {
            assertEquals("头插前第二历史行必须对应 P1", "P1", String(beforeInsert))
        } finally {
            beforeInsert.fill('\u0000')
        }

        // 模拟详情页保留旧行句柄时另一条写路径在历史头部插入新修订。
        save(file, entryId, title = "Head insert target", password = "P4")
        assertEquals("头插后正式文件当前密码必须为 P4", "P4", currentPassword(file, entryId))

        val afterInsert = repository.getEntryRevisionPasswordChars(entryId, staleVisibleHandle)
            ?: error("旧可见行句柄必须经修订时间戳定位回原快照")
        try {
            assertEquals("头插不得让旧可见行漂移到相邻修订", "P1", String(afterInsert))
        } finally {
            afterInsert.fill('\u0000')
        }
        val rollback = repository.rollbackEntryToRevision(entryId, staleVisibleHandle)
        assertTrue("使用头插前的行句柄回滚必须命中原修订: $rollback", rollback is KdbxResult.Success)
        assertEquals("回滚目标必须仍为旧行对应的 P1", "P1", currentPassword(file, entryId))

        val parsed = parse(file)
        try {
            assertEquals(
                "头插后旧行句柄回滚的正式 KDBX 当前密码必须为 P1",
                "P1",
                parsed.rootGroup.findEntry(hexUuid(entryId))?.password?.readString()
            )
        } finally {
            parsed.clearSensitiveData()
        }
    }

    @Test
    fun `历史附件替换与独有附件回滚按字节恢复且锁定清理不损坏`() = runBlocking {
        val file = registerFreshFile("p2-633")
        val entryId = registerEntry(file, title = "Attachment target", password = "base")
        val older = ByteArray(2_400_000) { (it % 251).toByte() }
        val current = ByteArray(2_600_000) { ((it * 7) % 253).toByte() }
        val onlyInHistory = "ONLY-IN-HISTORY-#633".toByteArray()

        // 当前/历史两侧都使用“同名 + 同下标”：按当前附件合并会替换成 current。
        assertTrue(
            "历史附件版本保存失败",
            repository.saveEntry(
                currentUiEntry(
                    entryId,
                    title = "Attachment target",
                    attachments = listOf(
                        UiAttachment(
                            id = "$entryId-0",
                            fileName = "report.bin",
                            fileSizeFormatted = "${older.size} B",
                            refIndex = 0,
                            data = older
                        ),
                        UiAttachment(
                            id = "$entryId-1",
                            fileName = "old-only.bin",
                            fileSizeFormatted = "${onlyInHistory.size} B",
                            refIndex = 1,
                            data = onlyInHistory
                        )
                    )
                )
            ) is KdbxResult.Success
        )
        assertTrue(
            "当前附件版本保存失败",
            repository.saveEntry(
                currentUiEntry(
                    entryId,
                    title = "Attachment target",
                    attachments = listOf(
                        UiAttachment(
                            id = "$entryId-0",
                            fileName = "report.bin",
                            fileSizeFormatted = "${current.size} B",
                            refIndex = 0,
                            data = current
                        )
                    )
                )
            ) is KdbxResult.Success
        )
        val handles = projectedRevisionIds(entryId)
        val olderHandle = handles.first { handle ->
            val snapshot = repository.getEntryRevisionSnapshot(entryId, handle)
            snapshot?.entry?.attachments?.map { it.fileName } == listOf("report.bin", "old-only.bin")
        }
        val rollback = repository.rollbackEntryToRevision(entryId, olderHandle)
        assertTrue("历史附件回滚失败: $rollback", rollback is KdbxResult.Success)

        val parsed = parse(file)
        try {
            val restored = parsed.rootGroup.findEntry(hexUuid(entryId)) ?: error("重解析后条目缺失")
            assertEquals("必须恢复历史两份附件", 2, restored.attachments.size)
            assertArrayEquals("同名同下标必须恢复历史字节", older, restored.attachments[0].data)
            assertEquals("历史独有附件名必须恢复", "old-only.bin", restored.attachments[1].name)
            assertArrayEquals("历史独有附件字节必须恢复", onlyInHistory, restored.attachments[1].data)
        } finally {
            parsed.clearSensitiveData()
        }

        // 重新打开仍可读，锁定清理后再重开，确认恢复后的 spill/source 未悬空。
        repository.lockDatabase()
        unlockActiveFile()
        val afterLock = parse(file)
        try {
            val restored = afterLock.rootGroup.findEntry(hexUuid(entryId)) ?: error("锁定后条目缺失")
            assertEquals(2, restored.attachments.size)
            assertArrayEquals(older, restored.attachments[0].data)
            assertArrayEquals(onlyInHistory, restored.attachments[1].data)
        } finally {
            afterLock.clearSensitiveData()
        }
    }

    @Test
    fun `自定义字段删除改名和受保护回填在正式文件核对`() = runBlocking {
        val file = registerFreshFile("p2-634")
        val entryId = registerEntry(file, title = "Field target", password = "p")
        val uid = entryId

        assertTrue(
            "字段初始保存失败",
            repository.saveEntry(
                currentUiEntry(
                    entryId,
                    title = "Field target",
                    customFields = listOf(
                        UiCustomField("${uid}_X", "X", "x-value"),
                        UiCustomField("${uid}_SECRET", "SECRET", "", isProtected = true)
                    )
                ),
                protectedFieldChars = mapOf("${uid}_SECRET" to "s-value".toCharArray())
            ) is KdbxResult.Success
        )
        val initial = requireNotNull(repository.getKdbxEntry(entryId))
        assertEquals(
            "受保护字段必须成功写入",
            "s-value",
            initial.customFields.first { it.key == "SECRET" }.value.readString()
        )

        // 删除 X，保留 SECRET 且不显式提交其明文：走 F2 回填，不能被误清空。
        val deleted = repository.saveEntry(
            currentUiEntry(
                entryId,
                title = "Field target",
                customFields = listOf(UiCustomField("${uid}_SECRET", "SECRET", "", isProtected = true))
            ),
            protectedFieldChars = emptyMap(),
            loadedCustomFieldKeys = mapOf(
                "${uid}_X" to "X",
                "${uid}_SECRET" to "SECRET"
            )
        )
        assertTrue("删除字段保存失败: $deleted", deleted is KdbxResult.Success)
        val deletedDb = parse(file)
        try {
            assertEquals(
                "删除后的正式文件不得再有 X",
                null,
                deletedDb.rootGroup.findEntry(hexUuid(entryId))!!.customFields.firstOrNull { it.key == "X" }
            )
        } finally {
            deletedDb.clearSensitiveData()
        }
        assertEquals(
            "未编辑的受保护字段必须回填原值",
            "s-value",
            requireNotNull(repository.getKdbxEntry(entryId)).customFields
                .first { it.key == "SECRET" }.value.readString()
        )

        // X -> Y：旧键必须退役，新键与值存在；同时保留 SECRET。
        val renamed = repository.saveEntry(
            currentUiEntry(
                entryId,
                title = "Field target",
                customFields = listOf(
                    UiCustomField("${uid}_X", "Y", "y-value"),
                    UiCustomField("${uid}_SECRET", "SECRET", "", isProtected = true)
                )
            ),
            loadedCustomFieldKeys = mapOf(
                "${uid}_X" to "X",
                "${uid}_SECRET" to "SECRET"
            )
        )
        assertTrue("改名保存失败: $renamed", renamed is KdbxResult.Success)
        val renamedDb = parse(file)
        try {
            val fields = renamedDb.rootGroup.findEntry(hexUuid(entryId))!!.customFields
            assertEquals("改名后只能有 Y 与 SECRET", listOf("Y", "SECRET"), fields.map { it.key })
            assertEquals("新键必须携带提交值", "y-value", fields.first { it.key == "Y" }.value.readString())
        } finally {
            renamedDb.clearSensitiveData()
        }

        // 同名键 / 标准字段键都必须 fail-closed，正式文件与历史保持不变。
        val beforeInvalid = file.readBytes()
        val duplicate = repository.saveEntry(
            currentUiEntry(
                entryId,
                title = "Field target",
                customFields = listOf(
                    UiCustomField("a", "dup", "1"),
                    UiCustomField("b", "dup", "2")
                )
            )
        )
        assertTrue("同名键必须失败", duplicate is KdbxResult.Failure)
        assertEquals(KdbxError.ENTRY_CUSTOM_FIELD_INVALID, (duplicate as KdbxResult.Failure).code)
        val reserved = repository.saveEntry(
            currentUiEntry(
                entryId,
                title = "Field target",
                customFields = listOf(UiCustomField("t", KdbxConstants.Fields.TITLE, "bad"))
            )
        )
        assertTrue("标准字段键冲突必须失败", reserved is KdbxResult.Failure)
        assertEquals(KdbxError.ENTRY_CUSTOM_FIELD_INVALID, (reserved as KdbxResult.Failure).code)
        assertArrayEquals("拒绝保存不得改写正式文件", beforeInvalid, file.readBytes())
    }

    @Test
    fun `分组三向换址生产保存后父子关系正确且无副本`() = runBlocking {
        val file = registerFreshFile("p2-635")
        val rootId = requireNotNull(session.databaseFlow.value).rootGroup.id.toHexString()
        val childA = createGroup(file, rootId, "Child A")
        val childB = createGroup(file, rootId, "Child B")
        val entryId = registerEntry(file, title = "Move target", password = "p", groupId = childA)

        suspend fun move(intent: EntryGroupIntent, expectedGroup: String) {
            val result = repository.saveEntry(
                currentUiEntry(entryId, title = "Move target", groupId = expectedGroup),
                groupIntent = intent
            )
            assertTrue("换址保存失败（$intent）: $result", result is KdbxResult.Success)
            val parsed = parse(file)
            try {
                val hits = parsed.rootGroup.allEntries().filter { it.id == hexUuid(entryId) }
                assertEquals("换址不得产生副本（$intent）", 1, hits.size)
                assertEquals(
                    "父组必须为期望组（$intent）",
                    hexUuid(expectedGroup).toHexString(),
                    hits.single().parentGroupId?.toHexString()
                )
            } finally {
                parsed.clearSensitiveData()
            }
        }

        // 子组 -> 根
        move(EntryGroupIntent.Root, rootId)
        // 根 -> 子组（A）
        move(EntryGroupIntent.ToGroup(childA), childA)
        // 子组 -> 另一子组（B）
        move(EntryGroupIntent.ToGroup(childB), childB)

        // 失效目标：另一窗口删除目标后保存必须拒绝，条目仍在 Child B。
        assertTrue("目标分组删除失败", repository.deleteGroup(childA) is KdbxResult.Success)
        val missing = repository.saveEntry(
            currentUiEntry(entryId, title = "Move target", groupId = childA),
            groupIntent = EntryGroupIntent.ToGroup(childA)
        )
        assertTrue("失效目标必须失败", missing is KdbxResult.Failure)
        assertEquals(KdbxError.ENTRY_GROUP_TARGET_INVALID, (missing as KdbxResult.Failure).code)
        val afterMissing = parse(file)
        try {
            assertEquals(
                childB.uppercase(),
                afterMissing.rootGroup.findEntry(hexUuid(entryId))?.parentGroupId?.toHexString()
            )
        } finally {
            afterMissing.clearSensitiveData()
        }

        // 只读会话：尝试移回根必须拒绝，且条目仍留在 Child B。
        repository.lockDatabase()
        val readOnlyOpen = repository.unlockActiveDatabase(password.clone(), null, readOnly = true)
        assertTrue("只读重开失败: $readOnlyOpen", readOnlyOpen is KdbxResult.Success)
        val beforeReadOnlyWrite = file.readBytes()
        val readOnlyWrite = repository.saveEntry(
            currentUiEntry(entryId, title = "Move target", groupId = rootId),
            groupIntent = EntryGroupIntent.Root
        )
        assertTrue("只读写入必须失败", readOnlyWrite is KdbxResult.Failure)
        assertArrayEquals("只读拒绝不得改动正式文件", beforeReadOnlyWrite, file.readBytes())
        val readOnlyDb = parse(file)
        try {
            assertEquals(
                childB.uppercase(),
                readOnlyDb.rootGroup.findEntry(hexUuid(entryId))?.parentGroupId?.toHexString()
            )
        } finally {
            readOnlyDb.clearSensitiveData()
        }
    }

    @Test
    fun `修剪后旧历史句柄零写入失败`() = runBlocking {
        val file = registerFreshFile("p2-632-trim")
        val entryId = registerEntry(file, title = "Trim target", password = "v0")
        save(file, entryId, title = "Trim target", password = "v1")
        save(file, entryId, title = "Trim target", password = "v2")
        val firstHandle = projectedRevisionIds(entryId).last()
        val before = file.readBytes()

        // 通过生产会话入口把库级 historyMaxItems 调为 1，真实落盘并重开，使列表中最旧
        // 的两份快照按官方修剪口径消失；随后用原句柄回滚必须零写入失败。
        session.updateDatabaseMeta { it.copy(historyMaxItems = 1) }
        // 上限变更本身只写 Meta；按产品读写口径，随后一次条目修订才触发 HistoryManager 修剪。
        save(file, entryId, title = "Trim target", password = "v3")
        repository.lockDatabase()
        unlockActiveFile()
        val trimmed = requireNotNull(repository.getKdbxEntry(entryId))
        assertEquals("正式文件必须已按新上限修剪历史", 1, trimmed.history.size)
        val afterTrim = file.readBytes()
        val rollback = repository.rollbackEntryToRevision(entryId, firstHandle)
        assertTrue("不存在句柄必须失败: $rollback", rollback is KdbxResult.Failure)
        assertEquals(
            "不存在句柄必须返回历史不可用",
            KdbxError.HISTORY_REVISION_UNAVAILABLE,
            (rollback as KdbxResult.Failure).code
        )
        assertArrayEquals("失败回滚不得改写正式文件", afterTrim, file.readBytes())
        assertNotEquals("修剪前后正式文件必须发生真实变化", 0, before.size)
    }

    @Test
    fun `导出合成互操作库供KeePassXC独立读取`() = runBlocking {
        assertEquals("仅在显式互操作验收时导出", "true", InstrumentationRegistry.getArguments().getString("p2InteropExport"))
        val file = registerFreshFile("interop-export")
        val entryId = registerEntry(file, title = "P2 interoperability sample", password = "old-password")
        val secretKey = "${entryId}_SECRET"
        val secretChars = "synthetic-protected-value".toCharArray()
        val loadedKeys = mapOf(secretKey to "SECRET")
        val initialFields = listOf(
            UiCustomField("${entryId}_X", "X", "retired-value"),
            UiCustomField(secretKey, "SECRET", "", isProtected = true)
        )
        assertTrue("互操作样本自定义字段写入失败", repository.saveEntry(
            currentUiEntry(entryId, "P2 interoperability sample", customFields = initialFields),
            protectedFieldChars = mapOf(secretKey to secretChars),
            loadedCustomFieldKeys = initialFields.associate { it.id to it.key }
        ) is KdbxResult.Success)
        secretChars.fill('\u0000')

        val renamedFields = listOf(
            UiCustomField("${entryId}_X", "Y", "renamed-value"),
            UiCustomField(secretKey, "SECRET", "", isProtected = true)
        )
        assertTrue("互操作样本字段改名失败", repository.saveEntry(
            currentUiEntry(entryId, "P2 interoperability sample", customFields = renamedFields),
            protectedFieldChars = mapOf(secretKey to "synthetic-protected-value".toCharArray()),
            loadedCustomFieldKeys = initialFields.associate { it.id to it.key }
        ) is KdbxResult.Success)

        val oldAttachment = ByteArray(2_400_000) { (it % 251).toByte() }
        val currentAttachment = ByteArray(2_600_000) { ((it * 7) % 253).toByte() }
        val currentFields = listOf(
            UiCustomField("${entryId}_Y", "Y", "renamed-value"),
            UiCustomField(secretKey, "SECRET", "", isProtected = true)
        )
        suspend fun saveAttachmentVersion(bytes: ByteArray, attachmentPassword: String) {
            val fields = currentUiEntry(
                entryId,
                "P2 interoperability sample",
                attachmentPassword,
                customFields = currentFields,
                attachments = listOf(UiAttachment(
                    id = "$entryId-0",
                    fileName = "sample.bin",
                    fileSizeFormatted = "${bytes.size} B",
                    refIndex = 0,
                    data = bytes
                ))
            )
            assertTrue("互操作样本附件版本保存失败", repository.saveEntry(
                fields,
                protectedFieldChars = mapOf(secretKey to "synthetic-protected-value".toCharArray()),
                loadedCustomFieldKeys = currentFields.associate { it.id to it.key }
            ) is KdbxResult.Success)
        }
        saveAttachmentVersion(oldAttachment, "old-password")
        saveAttachmentVersion(currentAttachment, "current-password")
        val oldRevision = projectedRevisionIds(entryId).first()
        assertTrue("互操作样本历史回滚失败", repository.rollbackEntryToRevision(entryId, oldRevision) is KdbxResult.Success)

        session.updateDatabaseMeta {
            it.copy(
                databaseName = "P2 interoperability sample",
                databaseDescription = "Synthetic history, attachment, custom-field, and Meta sample",
                defaultUserName = "synthetic-user",
                customData = mapOf("synthetic-key" to "synthetic-value")
            )
        }
        assertTrue("互操作样本 Meta 保存失败", session.save() is KdbxResult.Success)

        val parsed = parse(file)
        try {
            val entry = requireNotNull(parsed.rootGroup.findEntry(hexUuid(entryId)))
            assertTrue("样本必须保留多份历史", entry.history.size >= 3)
            assertEquals("改名旧字段不得残留", listOf("Y", "SECRET"), entry.customFields.map { it.key })
            assertArrayEquals("历史附件回滚后内容必须为旧版", oldAttachment, entry.attachments.single().data)
            assertEquals("Meta 自定义数据必须保留", "synthetic-value", parsed.customData["synthetic-key"])
        } finally {
            parsed.clearSensitiveData()
            oldAttachment.fill(0)
            currentAttachment.fill(0)
        }

        val exported = File(context.filesDir, "p2-632-633-634-659-interop.kdbx")
        file.copyTo(exported, overwrite = true)
        assertTrue("已授权导出的合成 KDBX 必须非空", exported.length() > 0L)
        val publicName = "p2-632-633-634-659-interop-${UUID.randomUUID()}.kdbx"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, publicName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri, "w")!!.use { output -> exported.inputStream().use { it.copyTo(output) } }
        assertEquals("公开下载区导出字节必须与应用私有文件相同", exported.length(), context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize })
    }

    private fun registerFreshFile(tag: String): File = runBlocking {
        val dir = File(context.filesDir, "p2-632-635-$tag-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(dir, "vault.kdbx").also { ownedFiles += it }
        assertTrue(
            "合成库创建失败",
            session.create(file, tag, password.clone(), useArgon2 = false) is KdbxResult.Success
        )
        val imported = repository.importExternalDatabase("p2-632-635-$tag", file.absolutePath)
        assertTrue("库登记失败: $imported", imported is KdbxResult.Success)
        file
    }

    private suspend fun unlockActiveFile() {
        val unlocked = repository.unlockActiveDatabase(password.clone(), null, readOnly = false)
        assertTrue("活动文件重开失败: $unlocked", unlocked is KdbxResult.Success)
    }

    private suspend fun registerEntry(
        file: File,
        title: String,
        password: String,
        groupId: String? = null
    ): String {
        val id = "0000000000000000000000000000a632"
        val entry = currentUiEntry(id, title, password, groupId)
        val saved = repository.saveEntry(entry, password.toCharArray())
        assertTrue("条目初始保存失败: $saved", saved is KdbxResult.Success)
        assertEquals("条目必须落在正式文件", password, currentPassword(file, id))
        return id
    }

    private suspend fun save(file: File, entryId: String, title: String, password: String) {
        val result = repository.saveEntry(
            currentUiEntry(entryId, title, password),
            password.toCharArray()
        )
        assertTrue("条目保存失败: $result", result is KdbxResult.Success)
        assertEquals(currentPassword(file, entryId), password)
    }

    private suspend fun createGroup(file: File, rootId: String, name: String): String {
        val id = when (name) {
            "Child A" -> "0000000000000000000000000000b635"
            else -> "0000000000000000000000000000c635"
        }
        val saved = repository.saveGroup(VaultGroup(id = id, name = name, parentId = rootId))
        assertTrue("分组保存失败: $saved", saved is KdbxResult.Success)
        val parsed = parse(file)
        try {
            assertNotNull("新分组必须能在正式文件找到", parsed.rootGroup.findGroup(hexUuid(id)))
        } finally {
            parsed.clearSensitiveData()
        }
        return id
    }

    private fun currentPassword(file: File, entryId: String): String {
        val db = parse(file)
        return try {
            val entry = db.rootGroup.findEntry(hexUuid(entryId)) ?: error("正式文件缺少条目 $entryId")
            entry.password?.readString().orEmpty()
        } finally {
            db.clearSensitiveData()
        }
    }

    private fun projectedRevisionIds(entryId: String): List<String> = runBlocking {
        val entry = repository.getEntry(entryId).first() ?: error("UI 投影缺少条目 $entryId")
        entry.revisions.map { it.id }
    }

    private fun currentUiEntry(
        id: String,
        title: String,
        password: String? = null,
        groupId: String? = null,
        customFields: List<UiCustomField> = emptyList(),
        attachments: List<UiAttachment> = emptyList()
    ): UiVaultEntry = UiVaultEntry(
        id = id,
        title = title,
        username = "device-user",
        url = "https://device.invalid",
        groupId = groupId,
        customFields = customFields,
        attachments = attachments
    )

    private fun parse(file: File): KdbxDatabase = KdbxFile.load(file.inputStream(), password.clone())

    private fun hexUuid(hex: String) = com.keepasskey.core.model.KdbxUuid.fromHexString(hex)

    private fun releasePersisted(uri: Uri) {
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            ?: return
        var flags = 0
        if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags != 0) context.contentResolver.releasePersistableUriPermission(uri, flags)
    }
}
